/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.dialog

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ExcludedScanPathsKey
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.utils.scanners.MusicFolderDiscovery
import com.dd3boh.outertune.utils.scanners.stringFromUriList
import com.dd3boh.outertune.utils.scanners.uriListFromString

/**
 * 隐藏文件夹对话框
 *
 * 扫描「扫描路径」下的所有文件夹，把含音乐的文件夹列出来，
 * 用户用开关把不想进媒体库的文件夹隐藏掉（隐藏 = 不参与扫描）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenScanFoldersDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val scanPaths by rememberPreference(ScanPathsKey, defaultValue = "")
    val (hiddenFolders, onHiddenFoldersChange) = rememberPreference(ExcludedScanPathsKey, defaultValue = "")

    val folders by MusicFolderDiscovery.folders.collectAsState()
    val isScanning by MusicFolderDiscovery.isScanning.collectAsState()
    val scannedDirs by MusicFolderDiscovery.scannedDirCount.collectAsState()
    val scannedSongs by MusicFolderDiscovery.scannedSongCount.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    val scanFailed by MusicFolderDiscovery.scanFailed.collectAsState()

    val hiddenKeys = remember(hiddenFolders) {
        uriListFromString(hiddenFolders).map { it.toString() }.toSet()
    }

    fun startScan() {
        MusicFolderDiscovery.scanAsync(context, uriListFromString(scanPaths))
    }

    // 首次打开时如果还没有扫描结果，自动扫一次
    LaunchedEffect(Unit) {
        if (!MusicFolderDiscovery.hasScanned.value && !MusicFolderDiscovery.isScanning.value) {
            startScan()
        }
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

    val filteredFolders = remember(folders, searchQuery) {
        if (searchQuery.isBlank()) folders
        else folders.filter { it.path.contains(searchQuery.trim(), ignoreCase = true) }
    }
    val hiddenCount = folders.count { it.uri.toString() in hiddenKeys }

    DefaultDialog(
        onDismiss = onDismiss,
        icon = { Icon(Icons.Rounded.VisibilityOff, null) },
        title = { Text(stringResource(R.string.hidden_folders_title)) },
        horizontalAlignment = Alignment.Start,
        buttons = {
            TextButton(
                onClick = { startScan() },
                enabled = !isScanning,
            ) {
                Text(stringResource(R.string.hidden_folders_rescan))
            }

            if (hiddenCount > 0) {
                TextButton(
                    onClick = { onHiddenFoldersChange("") },
                ) {
                    Text(stringResource(R.string.hidden_folders_unhide_all))
                }
            }

            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.ok))
            }
        }
    ) {
        Text(
            text = stringResource(R.string.hidden_folders_description),
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(8.dp))

        if (isScanning) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = stringResource(R.string.hidden_folders_scanning),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.hidden_folders_scan_progress, scannedDirs, scannedSongs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
        } else {
            Text(
                text = if (scanFailed) {
                    stringResource(R.string.hidden_folders_scan_failed)
                } else {
                    stringResource(R.string.hidden_folders_summary, folders.size, hiddenCount)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        if (folders.isNotEmpty()) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.hidden_folders_search)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )
        }

        if (!isScanning && folders.isEmpty()) {
            Text(
                text = stringResource(R.string.hidden_folders_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(vertical = 12.dp)
            )
        }

        if (filteredFolders.isNotEmpty()) {
            val lazyListState = rememberLazyListState()
            Box {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .padding(top = 4.dp)
                ) {
                    items(
                        items = filteredFolders,
                        key = { it.uri.toString() },
                    ) { folder ->
                        val hidden = folder.uri.toString() in hiddenKeys
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { setHidden(folder.uri, !hidden) }
                                .padding(vertical = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 8.dp)
                            ) {
                                Text(
                                    text = folder.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hidden) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (folder.songCount > folder.directSongCount) {
                                        stringResource(
                                            R.string.hidden_folders_song_count_nested,
                                            folder.directSongCount,
                                            folder.songCount
                                        )
                                    } else {
                                        stringResource(R.string.hidden_folders_song_count, folder.songCount)
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontSize = 11.sp,
                                )
                            }
                            Switch(
                                checked = hidden,
                                onCheckedChange = { setHidden(folder.uri, it) },
                            )
                        }
                    }
                }
                LazyColumnScrollbar(state = lazyListState)
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}
