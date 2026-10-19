package com.dd3boh.outertune.ui.player

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.media3.common.C
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.Player.STATE_ENDED
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DEFAULT_PLAYER_BACKGROUND
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.PlayerBackgroundStyle
import com.dd3boh.outertune.constants.PlayerBackgroundStyleKey
import com.dd3boh.outertune.constants.PlayerHorizontalPadding
import com.dd3boh.outertune.constants.SeekIncrement
import com.dd3boh.outertune.constants.SeekIncrementKey
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.extensions.togglePlayPause
import com.dd3boh.outertune.lyrics.LyricsSource
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.playback.QueueBoard
import com.dd3boh.outertune.ui.component.BottomSheetState
import com.dd3boh.outertune.ui.component.LyricsDisplay
import com.dd3boh.outertune.ui.component.LyricsPreview
import com.dd3boh.outertune.ui.component.LyricsSourceBadge
import com.dd3boh.outertune.ui.component.LyricsStyle
import com.dd3boh.outertune.ui.component.LyricsView
import com.dd3boh.outertune.ui.component.PlayerSliderTrack
import com.dd3boh.outertune.ui.component.dismissedAnchor
import com.dd3boh.outertune.ui.component.rememberBottomSheetState
import com.dd3boh.outertune.ui.component.rememberPlayerPosition
import com.dd3boh.outertune.ui.component.showLyricsMenu
import com.dd3boh.outertune.ui.component.toLyricsDisplay
import com.dd3boh.outertune.ui.menu.PlayerMenu
import com.dd3boh.outertune.ui.menu.SleepTimerDialog
import com.dd3boh.outertune.ui.utils.fadingEdge
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private const val PAGE_COVER = 0
private const val PAGE_LYRICS = 1

// 竖屏播放页：顶部标题、可左滑切换的封面/歌词页、底部进度与控制栏
@Composable
fun PortraitPlayer(
    playerSheetState: BottomSheetState,
    navController: NavController,
    queueBoard: QueueBoard,
    enableQueueSheet: Boolean = true,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()

    val playerBackground by rememberEnumPreference(PlayerBackgroundStyleKey, DEFAULT_PLAYER_BACKGROUND)
    val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
    val isSystemInDarkTheme = isSystemInDarkTheme()
    val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
        if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
    }
    // 除「跟随主题」外，背景固定为封面取色的深色沉浸式背景，文字统一用白色
    val immersive = playerBackground != PlayerBackgroundStyle.FOLLOW_THEME
    val contentColor = if (immersive) Color.White else MaterialTheme.colorScheme.onSurface

    val playerLyrics by playerConnection.currentLyrics.collectAsState()
    val lyricsDisplay = remember(playerLyrics) { playerLyrics.toLyricsDisplay() }

    // 拖动进度条时，歌词预览跟随拖动位置
    val playerPosition = rememberPlayerPosition(playerConnection)
    val dragPosition = remember { mutableStateOf<Long?>(null) }
    val positionProvider: () -> Long = remember { { dragPosition.value ?: playerPosition.longValue } }
    val seekTo: (Long) -> Unit = remember {
        { target ->
            playerConnection.player.seekTo(target)
            playerPosition.longValue = target
        }
    }

    // 播放队列默认隐藏，由工具栏按钮展开
    val dismissedBound = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()
    val queueSheetState = rememberBottomSheetState(
        dismissedBound = dismissedBound,
        expandedBound = playerSheetState.expandedBound,
        collapsedBound = dismissedBound,
        initialAnchor = dismissedAnchor,
    )

    // 左滑切换到歌词页；页码与 ShowLyricsKey 双向同步，保证播放菜单中的「切换歌词」仍然有效
    var showLyrics by rememberPreference(ShowLyricsKey, defaultValue = false)
    val pagerState = rememberPagerState(initialPage = if (showLyrics) PAGE_LYRICS else PAGE_COVER) { 2 }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            val lyricsPage = page == PAGE_LYRICS
            if (showLyrics != lyricsPage) showLyrics = lyricsPage
        }
    }
    LaunchedEffect(showLyrics) {
        val target = if (showLyrics) PAGE_LYRICS else PAGE_COVER
        if (!pagerState.isScrollInProgress && pagerState.settledPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    DisposableEffect(showLyrics) {
        view.keepScreenOn = showLyrics
        onDispose {
            view.keepScreenOn = false
        }
    }

    // 深色沉浸式背景上状态栏、导航栏图标需为浅色；展开播放队列（浅色背景）或收起播放器时恢复主题设置
    val activity = LocalActivity.current
    val currentUseDarkTheme by rememberUpdatedState(useDarkTheme)
    val lightSystemBarIcons by remember(immersive, useDarkTheme) {
        derivedStateOf {
            immersive && !useDarkTheme && playerSheetState.progress > 0.5f && queueSheetState.progress < 0.5f
        }
    }
    DisposableEffect(lightSystemBarIcons) {
        val applied = lightSystemBarIcons
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (applied && controller != null) {
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
        onDispose {
            if (applied && controller != null) {
                controller.isAppearanceLightStatusBars = !currentUseDarkTheme
                controller.isAppearanceLightNavigationBars = !currentUseDarkTheme
            }
        }
    }

    val sheetNestedScroll = remember(playerSheetState) { playerSheetState.preUpPostDownNestedScrollConnection }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(bottom = dismissedBound + 10.dp)
        ) {
            PlayerHeader(
                playerSheetState = playerSheetState,
                navController = navController,
                modifier = Modifier.padding(top = 28.dp)
            )

            HorizontalPager(
                state = pagerState,
                userScrollEnabled = playerSheetState.isExpanded,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(sheetNestedScroll)
            ) { page ->
                when (page) {
                    PAGE_COVER -> CoverPage(
                        display = lyricsDisplay,
                        positionProvider = positionProvider,
                        onShowLyrics = {
                            coroutineScope.launch { pagerState.animateScrollToPage(PAGE_LYRICS) }
                        },
                    )

                    else -> LyricsPage(
                        display = lyricsDisplay,
                        source = playerLyrics?.source,
                        positionProvider = positionProvider,
                        onSeek = seekTo,
                    )
                }
            }

            PlayerProgress(
                positionProvider = positionProvider,
                dragPosition = dragPosition,
                onSeek = seekTo,
            )
            PlayerTransport(queueBoard = queueBoard, onSeek = seekTo)
            Spacer(Modifier.height(15.dp))
            PlayerToolbar(
                playerSheetState = playerSheetState,
                queueSheetState = queueSheetState,
                navController = navController,
            )
        }
    }

    if (enableQueueSheet) {
        QueueSheet(
            state = queueSheetState,
            playerBottomSheetState = playerSheetState,
            onTerminate = {
                playerSheetState.dismiss()
                queueBoard.detachedHead = false
            },
            navController = navController
        )
    }
}

@Composable
private fun PlayerHeader(
    playerSheetState: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val contentColor = LocalContentColor.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PlayerHorizontalPadding, end = 16.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mediaMetadata?.title.orEmpty(),
                color = contentColor,
                fontSize = 22.sp,
                lineHeight = 29.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .basicMarquee(iterations = 1, initialDelayMillis = 3000)
                    .clickable(enabled = mediaMetadata?.album != null) {
                        mediaMetadata?.album?.id?.let { navController.navigate("album/$it") }
                        playerSheetState.collapseSoft()
                    }
            )
            Row(modifier = Modifier.basicMarquee(iterations = 1, initialDelayMillis = 5000)) {
                mediaMetadata?.artists?.forEachIndexed { index, artist ->
                    Text(
                        text = artist.name,
                        color = contentColor.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        maxLines = 1,
                        modifier = Modifier.clickable(enabled = artist.id != null) {
                            navController.navigate("artist/${artist.id}")
                            playerSheetState.collapseSoft()
                        }
                    )
                    if (index != mediaMetadata?.artists?.lastIndex) {
                        Text(text = ", ", color = contentColor.copy(alpha = 0.6f), fontSize = 14.sp, lineHeight = 19.sp)
                    }
                }
            }
        }

        PlayerIconButton(
            painter = painterResource(if (currentSong?.song?.liked == true) R.drawable.favorite else R.drawable.favorite_border),
            contentDescription = stringResource(R.string.action_like),
            onClick = playerConnection::toggleLike,
            size = 48.dp,
            iconSize = 26.dp,
        )
    }
}

@Composable
private fun CoverPage(
    display: LyricsDisplay,
    positionProvider: () -> Long,
    onShowLyrics: () -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val contentColor = LocalContentColor.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val error by playerConnection.error.collectAsState()

    // 三行歌词预览按字体缩放计算高度，保证封面尺寸计算准确
    val previewHeight = with(density) { (23.sp * 3).toDp() }
    val coverGap = 33.dp

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = PlayerHorizontalPadding)
    ) {
        val coverSize = minOf(maxWidth, maxHeight - previewHeight - coverGap).coerceAtLeast(0.dp)
        // 封面与预览整体略偏下，贴近参考设计中上方留白多于下方的比例
        Column(modifier = Modifier.align(BiasAlignment(0f, 0.34f))) {
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .clip(RoundedCornerShape(8.dp))
                    .background(contentColor.copy(alpha = 0.08f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        onShowLyrics()
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
            ) {
                val playbackError = error
                if (playbackError != null) {
                    ThumbnailPlaybackError(error = playbackError, retry = playerConnection.player::prepare)
                } else {
                    AsyncImage(
                        model = mediaMetadata?.getThumbnailModel(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.height(coverGap))
            LyricsPreview(
                display = display,
                positionProvider = positionProvider,
                activeColor = contentColor,
                inactiveColor = contentColor.copy(alpha = 0.45f),
                modifier = Modifier
                    .width(coverSize)
                    .height(previewHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onShowLyrics,
                    )
            )
        }
    }
}

@Composable
private fun LyricsPage(
    display: LyricsDisplay,
    source: LyricsSource?,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val contentColor = LocalContentColor.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()

    val style = remember(contentColor) {
        LyricsStyle(
            activeColor = contentColor,
            inactiveColor = contentColor.copy(alpha = 0.4f),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        LyricsView(
            display = display,
            positionProvider = positionProvider,
            onSeek = {
                onSeek(it)
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            },
            style = style,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .fadingEdge(vertical = 56.dp)
        )
        val metadata = mediaMetadata
        if (metadata != null && display != LyricsDisplay.Loading) {
            LyricsSourceBadge(
                source = source,
                color = contentColor.copy(alpha = 0.6f),
                onClick = { menuState.showLyricsMenu(playerConnection, metadata) },
                modifier = Modifier.padding(start = PlayerHorizontalPadding - 4.dp, bottom = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerProgress(
    positionProvider: () -> Long,
    dragPosition: MutableState<Long?>,
    onSeek: (Long) -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val contentColor = LocalContentColor.current
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()

    var duration by remember { mutableLongStateOf(C.TIME_UNSET) }
    LaunchedEffect(playbackState, mediaMetadata) {
        while (isActive) {
            duration = playerConnection.player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                ?: mediaMetadata?.duration?.takeIf { it > 0 }?.let { it * 1000L }
                        ?: C.TIME_UNSET
            delay(1000)
        }
    }

    // 进度值按 200ms、时间文本按秒量化，避免每帧重组
    val sliderValue by remember { derivedStateOf { (positionProvider() / 200L * 200L).toFloat() } }
    val positionSeconds by remember { derivedStateOf { positionProvider() / 1000L } }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
    ) {
        Slider(
            value = sliderValue,
            valueRange = 0f..(if (duration == C.TIME_UNSET) 0f else duration.toFloat()),
            onValueChange = { dragPosition.value = it.toLong() },
            onValueChangeFinished = {
                dragPosition.value?.let(onSeek)
                dragPosition.value = null
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            thumb = { Spacer(modifier = Modifier.size(0.dp)) },
            track = { sliderState ->
                PlayerSliderTrack(
                    sliderState = sliderState,
                    colors = SliderDefaults.colors(
                        activeTrackColor = contentColor,
                        inactiveTrackColor = contentColor.copy(alpha = 0.25f),
                    ),
                    trackHeight = 3.dp,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PlayerHorizontalPadding)
        )
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = PlayerHorizontalPadding)
        ) {
            val timeColor = contentColor.copy(alpha = 0.6f)
            Text(text = formatPlayerTime(positionSeconds * 1000L), color = timeColor, fontSize = 13.sp)
            Text(
                text = if (duration != C.TIME_UNSET) formatPlayerTime(duration) else "",
                color = timeColor,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun PlayerTransport(
    queueBoard: QueueBoard,
    onSeek: (Long) -> Unit,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val haptic = LocalHapticFeedback.current
    val isPlaying by playerConnection.isPlaying.collectAsState()
    val playbackState by playerConnection.playbackState.collectAsState()
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val seekIncrement by rememberEnumPreference(SeekIncrementKey, defaultValue = SeekIncrement.OFF)

    Row(
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (seekIncrement != SeekIncrement.OFF) {
            PlayerIconButton(
                painter = rememberVectorPainter(Icons.Rounded.FastRewind),
                contentDescription = null,
                onClick = {
                    onSeek((playerConnection.player.currentPosition - seekIncrement.millisec).coerceAtLeast(0L))
                },
                size = 48.dp,
                iconSize = 30.dp,
            )
        }
        PlayerIconButton(
            painter = rememberVectorPainter(Icons.Rounded.SkipPrevious),
            contentDescription = stringResource(R.string.player_previous),
            enabled = canSkipPrevious,
            onClick = {
                if (playerConnection.player.currentMediaItem == null) {
                    queueBoard.setCurrQueue()
                }
                playerConnection.player.seekToPrevious()
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            },
            size = 56.dp,
            iconSize = 42.dp,
        )
        PlayerIconButton(
            painter = rememberVectorPainter(
                when {
                    playbackState == STATE_ENDED -> Icons.Rounded.Replay
                    isPlaying -> Icons.Rounded.Pause
                    else -> Icons.Rounded.PlayArrow
                }
            ),
            contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.play),
            onClick = {
                if (playerConnection.player.currentMediaItem == null) {
                    queueBoard.setCurrQueue()
                    playerConnection.player.togglePlayPause()
                } else if (playbackState == STATE_ENDED) {
                    playerConnection.player.seekTo(0, 0)
                    playerConnection.player.playWhenReady = true
                } else {
                    playerConnection.player.togglePlayPause()
                }
                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            },
            size = 72.dp,
            iconSize = 56.dp,
        )
        PlayerIconButton(
            painter = rememberVectorPainter(Icons.Rounded.SkipNext),
            contentDescription = stringResource(R.string.player_next),
            enabled = canSkipNext,
            onClick = {
                playerConnection.player.seekToNext()
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            },
            size = 56.dp,
            iconSize = 42.dp,
        )
        if (seekIncrement != SeekIncrement.OFF) {
            PlayerIconButton(
                painter = rememberVectorPainter(Icons.Rounded.FastForward),
                contentDescription = null,
                onClick = { onSeek(playerConnection.player.currentPosition + seekIncrement.millisec) },
                size = 48.dp,
                iconSize = 30.dp,
            )
        }
    }
}

@Composable
private fun PlayerToolbar(
    playerSheetState: BottomSheetState,
    queueSheetState: BottomSheetState,
    navController: NavController,
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val menuState = LocalMenuState.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val repeatMode by playerConnection.repeatMode.collectAsState()
    val shuffleModeEnabled by playerConnection.shuffleModeEnabled.collectAsState()
    val playMode = PlayMode.from(shuffleModeEnabled, repeatMode)
    val sleepTimer = playerConnection.service.sleepTimer

    var showSleepTimerDialog by remember { mutableStateOf(false) }
    if (showSleepTimerDialog) {
        SleepTimerDialog(onDismiss = { showSleepTimerDialog = false })
    }
    val equalizerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 17.dp)
    ) {
        ToolbarCell {
            PlayerIconButton(
                painter = painterResource(playMode.icon),
                contentDescription = stringResource(playMode.label),
                enabled = mediaMetadata != null,
                onClick = {
                    val next = playMode.next()
                    next.applyTo(playerConnection)
                    Toast.makeText(context, next.label, Toast.LENGTH_SHORT).show()
                    haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                },
            )
        }
        ToolbarCell {
            PlayerIconButton(
                painter = rememberVectorPainter(Icons.Rounded.Timer),
                contentDescription = stringResource(R.string.sleep_timer),
                selected = sleepTimer.isActive,
                onClick = {
                    if (sleepTimer.isActive) {
                        sleepTimer.clear()
                        Toast.makeText(context, R.string.sleep_timer_cancelled, Toast.LENGTH_SHORT).show()
                    } else {
                        showSleepTimerDialog = true
                    }
                },
            )
        }
        ToolbarCell {
            PlayerIconButton(
                painter = rememberVectorPainter(Icons.Rounded.GraphicEq),
                contentDescription = stringResource(R.string.equalizer),
                onClick = {
                    val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                        putExtra(AudioEffect.EXTRA_AUDIO_SESSION, playerConnection.player.audioSessionId)
                        putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                        putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                    }
                    if (intent.resolveActivity(context.packageManager) != null) {
                        equalizerLauncher.launch(intent)
                    } else {
                        Toast.makeText(context, R.string.equalizer_unavailable, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
        ToolbarCell {
            PlayerIconButton(
                painter = rememberVectorPainter(Icons.AutoMirrored.Rounded.FormatListBulleted),
                contentDescription = stringResource(R.string.queue),
                onClick = {
                    queueSheetState.expandSoft()
                    haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                },
            )
        }
        ToolbarCell {
            PlayerIconButton(
                painter = rememberVectorPainter(Icons.Rounded.MoreHoriz),
                contentDescription = stringResource(R.string.more_options),
                onClick = {
                    menuState.show {
                        PlayerMenu(
                            mediaMetadata = mediaMetadata,
                            navController = navController,
                            playerBottomSheetState = playerSheetState,
                            onDismiss = menuState::dismiss
                        )
                    }
                },
            )
        }
    }
}

@Composable
private fun RowScope.ToolbarCell(content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) {
        content()
    }
}

/** 播放模式按钮在四种模式间循环：顺序 → 列表循环 → 单曲循环 → 随机。 */
private enum class PlayMode(@DrawableRes val icon: Int, @StringRes val label: Int) {
    SEQUENTIAL(R.drawable.shuffle_off, R.string.play_mode_sequential),
    REPEAT_ALL(R.drawable.repeat_on, R.string.play_mode_repeat_all),
    REPEAT_ONE(R.drawable.repeat_one, R.string.play_mode_repeat_one),
    SHUFFLE(R.drawable.shuffle_on, R.string.shuffle);

    fun next(): PlayMode = entries[(ordinal + 1) % entries.size]

    fun applyTo(playerConnection: PlayerConnection) {
        val player = playerConnection.player
        val wantShuffle = this == SHUFFLE
        if (player.shuffleModeEnabled != wantShuffle) {
            playerConnection.triggerShuffle()
        }
        player.repeatMode = when (this) {
            SEQUENTIAL -> REPEAT_MODE_OFF
            REPEAT_ONE -> REPEAT_MODE_ONE
            // 随机播放默认循环整个队列
            REPEAT_ALL, SHUFFLE -> REPEAT_MODE_ALL
        }
    }

    companion object {
        fun from(shuffle: Boolean, repeatMode: Int): PlayMode = when {
            shuffle -> SHUFFLE
            repeatMode == REPEAT_MODE_ONE -> REPEAT_ONE
            repeatMode == REPEAT_MODE_ALL -> REPEAT_ALL
            else -> SEQUENTIAL
        }
    }
}

@Composable
private fun PlayerIconButton(
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = 48.dp,
    iconSize: Dp = 26.dp,
) {
    val contentColor = LocalContentColor.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (selected) contentColor.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true),
                onClick = onClick,
            )
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
            modifier = Modifier.size(iconSize)
        )
    }
}

/** 统一使用两位分钟格式，如 00:21、02:58。 */
private fun formatPlayerTime(ms: Long): String {
    if (ms < 0) return ""
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

