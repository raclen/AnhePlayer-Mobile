package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    KugouCover(state.coverUrl, Modifier.size(152.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
                    if (state.description.isNotBlank()) Text(state.description, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = playAll, enabled = !state.loadingAll, modifier = Modifier.padding(top = 16.dp)) { Text("播放完整合集") }
                    Text("已加载 ${state.songs.size} 首${if (state.hasMore) "，列表尚未加载完" else ""}。点选单曲播放当前已加载列表。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    if (state.loadingAll) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                        Text("正在读取完整合集，完成后开始播放…", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        TextButton(onClick = viewModel::cancelAll) { Text("取消完整加载") }
                    }
                }
                state.allError?.let { KugouMessage(it, playAll) }
            }
            itemsIndexed(state.songs, key = { _, song -> song.id }) { index, song ->
                KugouSongRow(song, navController, onPlay = {
                    viewModel.cancelAll()
                    playback(state.songs, index, "$title（已加载列表）")
                })
            }
            if (state.page > 0 && state.songs.isEmpty() && !state.loading) item("empty") { KugouMessage("合集暂无歌曲。") }
            item("footer") { KugouPageFooter(state.loading, state.hasMore, state.error, viewModel::loadMore) }
            item("refresh") { TextButton(onClick = viewModel::refresh, modifier = Modifier.padding(horizontal = 8.dp)) { Text("重新加载合集") } }
        }
    }
}
