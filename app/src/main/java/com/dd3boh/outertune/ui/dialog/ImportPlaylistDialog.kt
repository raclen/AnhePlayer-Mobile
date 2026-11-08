package com.dd3boh.outertune.ui.dialog

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.PlaylistFilter
import com.dd3boh.outertune.constants.PlaylistSortType
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.PlaylistEntity
import com.dd3boh.outertune.db.entities.PlaylistSongMap
import com.dd3boh.outertune.source.playlist.ImportedPlaylist
import com.dd3boh.outertune.source.playlist.PlaylistImporter
import com.dd3boh.outertune.source.playlist.PlaylistPlatform
import com.dd3boh.outertune.ui.component.LazyColumnScrollbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

private enum class ImportTargetMode {
    NEW, EXISTING
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImportPlaylistDialog(
    initialTargetPlaylist: Playlist? = null,
    onDismiss: () -> Unit,
    onPlaylistImported: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = LocalDatabase.current
    val coroutineScope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var selectedPlatform by remember { mutableStateOf(PlaylistPlatform.AUTO) }
    var rawInput by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var importedResult by remember { mutableStateOf<ImportedPlaylist?>(null) }

    var targetMode by remember {
        mutableStateOf(if (initialTargetPlaylist != null) ImportTargetMode.EXISTING else ImportTargetMode.NEW)
    }
    var playlistName by remember { mutableStateOf("") }
    var selectedTargetPlaylist by remember { mutableStateOf<Playlist?>(initialTargetPlaylist) }
    var availablePlaylists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            database.playlists(PlaylistFilter.LIBRARY, PlaylistSortType.CREATE_DATE, true, 2)
                .first()
                .let { availablePlaylists = it }
        }
    }

    DefaultDialog(
        onDismiss = {
            if (!isLoading && !isSaving) onDismiss()
        },
        icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
        title = { Text(stringResource(R.string.import_online_playlist)) },
        buttons = {
            if (importedResult == null) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !isLoading,
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
                Button(
                    onClick = {
                        val input = rawInput.trim()
                        if (input.isBlank()) return@Button
                        isLoading = true
                        errorMessage = null
                        coroutineScope.launch {
                            try {
                                val result = PlaylistImporter.importPlaylist(input, selectedPlatform)
                                importedResult = result
                                playlistName = result.title
                            } catch (e: Exception) {
                                errorMessage = e.message ?: "解析歌单失败，请检查链接或网络状态"
                            } finally {
                                isLoading = false
                            }
                        }
                    },
                    enabled = rawInput.isNotBlank() && !isLoading,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_playlist_parsing))
                    } else {
                        Text(stringResource(R.string.import_playlist_parse))
                    }
                }
            } else {
                TextButton(
                    onClick = {
                        importedResult = null
                        errorMessage = null
                    },
                    enabled = !isSaving,
                ) {
                    Text(stringResource(R.string.import_playlist_prev_step))
                }
                Button(
                    onClick = {
                        val result = importedResult ?: return@Button
                        isSaving = true
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                val targetId: String
                                val targetDisplayName: String

                                // 1. 先持久化歌曲元数据
                                result.songs.forEach { song ->
                                    database.insert(song)
                                }

                                if (targetMode == ImportTargetMode.NEW) {
                                    val newTitle = playlistName.trim().ifBlank { result.title }
                                    val newPlaylist = PlaylistEntity(
                                        name = newTitle,
                                        thumbnailUrl = result.coverUrl,
                                        isEditable = true,
                                        isLocal = true,
                                        bookmarkedAt = LocalDateTime.now(),
                                    )
                                    database.query {
                                        insert(newPlaylist)
                                    }
                                    result.songs.forEachIndexed { index, song ->
                                        database.query {
                                            insert(
                                                PlaylistSongMap(
                                                    songId = song.id,
                                                    playlistId = newPlaylist.id,
                                                    position = index,
                                                )
                                            )
                                        }
                                    }
                                    targetId = newPlaylist.id
                                    targetDisplayName = newTitle
                                } else {
                                    val target = selectedTargetPlaylist
                                        ?: throw IllegalStateException("请选择目标歌单")
                                    database.addSongToPlaylist(target, result.songs.map { it.id })
                                    targetId = target.id
                                    targetDisplayName = target.playlist.name
                                }

                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.import_playlist_success_toast,
                                            targetDisplayName,
                                            result.songs.size,
                                        ),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    onDismiss()
                                    onPlaylistImported?.invoke(targetId)
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    errorMessage = e.message ?: "保存歌单失败"
                                    isSaving = false
                                }
                            }
                        }
                    },
                    enabled = !isSaving && (
                        (targetMode == ImportTargetMode.NEW && playlistName.isNotBlank()) ||
                            (targetMode == ImportTargetMode.EXISTING && selectedTargetPlaylist != null)
                        ),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("保存中...")
                    } else {
                        Text(stringResource(R.string.import_playlist_confirm))
                    }
                }
            }
        },
    ) {
        if (importedResult == null) {
            // 第一步：平台选择与输入
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = "选择来源平台",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    PlaylistPlatform.selectablePlatforms.forEach { platform ->
                        FilterChip(
                            selected = selectedPlatform == platform,
                            onClick = { selectedPlatform = platform },
                            label = { Text(platform.displayName, fontSize = 13.sp) },
                            leadingIcon = if (selectedPlatform == platform) {
                                { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(14.dp)) }
                            } else null,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = rawInput,
                    onValueChange = {
                        rawInput = it
                        errorMessage = null
                    },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.import_playlist_hint),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    },
                    supportingText = {
                        Text(stringResource(R.string.import_playlist_supporting_hint))
                    },
                    trailingIcon = {
                        if (rawInput.isNotEmpty()) {
                            IconButton(onClick = {
                                rawInput = ""
                                errorMessage = null
                            }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "清空")
                            }
                        } else {
                            IconButton(onClick = {
                                clipboardManager.getText()?.text?.let { text ->
                                    if (text.isNotBlank()) {
                                        rawInput = text
                                        errorMessage = null
                                    }
                                }
                            }) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "粘贴")
                            }
                        }
                    },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            // 第二步：歌单详情与目标选择
            val result = importedResult!!
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
            ) {
                // 歌单基本信息卡片
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp))
                        .padding(10.dp),
                ) {
                    if (result.coverUrl != null) {
                        AsyncImage(
                            model = result.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceColorAtElevation(12.dp)),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = result.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = result.platform.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = " · ${stringResource(R.string.import_playlist_song_count, result.songs.size)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 导入目标方式选择
                Text(
                    text = "导入目标",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    FilterChip(
                        selected = targetMode == ImportTargetMode.NEW,
                        onClick = { targetMode = ImportTargetMode.NEW },
                        label = { Text(stringResource(R.string.import_playlist_target_new)) },
                        leadingIcon = if (targetMode == ImportTargetMode.NEW) {
                            { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(14.dp)) }
                        } else null,
                    )
                    FilterChip(
                        selected = targetMode == ImportTargetMode.EXISTING,
                        onClick = { targetMode = ImportTargetMode.EXISTING },
                        label = { Text(stringResource(R.string.import_playlist_target_existing)) },
                        leadingIcon = if (targetMode == ImportTargetMode.EXISTING) {
                            { Icon(Icons.Rounded.Check, null, modifier = Modifier.size(14.dp)) }
                        } else null,
                        enabled = availablePlaylists.isNotEmpty(),
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (targetMode == ImportTargetMode.NEW) {
                    OutlinedTextField(
                        value = playlistName,
                        onValueChange = { playlistName = it },
                        label = { Text(stringResource(R.string.import_playlist_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.import_playlist_select_playlist),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                    val playlistListState = rememberLazyListState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                    ) {
                        LazyColumn(state = playlistListState) {
                            itemsIndexed(availablePlaylists, key = { _, item -> item.id }) { _, p ->
                                val selected = selectedTargetPlaylist?.id == p.id
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedTargetPlaylist = p }
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                            else MaterialTheme.colorScheme.surface,
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.LibraryMusic,
                                        contentDescription = null,
                                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = p.playlist.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        text = "${p.songCount} 首",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        }
                        LazyColumnScrollbar(state = playlistListState)
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 歌曲列表预览
                Text(
                    text = "包含曲目（${result.songs.size} 首）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                val songPreviewListState = rememberLazyListState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 150.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)),
                ) {
                    LazyColumn(state = songPreviewListState) {
                        itemsIndexed(result.songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.width(28.dp),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = song.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = song.artists.joinToString(" / ") { it.name },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                    LazyColumnScrollbar(state = songPreviewListState)
                }

                if (errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
