package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            OutlinedCard(onClick = { navController.navigate("kugou/search") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null)
                    Text("搜索酷狗歌曲、歌单", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        item("refresh") {
            TextButton(onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.padding(horizontal = 8.dp)) { Text("刷新推荐") }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            state.error?.let { KugouMessage(it, viewModel::refresh) }
        }
        item("radio") {
            Row(Modifier.fillMaxWidth().clickable { playSection(data.radioSongs, "红心电台") }
                .padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(52.dp))
                Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("红心电台", style = MaterialTheme.typography.headlineSmall)
                    Text("按你的口味推荐歌曲", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { playSection(data.radioSongs, "红心电台") }) { Text("播放") }
            }
            (data.errors["radio"] ?: data.errors["radioSongs"])?.let { KugouMessage(it, viewModel::refresh) }
            if (data.radioSongs.isEmpty() && !state.loading) KugouMessage(if (account.status == KugouAccountStatus.SIGNED_IN) "暂无个人推荐，请稍后刷新。" else "个人推荐需要账号授权，可通过设置管理账号。")
        }
        itemsIndexed(data.radioSongs.take(3), key = { _, song -> "radio_${song.id}" }) { index, song ->
            KugouSongRow(song, navController, onPlay = { playback(data.radioSongs, index, "红心电台") })
        }
        item("daily_title") {
            KugouSectionTitle("每日推荐", "播放全部") { playSection(data.dailySongs, "每日推荐") }
            (data.errors["daily"] ?: data.errors["dailySongs"])?.let { KugouMessage(it, viewModel::refresh) }
            if (data.dailySongs.isEmpty() && !state.loading) KugouMessage(if (account.status == KugouAccountStatus.SIGNED_IN) "今日暂无推荐，请稍后刷新。" else "每日推荐需要账号授权，可通过设置管理账号。")
        }
        itemsIndexed(data.dailySongs, key = { _, song -> "daily_${song.id}" }) { index, song ->
            KugouSongRow(song, navController, onPlay = { playback(data.dailySongs, index, "每日推荐") })
        }
        item("playlists_title") {
            KugouSectionTitle("推荐歌单", "更多") { navController.navigate("kugou/browse/playlists") }
            data.errors["playlists"]?.let { KugouMessage(it, viewModel::refresh) }
            if (data.playlists.isEmpty() && !state.loading) KugouMessage("暂无推荐歌单，请稍后刷新。")
        }
        item("playlists") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(data.playlists, key = { it.id }) { collection ->
                    Column(Modifier.width(144.dp).clickable { navController.navigate(collectionRoute("playlist", collection.id)) }) {
                        KugouCover(collection.coverUrl, Modifier.size(144.dp))
                        Text(collection.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        item("ranks_title") {
            KugouSectionTitle("排行榜", "全部榜单") { navController.navigate("kugou/browse/ranks") }
            data.errors["ranks"]?.let { KugouMessage(it, viewModel::refresh) }
            if (data.ranks.isEmpty() && !state.loading) KugouMessage("暂无榜单，请稍后刷新。")
        }
        items(data.ranks, key = { "rank_${it.id}" }) { collection ->
            KugouCollectionRow(collection, "rank", navController, preview = true)
        }
        item("new_title") {
            KugouSectionTitle("新歌速递", "播放全部") { playSection(data.newSongs, "新歌速递") }
            (data.errors["new"] ?: data.errors["newSongs"])?.let { KugouMessage(it, viewModel::refresh) }
            if (data.newSongs.isEmpty() && !state.loading) KugouMessage("暂无新歌，请稍后刷新。")
        }
        itemsIndexed(data.newSongs, key = { _, song -> "new_${song.id}" }) { index, song ->
            KugouSongRow(song, navController, onPlay = { playback(data.newSongs, index, "新歌速递") })
        }
    }
}
