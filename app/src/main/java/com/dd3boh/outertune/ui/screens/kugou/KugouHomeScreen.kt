package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Player.STATE_IDLE
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.kugou.KugouAccountStatus
import com.dd3boh.outertune.source.kugou.KugouRadioMode
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
    val player = LocalPlayerConnection.current
    val currentSong = player?.mediaMetadata?.collectAsState()?.value
    val isPlaying = player?.isPlaying?.collectAsState()?.value == true
    val queueBoard = player?.queueBoard?.collectAsState()?.value
    val queuePlaylistId = player?.queuePlaylistId?.collectAsState()?.value
    val currentQueue = queueBoard?.getCurrentQueue()
    val activeMode = KugouRadioMode.fromQueue(currentQueue?.playlistId ?: queuePlaylistId, currentQueue?.title, currentSong?.source)
    val radioActive = activeMode == state.radioMode
    val radioSong = if (radioActive) currentSong else data.radioSongs.firstOrNull()

    fun playSection(songs: List<MediaMetadata>, title: String, playlistId: String? = null) {
        if (songs.isNotEmpty()) playback(songs, 0, title, playlistId)
        else scope.launch {
            if (account.status != KugouAccountStatus.SIGNED_IN && (KugouRadioMode.entries.any { it.title == title } || title == "每日推荐")) {
                if (snackbar.showSnackbar("此推荐需要酷狗账号授权", actionLabel = "前往设置", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                    navController.navigate("settings/kugou")
                }
            } else snackbar.showSnackbar("暂无可播放歌曲，请刷新后重试")
        }
    }

    fun toggleRadio() {
        playback.cancel()
        val queue = player?.queueBoard?.value?.getCurrentQueue()
        val mode = KugouRadioMode.fromQueue(queue?.playlistId, queue?.title, player?.mediaMetadata?.value?.source)
        if (player != null && mode == state.radioMode && account.status == KugouAccountStatus.SIGNED_IN) {
            if (player.player.playWhenReady && player.player.playbackState != STATE_ENDED) player.player.pause()
            else {
                if (player.player.playbackState == STATE_ENDED) player.player.seekToDefaultPosition(0)
                if (player.player.playbackState == STATE_IDLE) player.player.prepare()
                player.player.play()
            }
        } else playSection(data.radioSongs, state.radioMode.title, state.radioMode.playlistId)
    }

    fun radioLoadedCallback(): (List<MediaMetadata>, KugouRadioMode) -> Unit {
        val originalQueue = player?.queueBoard?.value?.getCurrentQueue()
        val originalMode = KugouRadioMode.fromQueue(originalQueue?.playlistId, originalQueue?.title, player?.mediaMetadata?.value?.source)
        val wasPlaying = player?.player?.let { it.playWhenReady && it.playbackState !in listOf(STATE_IDLE, STATE_ENDED) } == true
        return { songs, mode ->
            // 仅续接仍在播放的专区电台；暂停、切到每日推荐或其他音乐后不抢播放。
            val canContinue = {
                val queue = player?.queueBoard?.value?.getCurrentQueue()
                wasPlaying && originalMode != null && queue?.id == originalQueue?.id &&
                    KugouRadioMode.fromQueue(queue?.playlistId, queue?.title, player?.mediaMetadata?.value?.source) == originalMode &&
                    player?.player?.playWhenReady == true && player.player.playbackState !in listOf(STATE_IDLE, STATE_ENDED) &&
                    viewModel.state.value.radioMode == mode && !viewModel.state.value.radioLoading
            }
            if (canContinue()) playback(songs, 0, mode.title, mode.playlistId, canPlay = canContinue)
        }
    }

    fun refreshRadio() = viewModel.refreshRadio(radioLoadedCallback())
    // 主导航的标题栏由 MainActivity 提供，首页只绘制内容。
    KugouTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            BoxWithConstraints(Modifier.widthIn(max = 800.dp).fillMaxWidth()) {
                val contentWidth = maxWidth - 32.dp
                val columns = if (contentWidth >= 648.dp) 3 else if (contentWidth >= 300.dp && LocalDensity.current.fontScale < 1.3f) 2 else 1
                LazyColumn(Modifier.fillMaxSize(), contentPadding = kugouContentPadding()) {
                    item("search") {
                        Surface(
                            onClick = { navController.navigate("kugou/search") },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("搜索歌曲、歌手、歌单", Modifier.weight(1f).padding(start = 9.dp), fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item("radio") {
                        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                        state.error?.let { KugouMessage(it, viewModel::refresh, isError = true) }
                        KugouRadioHero(
                            song = radioSong, mode = state.radioMode, pendingMode = state.pendingRadioMode,
                            loading = state.loading || state.radioLoading, isPlaying = radioActive && isPlaying,
                            canPlay = radioActive || !(state.loading || state.radioLoading), canSelectMode = !state.loading,
                            onRefresh = ::refreshRadio, onPlay = ::toggleRadio,
                            onSelectMode = { viewModel.selectRadioMode(it, radioLoadedCallback()) },
                        )
                        if (state.radioLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
                        (state.radioError ?: data.errors["radio"] ?: data.errors["radioSongs"])?.let { message ->
                            KugouMessage(message, {
                                val failedMode = state.radioErrorMode
                                if (failedMode != null && failedMode != state.radioMode) viewModel.selectRadioMode(failedMode, radioLoadedCallback())
                                else refreshRadio()
                            }, isError = true)
                        }
                        if (data.radioSongs.isEmpty() && !state.loading && !state.radioLoading) KugouMessage(if (account.status == KugouAccountStatus.SIGNED_IN) "暂无个人推荐，请稍后刷新。" else "登录酷狗账号，开启个人推荐。账号可在设置中管理。")
                    }
                    item("daily_title") {
                        KugouSectionTitle("每日推荐", "播放全部", subtitle = "根据你的口味每天更新") { playSection(data.dailySongs, "每日推荐") }
                        (data.errors["daily"] ?: data.errors["dailySongs"])?.let { KugouMessage(it, viewModel::refresh, isError = true) }
                        if (data.dailySongs.isEmpty() && !state.loading) KugouMessage(if (account.status == KugouAccountStatus.SIGNED_IN) "今日暂无推荐，请稍后刷新。" else "每日推荐需要账号授权，可通过设置管理账号。")
                    }
                    item("daily") {
                        // 仅布局分组；点选仍使用完整推荐队列和原始索引，不截断播放列表。
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            itemsIndexed(data.dailySongs.chunked(6), key = { index, _ -> "daily_page_$index" }) { page, songs ->
                                KugouCard(Modifier.width(contentWidth)) {
                                    Column(Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
                                        songs.forEachIndexed { index, song ->
                                            KugouSongRow(song, navController, onPlay = { playback(data.dailySongs, page * 6 + index, "每日推荐") })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item("playlists_title") {
                        KugouSectionTitle("推荐歌单", "更多", subtitle = "此刻大家都在收藏") { navController.navigate("kugou/browse/playlists") }
                        data.errors["playlists"]?.let { KugouMessage(it, viewModel::refresh, isError = true) }
                        if (data.playlists.isEmpty() && !state.loading) KugouMessage("暂无推荐歌单，请稍后刷新。")
                    }
                    items(data.playlists.chunked(columns), key = { row -> "playlists_${row.first().id}" }) { row ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            row.forEach { collection -> KugouCollectionTile(collection, navController, Modifier.weight(1f)) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    item("ranks_title") {
                        KugouSectionTitle("排行榜", "全部榜单", subtitle = "现在大家都在听什么") { navController.navigate("kugou/browse/ranks") }
                        data.errors["ranks"]?.let { KugouMessage(it, viewModel::refresh, isError = true) }
                        if (data.ranks.isEmpty() && !state.loading) KugouMessage("暂无榜单，请稍后刷新。")
                    }
                    item("ranks") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(data.ranks, key = { it.id }) { collection ->
                                KugouCard(Modifier.width(contentWidth.coerceAtMost(360.dp))) {
                                    KugouCollectionRow(collection, "rank", navController, preview = true)
                                }
                            }
                        }
                    }
                    item("new_title") {
                        KugouSectionTitle("新歌速递", "播放全部", subtitle = "最新上架的好声音") { playSection(data.newSongs, "新歌速递") }
                        (data.errors["new"] ?: data.errors["newSongs"])?.let { KugouMessage(it, viewModel::refresh, isError = true) }
                        if (data.newSongs.isEmpty() && !state.loading) KugouMessage("暂无新歌，请稍后刷新。")
                    }
                    itemsIndexed(data.newSongs, key = { _, song -> "new_${song.id}" }) { index, song ->
                        KugouSongRow(song, navController, onPlay = { playback(data.newSongs, index, "新歌速递") })
                    }
                }
            }
        }
    }
}

@Composable
private fun KugouRadioHero(
    song: MediaMetadata?, mode: KugouRadioMode, pendingMode: KugouRadioMode?, loading: Boolean,
    isPlaying: Boolean, canPlay: Boolean, canSelectMode: Boolean,
    onRefresh: () -> Unit, onPlay: () -> Unit, onSelectMode: (KugouRadioMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val gradient = if (dark) listOf(Color(0xFFB8143C), Color(0xFF7F0D29), Color(0xFF3D0613))
        else listOf(Color(0xFFE6154A), Color(0xFFC5103E), Color(0xFF990B30))
    val subtitle = if (loading && pendingMode != null) "正在加载${pendingMode.title}…" else mode.subtitle
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 22.dp)
            .clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(gradient)),
    ) {
        val narrow = maxWidth < 280.dp || LocalDensity.current.fontScale >= 1.5f
        Column(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(start = 16.dp, end = 12.dp, bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Surface(onClick = { expanded = true }, enabled = canSelectMode, shape = CircleShape,
                        color = Color.White.copy(alpha = 0.18f), contentColor = Color.White) {
                        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(Icons.Rounded.Favorite, null, Modifier.size(13.dp))
                            Text(mode.title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Icon(Icons.Rounded.ExpandMore, "切换酷狗电台频道", Modifier.size(16.dp))
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        KugouRadioMode.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.title) }, enabled = canSelectMode && option != (pendingMode ?: mode),
                                trailingIcon = { if (option == mode) Icon(Icons.Rounded.Check, null) },
                                onClick = { expanded = false; onSelectMode(option) },
                            )
                        }
                    }
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Rounded.Refresh, "刷新${mode.title}", Modifier.size(18.dp), tint = Color.White.copy(alpha = if (loading) 0.5f else 1f))
                }
            }
            if (narrow) {
                KugouRadioText(song, mode, subtitle)
                KugouRadioArtwork(song, mode, isPlaying, canPlay, loading, onPlay, Modifier.align(Alignment.End).padding(top = 12.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { KugouRadioText(song, mode, subtitle) }
                    KugouRadioArtwork(song, mode, isPlaying, canPlay, loading, onPlay)
                }
            }
        }
    }
}

@Composable
private fun KugouRadioText(song: MediaMetadata?, mode: KugouRadioMode, subtitle: String) {
    Text(subtitle, fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
    Text(song?.title ?: "开启${mode.title}", modifier = Modifier.padding(top = 4.dp), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
        color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
    Text(song?.artists?.joinToString("、") { it.name }?.ifBlank { "未知歌手" } ?: "点击播放，听见新的喜欢",
        modifier = Modifier.padding(top = 3.dp), fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun KugouRadioArtwork(
    song: MediaMetadata?, mode: KugouRadioMode, isPlaying: Boolean, enabled: Boolean, loading: Boolean,
    onPlay: () -> Unit, modifier: Modifier = Modifier,
) {
    Box(modifier.size(88.dp), contentAlignment = Alignment.TopStart) {
        // 唱片是静态主题图形，封面只使用真实返回的歌曲，不增加网络请求。
        Box(Modifier.size(80.dp).clip(CircleShape).background(Color(0xFF12131F)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val radius = size.minDimension / 2f
                listOf(0.9f, 0.8f, 0.7f).forEach { drawCircle(Color.White.copy(alpha = 0.15f), radius * it, style = Stroke(1.dp.toPx())) }
            }
            KugouCover(song?.getThumbnailModel(), Modifier.size(58.dp), cornerRadius = 29.dp)
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF12131F)))
        }
        Surface(onClick = onPlay, enabled = enabled, modifier = Modifier.size(48.dp).align(Alignment.BottomEnd), shape = CircleShape, color = Color.White) {
            Box(contentAlignment = Alignment.Center) {
                if (loading && !enabled) CircularProgressIndicator(Modifier.size(22.dp), color = Color(0xFFBF103D), strokeWidth = 2.dp)
                else Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (isPlaying) "暂停${mode.title}" else "播放${mode.title}", Modifier.size(26.dp), tint = Color(0xFFBF103D))
            }
        }
    }
}
