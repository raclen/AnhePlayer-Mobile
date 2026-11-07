package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.source.kugou.KugouAccountStatus
import com.dd3boh.outertune.source.kugou.KugouConcept
import com.dd3boh.outertune.source.kugou.KugouSearchType
import com.dd3boh.outertune.viewmodels.KugouSearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KugouSearchScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: KugouSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val history by viewModel.history.collectAsState()
    val playback = rememberKugouPlayback(navController)
    val focus = LocalFocusManager.current
    val snackbar = LocalSnackbarHostState.current
    val account by KugouConcept.accountState.collectAsState()
    LaunchedEffect(state.songsError, state.playlistsError, account.status) {
        if ((state.songsError != null || state.playlistsError != null) &&
            (account.status == KugouAccountStatus.SIGNED_OUT || account.status == KugouAccountStatus.EXPIRED)) {
            if (snackbar.showSnackbar("酷狗搜索需要账号授权", actionLabel = "前往设置", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                navController.navigate("settings/kugou")
            }
        }
    }
    CancelOnKugouPageStop(viewModel::cancelLoading)
    fun submit(query: String = state.query) {
        viewModel.submit(query)
        focus.clearFocus()
    }
    KugouScreenFrame("酷狗搜索", navController, scrollBehavior) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = kugouContentPadding()) {
            item("input") {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    BasicTextField(
                        value = state.query, onValueChange = viewModel::updateQuery, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit() }),
                        decorationBox = { input ->
                            Row(Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                    if (state.query.isEmpty()) Text("搜索歌曲、歌手、歌单", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    input()
                                }
                                if (state.query.isNotEmpty()) IconButton(onClick = { viewModel.updateQuery("") }) {
                                    Icon(Icons.Rounded.Close, "清除搜索文字", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { submit() }) { Icon(Icons.Rounded.Search, "搜索并记录历史", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                            }
                        },
                    )
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                    KugouSegmentedControl(listOf("歌曲", "歌单"), if (state.type == KugouSearchType.SONG) 0 else 1) {
                        viewModel.selectType(if (it == 0) KugouSearchType.SONG else KugouSearchType.PLAYLIST)
                    }
                }
            }
            if (state.query.isBlank()) {
                item("history_title") { KugouSectionTitle("搜索历史", if (history.isNotEmpty()) "清空" else null, onAction = viewModel::clearHistory) }
                items(history.take(20), key = { "history_${it.id}" }) { entry ->
                    Row(
                        Modifier.fillMaxWidth().clickable { submit(entry.query) }.heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Text(entry.query, Modifier.weight(1f).padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                        IconButton(onClick = { viewModel.deleteHistory(entry) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Rounded.Close, "删除${entry.query}的搜索记录") }
                    }
                }
                if (history.isEmpty()) item("history_empty") { KugouMessage("输入歌名或歌手开始搜索。搜索历史遵循应用的暂停记录设置。") }
            } else {
                if (state.localSongs.isNotEmpty()) {
                    item("local_title") {
                        KugouSectionTitle("本地匹配")
                        Text("${state.localSongs.size} 首本地歌曲", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    }
                    itemsIndexed(state.localSongs, key = { _, song -> "local_${song.id}" }) { index, song ->
                        KugouSongRow(song, navController, onPlay = { playback(state.localSongs, index, "本地搜索：${state.query.trim()}") }, sourceLabel = "本地音乐")
                    }
                }
                item("online_title") {
                    KugouSectionTitle(if (state.type == KugouSearchType.SONG) "酷狗概念版歌曲" else "酷狗概念版歌单")
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val page = if (state.type == KugouSearchType.SONG) state.songPage else state.playlistPage
                        val count = if (state.type == KugouSearchType.SONG) state.songs.size else state.playlists.size
                        Text(
                            if (page > 0) "已加载 $count ${if (state.type == KugouSearchType.SONG) "首歌曲" else "个歌单"}，本地匹配置于前面" else "搜索“${state.query.trim()}”，本地匹配优先显示",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (state.songsLoading || state.playlistsLoading) {
                            TextButton(onClick = viewModel::cancelLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消加载") }
                        }
                    }
                }
                if (state.type == KugouSearchType.SONG) {
                    itemsIndexed(state.songs, key = { _, song -> "online_${song.id}" }) { index, song ->
                        KugouSongRow(song, navController, onPlay = { playback(state.songs, index, "酷狗搜索：${state.query.trim()}（已加载结果）") })
                    }
                    if (state.songPage > 0 && state.songs.isEmpty() && !state.songsLoading) item("songs_empty") { KugouMessage("没有找到匹配歌曲，试试其他关键词。") }
                    item("songs_footer") { KugouPageFooter(state.songsLoading, state.songsHasMore, state.songsError, viewModel::loadMore, page = state.songPage) }
                } else {
                    items(state.playlists, key = { "playlist_${it.id}" }) { collection -> KugouCollectionRow(collection, "playlist", navController) }
                    if (state.playlistPage > 0 && state.playlists.isEmpty() && !state.playlistsLoading) item("playlists_empty") { KugouMessage("没有找到匹配歌单，试试其他关键词。") }
                    item("playlists_footer") { KugouPageFooter(state.playlistsLoading, state.playlistsHasMore, state.playlistsError, viewModel::loadMore, page = state.playlistPage) }
                }
            }
        }
    }
}
