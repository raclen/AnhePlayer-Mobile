package com.dd3boh.outertune.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.radio.RadioPlaybackState
import com.dd3boh.outertune.radio.RadioPlayerManager
import com.dd3boh.outertune.radio.RadioStation
import com.dd3boh.outertune.radio.RadioViewModel
import com.dd3boh.outertune.ui.component.button.IconButton
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: RadioViewModel = hiltViewModel(),
) {
    val stations by viewModel.stations.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val currentStation by RadioPlayerManager.currentStation.collectAsState()
    val playbackState by RadioPlayerManager.playbackState.collectAsState()
    val errorMessage by RadioPlayerManager.errorMessage.collectAsState()

    val haptic = LocalHapticFeedback.current
    val coroutineScope = rememberCoroutineScope()
    var showStationSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val activeStation: RadioStation? = stations.getOrNull(currentIndex) ?: currentStation
    val playerConnection = LocalPlayerConnection.current

    LaunchedEffect(Unit) {
        playerConnection?.softKillPlayer()
        if (stations.isEmpty()) {
            viewModel.refreshStations()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(LocalPlayerAwareWindowInsets.current.asPaddingValues())
    ) {
        RadioContent(
            station = activeStation,
            playbackState = playbackState,
            currentIndex = currentIndex,
            totalCount = stations.size,
            isFavorite = activeStation?.let { favorites.contains(it.stationUuid) } ?: false,
            isLoading = isLoading,
            errorMessage = errorMessage,
            onRefresh = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.refreshStations(shuffleOrder = true)
            },
            onOpenSettings = {
                navController.navigate("settings/radio")
            },
            onStepChange = { offset ->
                val newIndex = (currentIndex + offset).coerceIn(0, (stations.size - 1).coerceAtLeast(0))
                if (newIndex != currentIndex && newIndex in stations.indices) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (offset > 0) {
                        viewModel.nextStation()
                    } else {
                        viewModel.previousStation()
                    }
                }
            },
            onToggleFavorite = {
                activeStation?.let {
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                    viewModel.toggleFavorite(it)
                }
            },
            onPrevious = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.previousStation()
            },
            onNext = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.nextStation()
            },
            onTogglePlay = {
                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                if (currentStation == null && activeStation != null) {
                    RadioPlayerManager.play(activeStation)
                } else {
                    RadioPlayerManager.togglePlay()
                }
            },
            onShuffle = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                viewModel.shuffleStation()
            },
            onOpenList = {
                showStationSheet = true
            },
            onRetry = {
                if (activeStation != null) {
                    viewModel.retryCurrentStation()
                } else {
                    viewModel.refreshStations()
                }
            }
        )

        // 电台列表底部抽屉
        if (showStationSheet) {
            ModalBottomSheet(
                onDismissRequest = { showStationSheet = false },
                sheetState = sheetState,
            ) {
                RadioStationListSheet(
                    stations = stations,
                    currentIndex = currentIndex,
                    currentStation = currentStation,
                    playbackState = playbackState,
                    onSelect = { index ->
                        viewModel.selectStation(index, autoPlay = true)
                        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                            showStationSheet = false
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun RadioContent(
    station: RadioStation?,
    playbackState: RadioPlaybackState,
    currentIndex: Int,
    totalCount: Int,
    isFavorite: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onStepChange: (Int) -> Unit,
    onToggleFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    onShuffle: () -> Unit,
    onOpenList: () -> Unit,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        // 顶部快捷功能栏（状态标签 + 快捷操作）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            RadioStatusBadge(
                playbackState = playbackState,
                errorMessage = errorMessage,
                currentIndex = currentIndex,
                totalCount = totalCount,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 随机漫游，放在顶部避免挤占底部主控制栏
                IconButton(onClick = onShuffle) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "随机一站",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "刷新电台",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Rounded.Settings,
                        contentDescription = "电台设置",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 中心核心：电台 Hero 大卡片（参考 roamdial.com 极简风格）
        RadioHeroCard(
            station = station,
            playbackState = playbackState,
            isLoading = isLoading,
            errorMessage = errorMessage,
            onRetry = onRetry,
            modifier = Modifier.weight(1f)
        )

        // 调频旋钮/标尺刻度（Tuning Dial）
        RadioTuningDial(
            currentIndex = currentIndex,
            totalCount = totalCount,
            onStepChange = onStepChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
        )

        // 底部核心控制栏（收藏、上一台、大播放键、下一台、列表抽屉）
        RadioControlsRow(
            isFavorite = isFavorite,
            playbackState = playbackState,
            isLoading = isLoading,
            onToggleFavorite = onToggleFavorite,
            onPrevious = onPrevious,
            onNext = onNext,
            onTogglePlay = onTogglePlay,
            onOpenList = onOpenList
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RadioStatusBadge(
    playbackState: RadioPlaybackState,
    errorMessage: String?,
    currentIndex: Int,
    totalCount: Int,
) {
    val (statusColor, statusText) = when (playbackState) {
        RadioPlaybackState.PLAYING -> Color(0xFF4CAF50) to "直播中"
        RadioPlaybackState.BUFFERING -> Color(0xFFFFB300) to "连接中"
        RadioPlaybackState.ERROR -> MaterialTheme.colorScheme.error to "连接失败"
        RadioPlaybackState.PAUSED -> MaterialTheme.colorScheme.outline to "已暂停"
        RadioPlaybackState.IDLE -> MaterialTheme.colorScheme.outline to "就绪"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (playbackState == RadioPlaybackState.BUFFERING || playbackState == RadioPlaybackState.PLAYING) {
                        statusColor.copy(alpha = alpha)
                    } else {
                        statusColor
                    }
                )
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 避免长错误文案把顶部徽章撑成多行方块
            modifier = Modifier.widthIn(max = 96.dp)
        )

        if (totalCount > 0) {
            Text(
                text = " · ${currentIndex + 1}/$totalCount",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun RadioHeroCard(
    station: RadioStation?,
    playbackState: RadioPlaybackState,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
    ) {
        // 电台 Logo / 图标
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(110.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(28.dp)
                )
        ) {
            if (!station?.favicon.isNullOrBlank()) {
                AsyncImage(
                    model = station.favicon,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Radio,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(52.dp)
                )
            }

            if (playbackState == RadioPlaybackState.BUFFERING) {
                CircularProgressIndicator(
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        if (isLoading && station == null) {
            Text(
                text = "正在载入电台…",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (station != null) {
            // 大电台名称
            Text(
                text = station.displayTitle,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(8.dp))

            // 国家与语言副标
            Text(
                text = station.displaySubtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(12.dp))

            // 码率与格式药丸 Badge
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BadgeChip(text = station.displayCodecAndBitrate)

                val mainTag = station.tagList.firstOrNull()
                if (!mainTag.isNullOrBlank()) {
                    BadgeChip(text = mainTag)
                }
            }

            if (playbackState == RadioPlaybackState.ERROR) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = errorMessage ?: "播放失败，轻触重试",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clickable { onRetry() }
                )
                Text(
                    text = "轻触重试，或点右侧「下一台」继续换台",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, start = 16.dp, end = 16.dp)
                )
            }
        } else {
            Text(
                text = "暂无符合筛选条件的电台",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "可尝试在设置中放宽码率或地区限制",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun BadgeChip(text: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

/**
 * 极简复古调频标尺（Tuning Dial）
 * 模拟传统调谐器刻度：中央高亮指针对齐当前电台，横向拖动可快速切台
 */
@Composable
private fun RadioTuningDial(
    currentIndex: Int,
    totalCount: Int,
    onStepChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var accumulatedDrag by remember { mutableFloatStateOf(0f) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val tickMajorColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        accumulatedDrag += delta
                        val threshold = 36f // 滑动阈值
                        if (accumulatedDrag > threshold) {
                            onStepChange(-1) // 向右滑切上一台
                            accumulatedDrag = 0f
                        } else if (accumulatedDrag < -threshold) {
                            onStepChange(1) // 向左滑切下一台
                            accumulatedDrag = 0f
                        }
                    }
                )
        ) {
            // 背景刻度标尺
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val midX = width / 2f
                val spacing = 16f
                val count = (width / spacing).toInt()

                for (i in -count / 2..count / 2) {
                    val x = midX + i * spacing
                    if (x in 0f..width) {
                        val isMajor = i % 5 == 0
                        val tickHeight = if (isMajor) height * 0.55f else height * 0.28f
                        val startY = (height - tickHeight) / 2f
                        drawLine(
                            color = if (isMajor) tickMajorColor else tickColor,
                            start = Offset(x, startY),
                            end = Offset(x, startY + tickHeight),
                            strokeWidth = if (isMajor) 2f else 1.2f
                        )
                    }
                }

                // 中央调谐高亮指针
                drawLine(
                    color = primaryColor,
                    start = Offset(midX, 2f),
                    end = Offset(midX, height - 2f),
                    strokeWidth = 3f
                )
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = "左右拨动微调电台",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * 底部操作栏：收藏、上一台、播放/暂停、下一台、电台抽屉
 */
@Composable
private fun RadioControlsRow(
    isFavorite: Boolean,
    playbackState: RadioPlaybackState,
    isLoading: Boolean,
    onToggleFavorite: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenList: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth()
    ) {
        // 收藏
        IconButton(onClick = onToggleFavorite) {
            Icon(
                imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = if (isFavorite) "取消收藏" else "收藏电台",
                tint = if (isFavorite) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }

        // 上一台
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = "上一台",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp)
            )
        }

        // 核心主播放键（大圆按钮）
        FilledIconButton(
            onClick = onTogglePlay,
            modifier = Modifier.size(68.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            val isPlaying = playbackState == RadioPlaybackState.PLAYING
            val isBuffering = playbackState == RadioPlaybackState.BUFFERING

            if (isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(38.dp)
                )
            }
        }

        // 下一台（跳下一曲）
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = "下一台",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp)
            )
        }

        // 展开电台列表抽屉
        IconButton(onClick = onOpenList) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.FormatListBulleted,
                contentDescription = "电台列表",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 底部电台列表抽屉
 */
@Composable
private fun RadioStationListSheet(
    stations: List<RadioStation>,
    currentIndex: Int,
    currentStation: RadioStation?,
    playbackState: RadioPlaybackState,
    onSelect: (Int) -> Unit,
) {
    var searchFilter by remember { mutableStateOf("") }
    val filteredStations = remember(stations, searchFilter) {
        if (searchFilter.isBlank()) {
            stations
        } else {
            stations.filter {
                it.name.contains(searchFilter, ignoreCase = true) ||
                (it.country ?: "").contains(searchFilter, ignoreCase = true) ||
                (it.language ?: "").contains(searchFilter, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(480.dp)
            .padding(horizontal = 16.dp)
    ) {
        Text(
            text = "电台列表 (${stations.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        OutlinedTextField(
            value = searchFilter,
            onValueChange = { searchFilter = it },
            placeholder = { Text("搜索当前电台、国家或语言…") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = filteredStations,
                key = { _, item -> item.stationUuid }
            ) { _, station ->
                val isSelected = station.stationUuid == currentStation?.stationUuid
                val originalIndex = stations.indexOfFirst { it.stationUuid == station.stationUuid }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else Color.Transparent
                        )
                        .clickable {
                            if (originalIndex >= 0) {
                                onSelect(originalIndex)
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        if (!station.favicon.isNullOrBlank()) {
                            AsyncImage(
                                model = station.favicon,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Radio,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = station.displayTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = listOfNotNull(
                                station.displaySubtitle,
                                station.displayCodecAndBitrate
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (isSelected && playbackState == RadioPlaybackState.PLAYING) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = "播放中",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "Radio Playing Dark", showBackground = true, widthDp = 380, heightDp = 760)
@Composable
private fun RadioContentPreview() {
    MaterialTheme {
        Surface {
            RadioContent(
                station = RadioStation(
                    stationUuid = "demo-hk-r1",
                    name = "香港电台第一台 RTHK Radio 1",
                    url = "https://rthkaudio1-lh.akamaihd.net/i/radio1_1@355864/master.m3u8",
                    codec = "MP3",
                    bitrate = 128,
                    country = "China",
                    countryCode = "HK",
                    language = "chinese,cantonese",
                    tags = "news,talk,public,cantonese",
                ),
                playbackState = RadioPlaybackState.PLAYING,
                currentIndex = 2,
                totalCount = 45,
                isFavorite = true,
                isLoading = false,
                errorMessage = null,
                onRefresh = {},
                onOpenSettings = {},
                onStepChange = {},
                onToggleFavorite = {},
                onPrevious = {},
                onNext = {},
                onTogglePlay = {},
                onShuffle = {},
                onOpenList = {},
            )
        }
    }
}
