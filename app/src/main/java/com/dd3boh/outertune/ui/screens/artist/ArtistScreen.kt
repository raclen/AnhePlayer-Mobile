package com.dd3boh.outertune.ui.screens.artist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.ui.component.items.AlbumGridItem
import com.dd3boh.outertune.ui.component.items.SongListItem
import com.dd3boh.outertune.ui.component.NavigationTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.ArtistViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ArtistViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val coroutineScope = rememberCoroutineScope()
    val artist by viewModel.libraryArtist.collectAsState()
    val songs by viewModel.librarySongs.collectAsState()
    val albums by viewModel.libraryAlbums.collectAsState()
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val swipeEnabled by rememberPreference(com.dd3boh.outertune.constants.SwipeToQueueKey, true)
    val snackbarHostState = com.dd3boh.outertune.LocalSnackbarHostState.current
    val thumbnailSize = (ListThumbnailSize.value * androidx.compose.ui.platform.LocalDensity.current.density).roundToInt()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(artist?.artist?.name.orEmpty()) },
            navigationIcon = {
                IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
                }
            },
            windowInsets = TopBarInsets,
            scrollBehavior = scrollBehavior
        )
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = artist?.artist?.name,
                                    items = songs.map { it.toMediaMetadata() },
                                    startShuffled = true
                                )
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Shuffle, null)
                        Text("随机播放")
                    }
                }
            }
            if (songs.isNotEmpty()) {
                item {
                    NavigationTitle("歌曲", onClick = {
                        navController.navigate("artist/${viewModel.artistId}/songs")
                    })
                }
                itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                    SongListItem(
                        song = song,
                        navController = navController,
                        snackbarHostState = snackbarHostState,
                        isActive = song.id == mediaMetadata?.id,
                        isPlaying = isPlaying,
                        inSelectMode = false,
                        isSelected = false,
                        onSelectedChange = {},
                        swipeEnabled = swipeEnabled,
                        thumbnailSize = thumbnailSize,
                        onPlay = {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = artist?.artist?.name,
                                    items = songs.map { it.toMediaMetadata() },
                                    startIndex = index
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (albums.isNotEmpty()) {
                item {
                    NavigationTitle("专辑", onClick = {
                        navController.navigate("artist/${viewModel.artistId}/albums")
                    })
                }
                items(albums, key = { it.id }) { album ->
                    AlbumGridItem(
                        album = album,
                        isActive = album.id == mediaMetadata?.album?.id,
                        isPlaying = isPlaying,
                        coroutineScope = coroutineScope,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .clickable { navController.navigate("album/${album.id}") },
                    )
                }
            }
        }
    }
}
