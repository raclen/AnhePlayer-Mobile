package com.dd3boh.outertune.ui.screens.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.constants.ListThumbnailSize
import com.dd3boh.outertune.constants.ThumbnailCornerRadius
import com.dd3boh.outertune.extensions.toMediaItem
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.ui.component.SwipeToQueueBox
import com.dd3boh.outertune.ui.component.items.ItemThumbnail
import com.dd3boh.outertune.ui.component.items.ListItem
import com.dd3boh.outertune.utils.joinByBullet
import com.dd3boh.outertune.utils.makeTimeString

@Composable
internal fun OnlineSongRow(
    item: MediaMetadata,
    activeId: String?,
    isPlaying: Boolean,
    swipeEnabled: Boolean,
    snackbarHostState: SnackbarHostState,
    onPlay: () -> Unit,
) {
    SwipeToQueueBox(item = item.toMediaItem(), swipeEnabled = swipeEnabled, snackbarHostState = snackbarHostState) {
        ListItem(
            title = item.title,
            subtitle = joinByBullet(
                item.artists.joinToString { it.name },
                item.duration.takeIf { it >= 0 }?.let { makeTimeString(it * 1000L) },
            ),
            thumbnailContent = {
                ItemThumbnail(
                    thumbnailUrl = item.thumbnailUrl,
                    isActive = activeId == item.id, isPlaying = isPlaying,
                    shape = RoundedCornerShape(ThumbnailCornerRadius),
                    modifier = Modifier.size(ListThumbnailSize),
                )
            },
            isActive = activeId == item.id,
            modifier = Modifier.clickable(onClick = onPlay),
        )
    }
}

@Composable
internal fun SearchPageFooter(loading: Boolean, hasMore: Boolean, error: String?, onMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("正在加载…", style = MaterialTheme.typography.bodySmall)
        } else if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onMore) { Text("重试") }
        } else if (hasMore) {
            TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text("加载更多") }
        }
    }
}
