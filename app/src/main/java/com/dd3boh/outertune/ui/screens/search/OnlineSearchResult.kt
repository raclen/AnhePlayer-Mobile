package com.dd3boh.outertune.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.SwipeToQueueKey
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.source.lx.LxPlaylistClient
import com.dd3boh.outertune.source.lx.LxSearchSource
import com.dd3boh.outertune.ui.component.EmptyPlaceholder
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.ui.component.items.ItemThumbnail
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.ui.component.items.PlaylistListItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.utils.onlinePlaylistRoute
import com.dd3boh.outertune.utils.joinByBullet
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.OnlineSearchType
import com.dd3boh.outertune.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun OnlineSearchResult(navController: NavController, viewModel: OnlineSearchViewModel = hiltViewModel()) {
    val density = LocalDensity.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val state by viewModel.viewState.collectAsState()
    val activeMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val swipeEnabled by rememberPreference(SwipeToQueueKey, true)
    val snackbarHostState = LocalSnackbarHostState.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val searchingSongs = state.type == OnlineSearchType.SONGS
    val playlistSupported = LxPlaylistClient.supports(state.source)

    LazyColumn(state = listState, contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues()) {
        item(key = "type_picker") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(OnlineSearchType.entries, key = { it.name }) { type ->
                    FilterChip(selected = state.type == type, onClick = { viewModel.selectType(type) }, label = { Text(type.displayName) })
                }
            }
        }

        if (searchingSongs && state.localSongs.isNotEmpty()) {
            item(key = "local_header") { NavigationTitle(title = "本地歌曲") }
            val thumbnailSize = (ListThumbnailSize.value * density.density).roundToInt()
            items(state.localSongs, key = { "local_${it.id}" }) { song ->
                SongListItem(
                    song = song, navController = navController, snackbarHostState = snackbarHostState,
                    isActive = activeMetadata?.id == song.id, isPlaying = isPlaying,
                    inSelectMode = false, isSelected = false, onSelectedChange = {},
                    swipeEnabled = swipeEnabled, thumbnailSize = thumbnailSize,
                    onPlay = {
                        playerConnection.playQueue(ListQueue(
                            title = "本地搜索：${viewModel.query}",
                            items = state.localSongs.map { it.toMediaMetadata() },
                            startIndex = state.localSongs.indexOfFirst { it.id == song.id },
                        ))
                    },
                )
            }
        } else if (!searchingSongs && state.localPlaylists.isNotEmpty()) {
            item(key = "local_playlist_header") { NavigationTitle(title = "本地歌单") }
            items(state.localPlaylists, key = { "local_playlist_${it.id}" }) { playlist ->
                PlaylistListItem(playlist = playlist, modifier = Modifier.clickable { navController.navigate("local_playlist/${playlist.id}") })
            }
        }

        item(key = "source_picker") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(LxSearchSource.entries, key = { it.sourceId }) { source ->
                    FilterChip(selected = state.source == source, onClick = { viewModel.selectSource(source) }, label = { Text(source.displayName) })
                }
            }
        }

        if (searchingSongs) {
            if (state.items.isNotEmpty()) item(key = "online_header") { NavigationTitle(title = "${state.source.displayName}歌曲") }
            items(state.items, key = { it.id }) { item ->
                OnlineSongRow(item, activeMetadata?.id, isPlaying, swipeEnabled, snackbarHostState) {
                    if (activeMetadata?.id == item.id) playerConnection.player.togglePlayPause()
                    else scope.launch {
                        val playbackItems = viewModel.preparePlaybackQueue(item.id)
                        playerConnection.playQueue(ListQueue(
                            title = "${state.source.displayName}搜索：${viewModel.query}",
                            items = playbackItems, startIndex = playbackItems.indexOfFirst { it.id == item.id },
                        ), replace = true)
                    }
                }
            }
        } else {
            if (state.playlists.isNotEmpty()) item(key = "online_playlist_header") { NavigationTitle(title = "${state.source.displayName}歌单") }
            items(state.playlists, key = { "playlist_${it.source.sourceId}_${it.id}" }) { playlist ->
                ListItem(
                    title = playlist.name,
                    subtitle = joinByBullet(playlist.creator.takeIf { it.isNotBlank() }, playlist.songCount?.let { "$it 首" }),
                    thumbnailContent = {
                        ItemThumbnail(thumbnailUrl = playlist.thumbnailUrl, isActive = false, isPlaying = false, shape = RoundedCornerShape(ThumbnailCornerRadius), modifier = Modifier.size(ListThumbnailSize))
                    },
                    modifier = Modifier.clickable { navController.navigate(onlinePlaylistRoute(playlist)) },
                )
            }
        }

        if (!searchingSongs && !playlistSupported) {
            item(key = "unsupported") { EmptyPlaceholder(icon = Icons.Rounded.Search, text = "${state.source.displayName}暂不支持歌单搜索，请切换其他平台") }
        } else {
            val empty = if (searchingSongs) state.items.isEmpty() && state.localSongs.isEmpty()
                else state.playlists.isEmpty() && state.localPlaylists.isEmpty()
            if (!state.isLoading && state.error == null && empty) {
                item(key = "empty") { EmptyPlaceholder(icon = Icons.Rounded.Search, text = "没有搜索到${state.type.displayName}") }
            }
            item(key = "footer") { SearchPageFooter(state.isLoading, state.hasMore, state.error, viewModel::loadMore) }
        }
    }
    LazyColumnScrollbar(state = listState)
    Box(Modifier.fillMaxSize()) {
        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.windowInsetsPadding(LocalPlayerAwareWindowInsets.current).align(Alignment.BottomCenter))
    }
}
