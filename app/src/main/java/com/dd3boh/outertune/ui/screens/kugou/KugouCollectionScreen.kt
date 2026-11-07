package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.viewmodels.KugouCollectionViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KugouCollectionScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: KugouCollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val playback = rememberKugouPlayback(navController)
    CancelOnKugouPageStop(viewModel::cancelLoading)
    val title = state.title.ifBlank { "酷狗合集" }
    val playAll = { viewModel.playAll { songs -> playback(songs, 0, title) } }
    KugouScreenFrame(title, navController, scrollBehavior) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = kugouContentPadding()) {
            item("header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        if (maxWidth >= 340.dp && LocalDensity.current.fontScale < 1.3f) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                KugouCover(state.coverUrl, Modifier.size(152.dp), cornerRadius = 16.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                    Text("酷狗概念版合集", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                KugouCover(state.coverUrl, Modifier.widthIn(max = 216.dp).fillMaxWidth().aspectRatio(1f).align(Alignment.CenterHorizontally), cornerRadius = 16.dp)
                                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    if (state.description.isNotBlank()) {
                        Text(state.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = playAll, enabled = !state.loadingAll, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("播放完整合集")
                    }
                    Text(
                        "已加载 ${state.songs.size} 首${if (state.hasMore) "，列表尚未加载完" else ""}。点选单曲播放当前已加载列表。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.loadingAll) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text("正在读取完整合集，完成后开始播放…", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = viewModel::cancelAll, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消完整加载") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                state.allError?.let { KugouMessage(it, playAll, isError = true) }
            }
            itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                KugouSongRow(song, navController, onPlay = {
                    viewModel.cancelAll()
                    playback(state.songs, index, "$title（已加载列表）")
                }, trackNumber = index + 1)
            }
            if (state.page > 0 && state.songs.isEmpty() && !state.loading) item("empty") { KugouMessage("合集暂无歌曲。") }
            item("footer") { KugouPageFooter(state.loading, state.hasMore, state.error, viewModel::loadMore, page = state.page) }
            item("refresh") {
                TextButton(onClick = viewModel::refresh, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) { Text("重新加载合集") }
            }
        }
    }
}
