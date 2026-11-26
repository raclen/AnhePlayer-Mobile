package com.dd3boh.outertune.ui.screens.online

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.source.lx.LxPlaylistInfo
import com.dd3boh.outertune.source.lx.LxRecommendTag
import com.dd3boh.outertune.ui.utils.onlinePlaylistRoute
import com.dd3boh.outertune.viewmodels.DEFAULT_RECOMMEND_TAG
import com.dd3boh.outertune.viewmodels.RecommendSheetsViewModel

/**
 * 在线热门歌单推荐页面（免登录，支持酷狗 / 网易云 / QQ 音乐及分类标签，对齐 PC 端）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecommendSheetsScreen(
    navController: NavController,
    viewModel: RecommendSheetsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val gridState = rememberLazyGridState()
    var showTagSheet by remember { mutableStateOf(false) }

    // 滚动接近底部时自动加载下一页
    val shouldLoadMore by remember {
        derivedStateOf {
            val totalItems = gridState.layoutInfo.totalItemsCount
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisibleItem >= totalItems - 4
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !state.loading && state.hasMore) {
            viewModel.loadMore()
        }
    }

    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val contentPadding = PaddingValues(
        start = insets.calculateStartPadding(direction) + 16.dp,
        end = insets.calculateEndPadding(direction) + 16.dp,
        top = insets.calculateTopPadding() + 8.dp,
        bottom = insets.calculateBottomPadding() + 24.dp,
    )

    val pinnedTags = remember(state.tags, state.selectedTag) {
        val base = listOf(DEFAULT_RECOMMEND_TAG) + state.tags?.pinned.orEmpty()
        val withSelected = if (base.any { it == state.selectedTag }) base else base + state.selectedTag
        withSelected.distinctBy { "${it.id}_${it.name}" }
    }

    if (showTagSheet && state.tags != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showTagSheet = false },
            sheetState = sheetState,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item("sheet_title") {
                    Text(
                        text = "${state.source.displayName}歌单分类",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
                item("all_tag") {
                    FilterChip(
                        selected = state.selectedTag == DEFAULT_RECOMMEND_TAG,
                        onClick = {
                            viewModel.selectTag(DEFAULT_RECOMMEND_TAG)
                            showTagSheet = false
                        },
                        label = { Text(DEFAULT_RECOMMEND_TAG.name) },
                    )
                }
                items(state.tags?.groups.orEmpty(), key = { it.title }) { group ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            group.tags.forEach { tag ->
                                FilterChip(
                                    selected = state.selectedTag == tag,
                                    onClick = {
                                        viewModel.selectTag(tag)
                                        showTagSheet = false
                                    },
                                    label = { Text(tag.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val columnCount = if (maxWidth >= 720.dp) 4 else if (maxWidth >= 480.dp) 3 else 2

        LazyVerticalGrid(
            columns = GridCells.Fixed(columnCount),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item("platforms", span = { GridItemSpan(columnCount) }) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(viewModel.sources, key = { it.sourceId }) { source ->
                        FilterChip(
                            selected = state.source == source,
                            onClick = { viewModel.selectSource(source) },
                            label = { Text(source.displayName) },
                        )
                    }
                }
            }

            item("tags", span = { GridItemSpan(columnCount) }) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(pinnedTags, key = { "${it.id}_${it.name}" }) { tag: LxRecommendTag ->
                        FilterChip(
                            selected = state.selectedTag == tag,
                            onClick = { viewModel.selectTag(tag) },
                            label = { Text(tag.name) },
                        )
                    }
                    if (!state.tags?.groups.isNullOrEmpty()) {
                        item("more_tags") {
                            FilterChip(
                                selected = false,
                                onClick = { showTagSheet = true },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Category,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                label = { Text("全部分类") },
                            )
                        }
                    }
                }
            }

            item("header", span = { GridItemSpan(columnCount) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Rounded.Whatshot,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "${state.source.displayName}热门歌单 · ${state.selectedTag.name}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = viewModel::refresh,
                        enabled = !state.loading,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("刷新")
                    }
                }
            }

            when {
                state.loading && state.playlists.isEmpty() -> {
                    item("loading", span = { GridItemSpan(columnCount) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(36.dp))
                                Text("正在发现精选热门歌单…", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                state.error != null && state.playlists.isEmpty() -> {
                    item("error", span = { GridItemSpan(columnCount) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.padding(16.dp),
                            ) {
                                Icon(
                                    Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(48.dp),
                                )
                                Text(
                                    text = state.error ?: "加载失败",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = viewModel::refresh) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("重试")
                                }
                            }
                        }
                    }
                }
                state.playlists.isEmpty() -> {
                    item("empty", span = { GridItemSpan(columnCount) }) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "当前分类暂无歌单，可切换其他分类或平台",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                else -> {
                    items(state.playlists, key = { "${it.source.sourceId}_${it.id}" }) { item ->
                        RecommendSheetTile(
                            playlist = item,
                            onClick = { navController.navigate(onlinePlaylistRoute(item)) },
                        )
                    }

                    if (state.loading) {
                        item("loading_more", span = { GridItemSpan(columnCount) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            }
                        }
                    } else if (state.error != null) {
                        item("error_more", span = { GridItemSpan(columnCount) }) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = state.error ?: "加载更多失败",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = viewModel::loadMore) {
                                    Text("重试")
                                }
                            }
                        }
                    } else if (state.hasMore) {
                        item("load_more", span = { GridItemSpan(columnCount) }) {
                            TextButton(
                                onClick = viewModel::loadMore,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp),
                            ) {
                                Text("加载更多")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecommendSheetTile(
    playlist: LxPlaylistInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (!playlist.thumbnailUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = playlist.thumbnailUrl,
                        contentDescription = playlist.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (playlist.songCount != null && playlist.songCount > 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .background(
                                color = Color.Black.copy(alpha = 0.55f),
                                shape = RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Headphones,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(11.dp),
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = "${playlist.songCount} 首",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (playlist.creator.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = playlist.creator,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
