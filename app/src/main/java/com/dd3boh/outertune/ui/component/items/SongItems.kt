/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */
package com.dd3boh.outertune.ui.component.items

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalDownloadUtil
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.GridThumbnailHeight
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.models.DirectoryTree
import com.dd3boh.outertune.ui.component.PlayingIndicatorBox
import com.dd3boh.outertune.ui.component.SwipeToQueueBox
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.menu.FolderMenu
import com.dd3boh.outertune.ui.menu.MenuState
import com.dd3boh.outertune.ui.menu.SongMenu
import com.dd3boh.outertune.utils.joinByBullet
import com.dd3boh.outertune.utils.makeTimeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongListItem(
    song: Song,
    albumIndex: Int? = null,
    playlistSong: PlaylistSong? = null,
    playlist: Playlist? = null,
    navController: NavController,
    snackbarHostState: SnackbarHostState? = null,

    isActive: Boolean,
    isPlaying: Boolean,
    inSelectMode: Boolean?,
    isSelected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    swipeEnabled: Boolean,

    showMenu: Boolean = true,
    showLikedIcon: Boolean = true,
    showInLibraryIcon: Boolean = true,
    showDownloadIcon: Boolean = true,

    thumbnailSize: Int,
    onPlay: () -> Unit,
    dragHandleModifier: Modifier? = null,
    modifier: Modifier = Modifier,
) {
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return

    val listItem: @Composable () -> Unit = {
        ListItem(
            title = song.song.title,
            subtitle = joinByBullet(
                (if (BuildConfig.DEBUG) song.song.id else ""),
                song.artists.joinToString { it.name },
                makeTimeString(song.song.duration * 1000L)
            ),
            badges = {
                if (showLikedIcon && song.song.liked) {
                    Icon.Favorite()
                }
                if (showInLibraryIcon && song.song.isLocal) {
                    Icon.FolderCopy()
                } else if (showInLibraryIcon && song.song.inLibrary != null) {
                    Icon.Library()
                }
                if (showDownloadIcon && !song.song.isLocal) {
                    val download by LocalDownloadUtil.current.getDownload(song.id).collectAsState(initial = null)
                    Icon.Download(download)
                }
            },
            thumbnailContent = {
                ItemThumbnail(
                    thumbnailUrl = song.song.thumbnailUrl ?: song.song.localPath,
                    preferredSize = thumbnailSize,
                    albumIndex = albumIndex,
                    isActive = isActive,
                    isPlaying = isPlaying,
                    shape = RoundedCornerShape(ThumbnailCornerRadius),
                    modifier = Modifier.size(ListThumbnailSize)
                )
            },
            trailingContent = {
                if (inSelectMode == true) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = onSelectedChange
                    )
                } else {
                    IconButton(
                        onClick = {
                            if (showMenu) {
                                menuState.show {
                                    SongMenu(
                                        originalSong = song,
                                        playlistSong = playlistSong,
                                        playlist = playlist,
                                        navController = navController,
                                        onDismiss = menuState::dismiss
                                    )
                                }
                            }

                            haptic.performHapticFeedback(HapticFeedbackType.Companion.ContextClick)
                        }
                    ) {
                        Icon(
                            Icons.Rounded.MoreVert,
                            contentDescription = null
                        )
                    }
                }

                if (dragHandleModifier != null) {
                    IconButton(
                        onClick = { },
                        modifier = dragHandleModifier
                    ) {
                        Icon(
                            Icons.Rounded.DragHandle,
                            contentDescription = null
                        )
                    }
                }
            },
            isSelected = inSelectMode == true && isSelected,
            isActive = isActive,
            modifier = modifier.combinedClickable(
                onClick = {
                    if (inSelectMode == true) {
                        onSelectedChange(!isSelected)
                    } else if (isActive) {
                        playerConnection.player.togglePlayPause()
                    } else {
                        onPlay()
                    }
                },
                onLongClick = {
                    if (inSelectMode == null) {
                        menuState.show {
                            SongMenu(
                                originalSong = song,
                                navController = navController,
                                onDismiss = menuState::dismiss
                            )
                        }
                    } else if (!inSelectMode) {
                        haptic.performHapticFeedback(HapticFeedbackType.Companion.LongPress)
                        onSelectedChange(true)
                    }
                }
            )
        )
    }

    SwipeToQueueBox(
        item = song.toMediaItem(),
        content = { listItem() },
        snackbarHostState = snackbarHostState,
        swipeEnabled = swipeEnabled
    )
}

@Composable
fun SongFolderItem(
    folderTitle: String,
    modifier: Modifier = Modifier,
) = ListItem(
    title = folderTitle, thumbnailContent = {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            modifier = modifier.size(48.dp)
        )
    },
    modifier = modifier
)

@Composable
fun SongFolderItem(
    folderTitle: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
) = ListItem(
    title = folderTitle,
    subtitle = subtitle,
    thumbnailContent = {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            modifier = modifier.size(48.dp)
        )
    },
    modifier = modifier
)

@Composable
fun SongFolderItem(
    folder: DirectoryTree,
    modifier: Modifier = Modifier,
    folderTitle: String? = null,
    menuState: MenuState,
    navController: NavController,
    subtitle: String?,
) {
    val coroutineScope = rememberCoroutineScope()
    val database = LocalDatabase.current
    var subDirSongCount by remember {
        mutableIntStateOf(0)
    }
    LaunchedEffect(Unit) {
        if (subtitle == null) {
            CoroutineScope(Dispatchers.IO).launch {
                database.localSongCountInPath(folder.getFullSquashedDir()).first()
                subDirSongCount = database.localSongCountInPath(folder.getFullSquashedDir()).first()
            }
        }
    }

    ListItem(
        title = folderTitle ?: folder.currentDir,
        subtitle = subtitle ?: pluralStringResource(R.plurals.n_song, subDirSongCount, subDirSongCount),
        thumbnailContent = {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                modifier = modifier.size(48.dp)
            )
        },
        trailingContent = {
            val haptic = LocalHapticFeedback.current
            IconButton(
                onClick = {
                    menuState.show {
                        FolderMenu(
                            folder = folder,
                            coroutineScope = coroutineScope,
                            navController = navController,
                            onDismiss = menuState::dismiss
                        )
                    }
                    haptic.performHapticFeedback(HapticFeedbackType.Companion.ContextClick)
                }
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = null
                )
            }
        },
        modifier = modifier
    )
}
