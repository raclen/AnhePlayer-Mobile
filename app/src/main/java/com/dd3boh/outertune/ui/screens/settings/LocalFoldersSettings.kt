/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ExcludedScanPathsKey
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.utils.scanners.MusicFolderInfo
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.scanners.MusicFolderDiscovery
import com.dd3boh.outertune.utils.scanners.stringFromUriList
import com.dd3boh.outertune.utils.scanners.uriListFromString

/**
 * 本地媒体 - 音乐文件夹
 *
 * 扫描后列出所有含音乐的文件夹，每个条目右侧有「⋮」菜单，可以直接隐藏 / 取消隐藏该文件夹。
 * 隐藏的文件夹会在后续扫描中被整棵跳过。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalFoldersSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val scanPaths by rememberPreference(ScanPathsKey, defaultValue = "")
    val (hiddenFolders, onHiddenFoldersChange) = rememberPreference(ExcludedScanPathsKey, defaultValue = "")

    val folders by MusicFolderDiscovery.folders.collectAsState()
    val isScanning by MusicFolderDiscovery.isScanning.collectAsState()
    val scannedDirs by MusicFolderDiscovery.scannedDirCount.collectAsState()
    val scannedSongs by MusicFolderDiscovery.scannedSongCount.collectAsState()
    val scanFailed by MusicFolderDiscovery.scanFailed.collectAsState()

    val hiddenKeys = remember(hiddenFolders) {
        uriListFromString(hiddenFolders).map { it.toString() }.toSet()
    }

    fun startScan() {
        MusicFolderDiscovery.scanAsync(context, uriListFromString(scanPaths))
    }

    fun setHidden(folderUri: Uri, hide: Boolean) {
        val current = uriListFromString(hiddenFolders).toMutableList()
        val key = folderUri.toString()
        if (hide) {
            if (current.none { it.toString() == key }) current.add(folderUri)
        } else {
            current.removeAll { it.toString() == key }
        }
        onHiddenFoldersChange(stringFromUriList(current))
    }

    // 进入页面就自动扫一次（已有结果则直接展示缓存）
    LaunchedEffect(Unit) {
        if (!MusicFolderDiscovery.isScanning.value && MusicFolderDiscovery.folders.value.isEmpty()) {
            startScan()
        }
    }

    val hiddenCount = folders.count { it.uri.toString() in hiddenKeys }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.fillMaxHeight()
    ) {
        // 顶部状态行
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (isScanning) {
                    Text(
                        text = stringResource(R.string.hidden_folders_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.hidden_folders_scan_progress, scannedDirs, scannedSongs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                } else {
                    Text(
                        text = if (scanFailed) {
                            stringResource(R.string.hidden_folders_scan_failed)
                        } else {
                            stringResource(R.string.hidden_folders_summary, folders.size, hiddenCount)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.folder_list_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            if (isScanning) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(12.dp))
                TextButton(onClick = { MusicFolderDiscovery.cancel() }) {
                    Text(stringResource(R.string.action_cancel))
                }
            } else {
                TextButton(onClick = { startScan() }) {
                    Text(stringResource(R.string.hidden_folders_rescan))
                }
            }
        }

        if (!isScanning && folders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.hidden_folders_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }

        Box(modifier = Modifier.fillMaxHeight()) {
            val lazyListState = rememberLazyListState()
            LazyColumn(state = lazyListState) {
                itemsIndexed(folders) { _, folder ->
                    FolderRow(
                        folder = folder,
                        hidden = folder.uri.toString() in hiddenKeys,
                        onHide = { setHidden(folder.uri, true) },
                        onUnhide = { setHidden(folder.uri, false) },
                    )
                }
                item {
                    Spacer(Modifier.height(96.dp))
                }
            }
            LazyColumnScrollbar(state = lazyListState)
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.grp_music_folders)) },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
private fun FolderRow(
    folder: MusicFolderInfo,
    hidden: Boolean,
    onHide: () -> Unit,
    onUnhide: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val name = folder.path.trimEnd('/').substringAfterLast('/').ifBlank { folder.path }
    val parent = folder.path.trimEnd('/').substringBeforeLast('/', "")

    ListItem(
        headlineContent = {
            Text(
                text = name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (hidden) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        },
        supportingContent = {
            Column {
                if (parent.isNotBlank()) {
                    Text(
                        text = parent,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 11.sp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (folder.songCount > folder.directSongCount) {
                            stringResource(
                                R.string.hidden_folders_song_count_nested,
                                folder.directSongCount,
                                folder.songCount
                            )
                        } else {
                            pluralStringResource(
                                R.plurals.scanner_n_song_found,
                                folder.songCount,
                                folder.songCount
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 11.sp,
                    )
                    if (hidden) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.folder_list_hidden_badge),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    if (hidden) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.folder_list_unhide)) },
                            leadingIcon = { Icon(Icons.Rounded.Visibility, null) },
                            onClick = {
                                menuExpanded = false
                                onUnhide()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.folder_list_hide)) },
                            leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null) },
                            onClick = {
                                menuExpanded = false
                                onHide()
                            }
                        )
                    }
                }
            }
        }
    )
}
