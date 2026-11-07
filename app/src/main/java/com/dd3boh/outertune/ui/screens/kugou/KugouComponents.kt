package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.source.kugou.KUGOU_CONCEPT_SOURCE
import com.dd3boh.outertune.source.kugou.KugouAccountStatus
import com.dd3boh.outertune.source.kugou.KugouCollection
import com.dd3boh.outertune.source.kugou.KugouConcept
import com.dd3boh.outertune.ui.dialog.AddToPlaylistDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun collectionRoute(type: String, id: String) = "kugou/collection/$type/${android.net.Uri.encode(id)}"

@Composable
internal fun kugouContentPadding(): PaddingValues {
    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = insets.calculateStartPadding(direction),
        end = insets.calculateEndPadding(direction),
        top = insets.calculateTopPadding() + 8.dp,
        bottom = insets.calculateBottomPadding() + 24.dp,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KugouScreenFrame(
    title: String,
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        content()
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
            },
            windowInsets = TopBarInsets,
            scrollBehavior = scrollBehavior,
        )
    }
}

@Composable
internal fun CancelOnKugouPageStop(onStop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latestOnStop by rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) latestOnStop()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            latestOnStop()
        }
    }
}

@Composable
internal fun KugouSectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (action != null) {
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

@Composable
internal fun KugouMessage(message: String, retry: (() -> Unit)? = null, isError: Boolean = false) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
        contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Column(
            Modifier.padding(horizontal = if (isError) 12.dp else 0.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                if (isError) {
                    Icon(Icons.Rounded.ErrorOutline, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            if (retry != null) {
                TextButton(onClick = retry, modifier = Modifier.heightIn(min = 48.dp)) { Text("重试") }
            }
        }
    }
}

@Composable
internal fun KugouLoadingStatus(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun KugouPageFooter(loading: Boolean, hasMore: Boolean, error: String?, onMore: () -> Unit, page: Int = 0) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        if (page > 0) {
            Text(
                "已加载第 $page 页${if (!hasMore && error == null && !loading) "，列表已加载完" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        when {
            loading -> KugouLoadingStatus("正在加载…", Modifier.padding(horizontal = 16.dp))
            error != null -> KugouMessage(error, onMore, isError = true)
            hasMore -> OutlinedButton(
                onClick = onMore,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 48.dp),
            ) { Text("加载更多") }
        }
    }
}

@Composable
internal fun KugouCover(url: Any?, modifier: Modifier = Modifier, cornerRadius: Dp = 8.dp) {
    Box(
        modifier.clip(RoundedCornerShape(cornerRadius)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun KugouCollectionTile(collection: KugouCollection, navController: NavController, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable { navController.navigate(collectionRoute("playlist", collection.id)) }
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KugouCover(collection.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f), cornerRadius = 12.dp)
        Text(
            collection.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
        )
        if (collection.description.isNotBlank()) {
            Text(
                collection.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun KugouCollectionRow(collection: KugouCollection, type: String, navController: NavController, preview: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clickable { navController.navigate(collectionRoute(type, collection.id)) }
            .heightIn(min = 80.dp).padding(horizontal = 16.dp, vertical = if (preview) 14.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KugouCover(collection.coverUrl, Modifier.size(if (preview) 96.dp else 68.dp), cornerRadius = if (preview) 12.dp else 8.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(collection.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (preview && collection.songs.isNotEmpty()) {
                collection.songs.take(3).forEachIndexed { index, song ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${index + 1}", modifier = Modifier.width(20.dp),
                            style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else if (collection.description.isNotBlank()) {
                Text(collection.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (preview) {
                Text(
                    if (collection.songs.isNotEmpty()) "歌曲摘要，打开查看完整榜单" else "打开查看完整榜单",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (!preview) {
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp).size(20.dp))
        }
    }
}

@Composable
internal fun rememberKugouPlayback(navController: NavController): (List<MediaMetadata>, Int, String) -> Unit {
    val database = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val preparation = remember { KugouPlaybackPreparation() }
    return { songs, index, title ->
        preparation.job?.cancel()
        preparation.job = scope.launch {
            try {
                if (songs.isNotEmpty()) {
                    if (songs.any { it.source == KUGOU_CONCEPT_SOURCE } && KugouConcept.accountState.value.status != KugouAccountStatus.SIGNED_IN) {
                        if (snackbar.showSnackbar("酷狗账号需要登录，请前往设置", actionLabel = "前往设置", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                            navController.navigate("settings/kugou")
                        }
                    } else if (player == null) {
                        snackbar.showSnackbar("播放器尚未连接，请稍后重试")
                    } else {
                        val session = KugouConcept.sessionVersion.value
                        val needsAccount = songs.any { it.source == KUGOU_CONCEPT_SOURCE }
                        withContext(Dispatchers.IO) { songs.forEach { database.insert(it) } }
                        if (!needsAccount || session == KugouConcept.sessionVersion.value) {
                            player.playQueue(ListQueue(title = title, items = songs, startIndex = index.coerceIn(songs.indices)))
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: "播放准备失败，请重试")
            }
        }
    }
}

private class KugouPlaybackPreparation(var job: Job? = null)

@Composable
internal fun KugouSongRow(song: MediaMetadata, navController: NavController, onPlay: () -> Unit, sourceLabel: String = "酷狗概念版", trackNumber: Int? = null) {
    val database = LocalDatabase.current
    val player = LocalPlayerConnection.current
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    val storedSong by remember(song.id, database) { database.song(song.id) }.collectAsState(initial = null)
    var expanded by remember(song.id) { mutableStateOf(false) }
    var showPlaylist by remember(song.id) { mutableStateOf(false) }
    var saving by remember(song.id) { mutableStateOf(false) }
    val liked = storedSong?.song?.liked ?: song.liked

    fun saveAndAct(action: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { database.insert(song) }
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                snackbar.showSnackbar(error.message ?: "保存失败，请重试")
            } finally {
                saving = false
            }
        }
    }

    fun enqueue(next: Boolean) {
        saveAndAct {
            if (song.source == KUGOU_CONCEPT_SOURCE && KugouConcept.accountState.value.status != KugouAccountStatus.SIGNED_IN) {
                if (snackbar.showSnackbar("播放需要酷狗账号授权", "前往设置", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                    navController.navigate("settings/kugou")
                }
            } else if (player == null) {
                snackbar.showSnackbar("播放器尚未连接，请稍后重试")
            } else {
                if (next) player.enqueueNext(song.toMediaItem()) else player.enqueueEnd(song.toMediaItem())
                snackbar.showSnackbar(if (next) "已设为下一首播放" else "已加入播放队列")
            }
        }
    }

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onPlay).heightIn(min = 80.dp)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (trackNumber != null) {
            Text(
                trackNumber.toString(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp),
            )
        }
        KugouCover(song.getThumbnailModel(), Modifier.size(52.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    song.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f),
                )
                if (liked) {
                    Icon(Icons.Rounded.Favorite, "已保存在本地喜欢", tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp).size(14.dp))
                }
            }
            Text(song.artists.joinToString("、") { it.name }.ifBlank { "未知歌手" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { expanded = true }, enabled = !saving, modifier = Modifier.size(48.dp)) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Rounded.MoreVert, "${song.title}的操作")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("下一首播放") }, leadingIcon = { Icon(Icons.Rounded.SkipNext, null) }, onClick = { expanded = false; enqueue(true) })
                DropdownMenuItem(text = { Text("加入播放队列") }, leadingIcon = { Icon(Icons.Rounded.QueueMusic, null) }, onClick = { expanded = false; enqueue(false) })
                DropdownMenuItem(text = { Text(if (liked) "取消本地喜欢" else "喜欢（保存在本地）") },
                    leadingIcon = { Icon(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) }, onClick = {
                    expanded = false
                    saveAndAct {
                        withContext(Dispatchers.IO) {
                            val current = database.song(song.id).first()
                            current?.song?.let { database.update(it.toggleLike()) }
                        }
                    }
                })
                DropdownMenuItem(text = { Text("加入本地歌单") }, leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, null) }, onClick = { expanded = false; saveAndAct { showPlaylist = true } })
            }
        }
    }
    if (showPlaylist) AddToPlaylistDialog(navController = navController, songIds = listOf(song.id), onDismiss = { showPlaylist = false })
}
