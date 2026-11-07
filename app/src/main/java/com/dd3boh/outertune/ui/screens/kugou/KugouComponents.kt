package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
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
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun KugouMessage(message: String, retry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (retry != null) TextButton(onClick = retry) { Text("重试") }
    }
}

@Composable
internal fun KugouPageFooter(loading: Boolean, hasMore: Boolean, error: String?, onMore: () -> Unit) {
    when {
        loading -> Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("正在加载…", style = MaterialTheme.typography.bodyMedium)
        }
        error != null -> KugouMessage(error, onMore)
        hasMore -> TextButton(onClick = onMore, modifier = Modifier.padding(horizontal = 8.dp)) { Text("加载更多") }
    }
}

@Composable
internal fun KugouCover(url: Any?, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
internal fun KugouCollectionRow(collection: KugouCollection, type: String, navController: NavController, preview: Boolean = false) {
    Row(Modifier.fillMaxWidth().clickable { navController.navigate(collectionRoute(type, collection.id)) }
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        KugouCover(collection.coverUrl, Modifier.size(if (preview) 76.dp else 64.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(collection.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (preview && collection.songs.isNotEmpty()) {
                Text(collection.songs.take(3).joinToString("\n") { it.title }, maxLines = 3,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("摘要预览 · 点击查看完整榜单", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (collection.description.isNotBlank()) {
                Text(collection.description, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
internal fun KugouSongRow(song: MediaMetadata, navController: NavController, onPlay: () -> Unit, sourceLabel: String = "酷狗概念版") {
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

    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        KugouCover(song.getThumbnailModel(), Modifier.size(52.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Text(song.artists.joinToString("、") { it.name }.ifBlank { "未知歌手" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sourceLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            IconButton(onClick = { expanded = true }, enabled = !saving) { Icon(Icons.Rounded.MoreVert, "${song.title}的操作") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text("下一首播放") }, onClick = { expanded = false; enqueue(true) })
                DropdownMenuItem(text = { Text("加入播放队列") }, onClick = { expanded = false; enqueue(false) })
                DropdownMenuItem(text = { Text(if (liked) "取消本地喜欢" else "喜欢（保存在本地）") }, onClick = {
                    expanded = false
                    saveAndAct {
                        withContext(Dispatchers.IO) {
                            val current = database.song(song.id).first()
                            current?.song?.let { database.update(it.toggleLike()) }
                        }
                    }
                })
                DropdownMenuItem(text = { Text("加入本地歌单") }, onClick = { expanded = false; saveAndAct { showPlaylist = true } })
            }
        }
    }
    if (showPlaylist) AddToPlaylistDialog(navController = navController, songIds = listOf(song.id), onDismiss = { showPlaylist = false })
}
