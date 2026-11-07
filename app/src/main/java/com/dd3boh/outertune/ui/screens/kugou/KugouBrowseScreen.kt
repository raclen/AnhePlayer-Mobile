package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.viewmodels.KugouBrowseViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KugouBrowseScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: KugouBrowseViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val ranks = viewModel.kind == "ranks"
    CancelOnKugouPageStop(viewModel::cancelLoading)
    KugouScreenFrame(if (ranks) "全部榜单" else "推荐歌单", navController, scrollBehavior) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 大字体或窄屏退回单列，不把标题与操作挤进固定宽度卡片。
            val columns = if (maxWidth >= 340.dp && LocalDensity.current.fontScale < 1.3f) 2 else 1
            LazyColumn(Modifier.fillMaxSize(), contentPadding = kugouContentPadding()) {
                item("refresh") {
                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (state.collections.isNotEmpty()) "已加载 ${state.collections.size} 个${if (ranks) "榜单" else "歌单"}" else if (ranks) "浏览音乐榜单" else "发现推荐歌单",
                                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("刷新列表")
                            }
                        }
                        Text(
                            if (ranks) "打开榜单查看歌曲与完整排名。" else "按封面挑选歌单，打开查看歌曲。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        if (state.loading) {
                            TextButton(onClick = viewModel::cancelLoading, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消加载") }
                        }
                    }
                }
                if (ranks) {
                    itemsIndexed(state.collections, key = { _, collection -> collection.id }) { index, collection ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        KugouCollectionRow(collection, "rank", navController, preview = true)
                    }
                } else {
                    items(state.collections.chunked(columns), key = { row -> row.first().id }) { row ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top,
                        ) {
                            row.forEach { collection -> KugouCollectionTile(collection, navController, Modifier.weight(1f)) }
                            if (row.size < columns) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                if (state.page > 0 && state.collections.isEmpty() && !state.loading) item("empty") { KugouMessage("暂无${if (ranks) "榜单" else "歌单"}，请稍后刷新。") }
                item("footer") { KugouPageFooter(state.loading, state.hasMore, state.error, viewModel::loadMore, page = state.page) }
            }
        }
    }
}
