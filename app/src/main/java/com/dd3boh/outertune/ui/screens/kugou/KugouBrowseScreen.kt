package com.dd3boh.outertune.ui.screens.kugou

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = kugouContentPadding()) {
            item("refresh") { TextButton(onClick = viewModel::refresh, enabled = !state.loading, modifier = Modifier.padding(horizontal = 8.dp)) { Text("刷新列表") } }
            items(state.collections, key = { it.id }) { collection -> KugouCollectionRow(collection, if (ranks) "rank" else "playlist", navController, preview = ranks) }
            if (state.page > 0 && state.collections.isEmpty() && !state.loading) item("empty") { KugouMessage("暂无${if (ranks) "榜单" else "歌单"}，请稍后刷新。") }
            item("footer") { KugouPageFooter(state.loading, state.hasMore, state.error, viewModel::loadMore) }
            if (state.loading) item("cancel") { TextButton(onClick = viewModel::cancelLoading, modifier = Modifier.padding(horizontal = 8.dp)) { Text("取消加载") } }
        }
    }
}
