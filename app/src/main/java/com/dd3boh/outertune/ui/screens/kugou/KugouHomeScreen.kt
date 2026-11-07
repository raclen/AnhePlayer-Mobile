package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.kugou.KugouAccountStatus
import com.dd3boh.outertune.viewmodels.KugouHomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KugouHomeScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: KugouHomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val account by viewModel.accountState.collectAsState()
    val playback = rememberKugouPlayback(navController)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    val data = state.data
    fun playSection(songs: List<MediaMetadata>, title: String) {
        if (songs.isNotEmpty()) playback(songs, 0, title)
        else scope.launch {
            if (account.status != KugouAccountStatus.SIGNED_IN && (title == "红心电台" || title == "每日推荐")) {
                if (snackbar.showSnackbar("此推荐需要酷狗账号授权", actionLabel = "前往设置", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                    navController.navigate("settings/kugou")
                }
            } else snackbar.showSnackbar("暂无可播放歌曲，请刷新后重试")
        }
    }
    // 主导航的标题栏由 MainActivity 提供，首页只绘制内容。
    LazyColumn(Modifier.fillMaxSize(), contentPadding = kugouContentPadding()) {
        item("search") {
            Surface(
                onClick = { navController.navigate("kugou/search") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(Modifier.heightIn(min = 64.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                    Text("搜索歌曲、歌单", Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item("refresh") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.loading) "正在更新推荐…" else "发现合口味的音乐",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("刷新推荐")
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            state.error?.let { KugouMessage(it, viewModel::refresh, isError = true) }
        }
        item("radio") {
            KugouRadioHero(data.radioSongs) { playSection(data.radioSongs, "红心电台") }
            (data.errors["radio"] ?: data.errors["radioSongs"])?.let { KugouMessage(it, viewModel::refresh, isError = true) }
            if (data.radioSongs.isEmpty() && !state.loading) KugouMessage(if (account.status == KugouAccountStatus.SIGNED_IN) "暂无个人推荐，请稍后刷新。" else "个人推荐需要账号授权，可通过设置管理账号。")
        }
        itemsIndexed(data.radioSongs.take(3), key = { _, song -> "radio_${song.id}" }) { index, song ->
            KugouSongRow(song, navController, onPlay = { playback(data.radioSongs, index, "红心电台") })
        }
        item("daily_title") {
            KugouSectionTitle("每日推荐", "播放全部") { playSection(data.dailySongs, "每日推荐") }
            (data.errors["daily"] ?: data.errors["dailySongs"])?.let { KugouMessage(it, viewModel::refresh, isError = true) }
            if (data.dailySongs.isEmpty() && !state.loading) KugouMessage(if (account.status == KugouAccountStatus.SIGNED_IN) "今日暂无推荐，请稍后刷新。" else "每日推荐需要账号授权，可通过设置管理账号。")
        }
        itemsIndexed(data.dailySongs, key = { _, song -> "daily_${song.id}" }) { index, song ->
            KugouSongRow(song, navController, onPlay = { playback(data.dailySongs, index, "每日推荐") })
        }
        item("playlists_title") {
            KugouSectionTitle("推荐歌单", "更多") { navController.navigate("kugou/browse/playlists") }
            data.errors["playlists"]?.let { KugouMessage(it, viewModel::refresh, isError = true) }
            if (data.playlists.isEmpty() && !state.loading) KugouMessage("暂无推荐歌单，请稍后刷新。")
        }
        item("playlists") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(data.playlists, key = { it.id }) { collection ->
                    KugouCollectionTile(collection, navController, Modifier.width(152.dp))
                }
            }
        }
        item("ranks_title") {
            KugouSectionTitle("排行榜", "全部榜单") { navController.navigate("kugou/browse/ranks") }
            data.errors["ranks"]?.let { KugouMessage(it, viewModel::refresh, isError = true) }
            if (data.ranks.isEmpty() && !state.loading) KugouMessage("暂无榜单，请稍后刷新。")
        }
        itemsIndexed(data.ranks, key = { _, collection -> "rank_${collection.id}" }) { index, collection ->
            if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
            KugouCollectionRow(collection, "rank", navController, preview = true)
        }
        item("new_title") {
            KugouSectionTitle("新歌速递", "播放全部") { playSection(data.newSongs, "新歌速递") }
            (data.errors["new"] ?: data.errors["newSongs"])?.let { KugouMessage(it, viewModel::refresh, isError = true) }
            if (data.newSongs.isEmpty() && !state.loading) KugouMessage("暂无新歌，请稍后刷新。")
        }
        itemsIndexed(data.newSongs, key = { _, song -> "new_${song.id}" }) { index, song ->
            KugouSongRow(song, navController, onPlay = { playback(data.newSongs, index, "新歌速递") })
        }
    }
}

@Composable
private fun KugouRadioHero(songs: List<MediaMetadata>, onPlay: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        BoxWithConstraints(Modifier.padding(20.dp)) {
            val horizontal = maxWidth >= 300.dp && LocalDensity.current.fontScale < 1.3f
            if (horizontal) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KugouRadioArtwork(songs.firstOrNull(), Modifier.size(120.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        KugouRadioText(songs.firstOrNull())
                        KugouRadioPlayButton(onPlay)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    KugouRadioArtwork(songs.firstOrNull(), Modifier.size(144.dp).align(Alignment.CenterHorizontally))
                    KugouRadioText(songs.firstOrNull())
                    KugouRadioPlayButton(onPlay)
                }
            }
        }
    }
}

@Composable
private fun KugouRadioText(firstSong: MediaMetadata?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Rounded.Favorite, null, modifier = Modifier.size(16.dp))
        Text("按你的口味推荐", style = MaterialTheme.typography.labelMedium)
    }
    Text("红心电台", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
    firstSong?.let {
        Text(it.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun KugouRadioPlayButton(onPlay: () -> Unit) {
    Button(
        onClick = onPlay, modifier = Modifier.heightIn(min = 48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
    ) {
        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(24.dp))
        Spacer(Modifier.width(6.dp))
        Text("播放电台")
    }
}

@Composable
private fun KugouRadioArtwork(song: MediaMetadata?, modifier: Modifier = Modifier) {
    val record = MaterialTheme.colorScheme.surfaceContainerHighest
    val groove = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    val label = MaterialTheme.colorScheme.primary
    val spindle = MaterialTheme.colorScheme.onPrimary
    Box(modifier, contentAlignment = Alignment.Center) {
        // 唱片是静态主题图形，封面只使用真实返回的歌曲，不增加网络请求。
        Canvas(Modifier.fillMaxSize().padding(start = 20.dp)) {
            val radius = size.minDimension / 2f
            drawCircle(record, radius)
            listOf(0.82f, 0.68f, 0.54f).forEach { scale ->
                drawCircle(groove, radius * scale, style = Stroke(1.dp.toPx()))
            }
            drawCircle(label, radius * 0.3f)
            drawCircle(spindle, radius * 0.055f)
        }
        KugouCover(song?.getThumbnailModel(), Modifier.fillMaxSize(0.76f).align(Alignment.CenterStart), cornerRadius = 12.dp)
    }
}
