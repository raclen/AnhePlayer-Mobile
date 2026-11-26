package com.dd3boh.outertune.ui.screens.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.OnlinePlaylistViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlinePlaylistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: OnlinePlaylistViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val playerConnection = LocalPlayerConnection.current ?: return
    val activeMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)
    val snackbarHostState = LocalSnackbarHostState.current
    val listState = rememberLazyListState()
    val play: (Int) -> Unit = { index ->
        if (state.songs.isNotEmpty()) playerConnection.playQueue(ListQueue(
            title = "${viewModel.title}（已加载歌曲）", items = state.songs, startIndex = index,
        ), replace = true)
    }

    Box(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
        LazyColumn(state = listState, contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
            item(key = "header") {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(viewModel.sourceLabel, style = MaterialTheme.typography.titleMedium)
                    Text("已加载 ${state.songs.size} 首${if (state.hasMore) "，可继续加载" else ""}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { play(0) }, enabled = state.songs.isNotEmpty(), modifier = Modifier.padding(top = 12.dp)) {
                        Text("播放已加载歌曲")
                    }
                }
            }
            items(state.songs, key = { it.id }) { song ->
                OnlineSongRow(song, activeMetadata?.id, isPlaying, swipeEnabled, snackbarHostState) {
                    if (activeMetadata?.id == song.id) playerConnection.player.togglePlayPause()
                    else play(state.songs.indexOfFirst { it.id == song.id })
                }
            }
            if (state.page > 0 && state.songs.isEmpty() && !state.loading) {
                item(key = "empty") { Text(if (viewModel.isToplist) "排行榜暂无歌曲" else "歌单暂无歌曲", Modifier.padding(16.dp)) }
            }
            item(key = "footer") { SearchPageFooter(state.loading, state.hasMore, state.error, viewModel::loadMore) }
        }
        LazyColumnScrollbar(state = listState)
        TopAppBar(
            title = { Text(viewModel.title, maxLines = 1) },
            navigationIcon = { IconButton(onClick = { navController.navigateUp() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") } },
            windowInsets = TopBarInsets,
            scrollBehavior = scrollBehavior,
        )
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current).align(Alignment.BottomCenter))
    }
}
