package com.dd3boh.outertune.ui.component

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.lyrics.LyricsSource
import com.dd3boh.outertune.lyrics.PlayerLyrics
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.ui.component.shimmer.ShimmerHost
import com.dd3boh.outertune.ui.component.shimmer.TextPlaceholder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.SemanticLyrics.LyricLine
import kotlin.math.abs

/** 一句主歌词及其翻译行，作为滚动与高亮的最小单位。 */
@Immutable
data class LyricGroup(
    val main: LyricLine,
    val translations: List<LyricLine>,
    val startMs: Long,
    /** 逐字高亮的结束时间，已按下一句开始时间截断 */
    val highlightEndMs: Long,
)

@Immutable
sealed interface LyricsDisplay {
    data object Loading : LyricsDisplay
    data object NotFound : LyricsDisplay
    data class Unsynced(val lines: List<String>) : LyricsDisplay
    data class Synced(val groups: List<LyricGroup>) : LyricsDisplay
}

fun PlayerLyrics?.toLyricsDisplay(): LyricsDisplay {
    if (this == null) return LyricsDisplay.Loading
    return when (val model = lyrics) {
        null -> LyricsDisplay.NotFound
        is SemanticLyrics.SyncedLyrics -> buildLyricGroups(model.text)
            .takeIf { it.isNotEmpty() }
            ?.let { LyricsDisplay.Synced(it) }
            ?: LyricsDisplay.NotFound

        else -> model.unsyncedText.map { it.first }
            .takeIf { lines -> lines.any { it.isNotBlank() } }
            ?.let { LyricsDisplay.Unsynced(it) }
            ?: LyricsDisplay.NotFound
    }
}

/**
 * 把解析后的歌词行合并为「主歌词 + 翻译」分组，并丢弃空白行。
 * 空白行通常是间奏标记，不参与显示，但上一句高亮会在下一句开始前保持。
 */
private fun buildLyricGroups(lines: List<LyricLine>): List<LyricGroup> {
    val mains = mutableListOf<Pair<LyricLine, MutableList<LyricLine>>>()
    for (line in lines) {
        if (line.isTranslated && mains.isNotEmpty()) {
            if (line.text.isNotBlank()) mains.last().second.add(line)
        } else {
            mains.add(line to mutableListOf())
        }
    }
    val visible = mains
        .filter { it.first.text.isNotBlank() }
        .sortedBy { it.first.start }
    return visible.mapIndexed { i, (main, translations) ->
        val start = main.start.toLong()
        val nextStart = visible.getOrNull(i + 1)?.first?.start?.toLong() ?: Long.MAX_VALUE
        LyricGroup(
            main = main,
            translations = translations,
            startMs = start,
            highlightEndMs = highlightEnd(main, start, nextStart),
        )
    }
}

private fun highlightEnd(line: LyricLine, start: Long, nextStart: Long): Long {
    line.words?.lastOrNull()?.let { return it.timeRange.last.toLong().coerceAtLeast(start + 1) }
    // 普通 LRC 只有行起始时间，结束时间取下一句开始。若中间夹着长间奏，按字数估算演唱时长，
    // 避免高亮在整个间奏里缓慢爬行。
    val parsedEnd = line.end.toLong().takeIf { it in (start + 1)..<Long.MAX_VALUE } ?: Long.MAX_VALUE
    return minOf(parsedEnd, nextStart, start + estimateLineDuration(line.text)).coerceAtLeast(start + 1)
}

private fun estimateLineDuration(text: String): Long {
    var ms = 0L
    for (c in text) {
        ms += when {
            c.isWhitespace() -> 0L
            // CJK、假名、韩文及全角字符按单字演唱时长估算
            c.code in 0x2E80..0xD7FF || c.code in 0xF900..0xFFEF -> 420L
            else -> 90L
        }
    }
    return ms.coerceIn(1_500L, 12_000L)
}

/** 找到当前播放位置所在的歌词组；尚未到第一句时返回 -1。[groups] 需按开始时间升序。 */
fun findCurrentGroup(groups: List<LyricGroup>, positionMs: Long): Int {
    var lo = 0
    var hi = groups.lastIndex
    var result = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (groups[mid].startMs <= positionMs) {
            result = mid
            lo = mid + 1
        } else {
            hi = mid - 1
        }
    }
    return result
}

/** 当前行已唱到的字符位置，可为小数，用于逐字渐变高亮。 */
private fun sungCharCount(group: LyricGroup, positionMs: Long): Float {
    val textLength = group.main.text.length
    if (positionMs < group.startMs) return 0f
    if (positionMs >= group.highlightEndMs) return textLength.toFloat()
    val words = group.main.words
    if (!words.isNullOrEmpty()) {
        val pos = positionMs.toULong()
        var sung = 0f
        for (word in words) {
            val wordStart = word.timeRange.first
            val wordEnd = word.timeRange.last
            if (pos < wordStart) break
            sung = if (pos >= wordEnd) {
                (word.charRange.last + 1).toFloat()
            } else {
                val progress = (pos - wordStart).toFloat() / (wordEnd - wordStart).coerceAtLeast(1u).toFloat()
                word.charRange.first + (word.charRange.last + 1 - word.charRange.first) * progress
            }
        }
        return sung.coerceIn(0f, textLength.toFloat())
    }
    val progress = (positionMs - group.startMs).toFloat() / (group.highlightEndMs - group.startMs).toFloat()
    return textLength * progress.coerceIn(0f, 1f)
}

@Immutable
data class LyricsStyle(
    val activeColor: Color,
    val inactiveColor: Color,
    val fontSize: TextUnit = 25.sp,
    val lineHeight: TextUnit = 30.sp,
    val fontWeight: FontWeight = FontWeight.SemiBold,
    val translationScale: Float = 0.7f,
    val textAlign: TextAlign = TextAlign.Start,
    val startPadding: Dp = 32.dp,
    val endPadding: Dp = 48.dp,
    val lineSpacing: Dp = 11.dp,
)

/**
 * 卡拉 OK 式逐字高亮：底层绘制暗色文字，上层亮色文字在离屏图层中按演唱进度擦除未唱部分。
 * 进度只在绘制阶段读取，播放时每帧重绘而不触发重组。
 */
@Composable
private fun KaraokeText(
    text: String,
    style: TextStyle,
    baseColor: Color,
    highlightColor: Color,
    showOverlay: Boolean,
    overlayAlpha: () -> Float,
    sungChars: () -> Float,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Text(
            text = text,
            style = style,
            color = baseColor,
            modifier = Modifier.fillMaxWidth(),
        )
        if (showOverlay) {
            var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = text,
                style = style,
                color = highlightColor,
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = overlayAlpha()
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        layout?.let { eraseUnsung(it, sungChars(), style.fontSize.toPx()) }
                    }
            )
        }
    }
}

private fun ContentDrawScope.eraseUnsung(layout: TextLayoutResult, sungChars: Float, fadeWidth: Float) {
    val length = layout.layoutInput.text.length
    if (length == 0) return
    if (sungChars >= length) return
    if (sungChars <= 0f) {
        drawRect(Color.Black, blendMode = BlendMode.DstOut)
        return
    }
    val charIndex = sungChars.toInt().coerceIn(0, length - 1)
    val box = layout.getBoundingBox(charIndex)
    val frontier = box.left + box.width * (sungChars - charIndex)
    val line = layout.getLineForOffset(charIndex)
    val lineTop = layout.getLineTop(line)
    val lineBottom = layout.getLineBottom(line)
    // 当前行：渐变擦除进度点之后的部分
    drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, Color.Black),
            startX = frontier - fadeWidth / 2,
            endX = frontier + fadeWidth / 2,
        ),
        topLeft = Offset(0f, lineTop),
        size = Size(size.width, lineBottom - lineTop),
        blendMode = BlendMode.DstOut,
    )
    // 后续换行全部擦除
    if (lineBottom < size.height) {
        drawRect(
            color = Color.Black,
            topLeft = Offset(0f, lineBottom),
            size = Size(size.width, size.height - lineBottom),
            blendMode = BlendMode.DstOut,
        )
    }
}

/**
 * 歌词视图：同步歌词自动居中当前行并逐字高亮，点击任一行跳转播放；非同步歌词仅平铺展示。
 *
 * @param positionProvider 当前播放位置（毫秒），拖动进度条时应返回拖动位置
 */
@Composable
fun LyricsView(
    display: LyricsDisplay,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    style: LyricsStyle,
    modifier: Modifier = Modifier,
    clickable: Boolean = true,
) {
    Box(modifier = modifier) {
        when (display) {
            LyricsDisplay.Loading -> LyricsPlaceholder(style)
            LyricsDisplay.NotFound -> Text(
                text = stringResource(R.string.lyrics_not_found),
                color = style.inactiveColor,
                fontSize = style.fontSize * 0.8f,
                fontWeight = style.fontWeight,
                textAlign = style.textAlign,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(start = style.startPadding, end = style.endPadding)
            )

            is LyricsDisplay.Unsynced -> UnsyncedLyricsList(display.lines, style)
            is LyricsDisplay.Synced -> SyncedLyricsList(
                groups = display.groups,
                positionProvider = positionProvider,
                onSeek = onSeek,
                style = style,
                clickable = clickable,
            )
        }
    }
}

@Composable
private fun SyncedLyricsList(
    groups: List<LyricGroup>,
    positionProvider: () -> Long,
    onSeek: (Long) -> Unit,
    style: LyricsStyle,
    clickable: Boolean,
) {
    val latestPosition by rememberUpdatedState(positionProvider)
    val position: () -> Long = remember { { latestPosition() } }
    val currentIndex by remember(groups) {
        derivedStateOf { findCurrentGroup(groups, position()) }
    }

    // 以当前行作为初始位置，避免打开歌词页时从顶部滚下来
    val listState = remember(groups) {
        LazyListState(firstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    }

    // 只有真实的手指拖动才暂停自动滚动；程序滚动不会产生 DragInteraction
    var userScrolling by remember(groups) { mutableStateOf(false) }
    LaunchedEffect(listState) {
        var activeDrags = 0
        listState.interactionSource.interactions
            .filterIsInstance<DragInteraction>()
            .collectLatest { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> {
                        activeDrags++
                        userScrolling = true
                    }

                    is DragInteraction.Stop, is DragInteraction.Cancel -> {
                        activeDrags = (activeDrags - 1).coerceAtLeast(0)
                        if (activeDrags == 0) {
                            // 等惯性滑动结束后停留一段时间，再回到当前行
                            snapshotFlow { listState.isScrollInProgress }.first { !it }
                            delay(LyricsUserScrollHoldMillis)
                            userScrolling = false
                        }
                    }

                    else -> Unit
                }
            }
    }

    LaunchedEffect(listState) {
        var animate = false
        snapshotFlow { currentIndex to userScrolling }
            .distinctUntilChanged()
            .collectLatest { (index, scrolling) ->
                if (scrolling) return@collectLatest
                try {
                    listState.centerOn(index.coerceAtLeast(0), animate)
                } catch (e: CancellationException) {
                    // 被更高优先级的用户滚动打断时跳过本次居中；协程本身被取消则继续抛出
                    currentCoroutineContext().ensureActive()
                }
                animate = true
            }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = maxHeight / 2, bottom = maxHeight / 2),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(groups) { index, group ->
                LyricGroupItem(
                    group = group,
                    isCurrent = index == currentIndex,
                    positionProvider = position,
                    style = style,
                    onClick = if (clickable) {
                        {
                            userScrolling = false
                            onSeek(group.startMs)
                        }
                    } else null,
                )
            }
        }
    }
}

/** 将第 [index] 项滚动到视口垂直中心。 */
private suspend fun LazyListState.centerOn(index: Int, animate: Boolean) {
    var item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        // 目标不在可见范围（如跳转进度后），先直接定位，再微调到中心
        scrollToItem(index)
        item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    }
    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2f
    val delta = item.offset + item.size / 2f - viewportCenter
    if (abs(delta) < 1f) return
    if (animate) {
        animateScrollBy(delta, tween(durationMillis = 500))
    } else {
        scrollBy(delta)
    }
}

@Composable
private fun LyricGroupItem(
    group: LyricGroup,
    isCurrent: Boolean,
    positionProvider: () -> Long,
    style: LyricsStyle,
    onClick: (() -> Unit)?,
) {
    val overlayAlpha by animateFloatAsState(
        targetValue = if (isCurrent) 1f else 0f,
        animationSpec = tween(durationMillis = if (isCurrent) 150 else 600),
        label = "lyricHighlight",
    )
    // 当前行或正在淡出的上一行才需要亮色覆盖层
    val showOverlay by remember { derivedStateOf { overlayAlpha > 0.01f } }
    val mainStyle = TextStyle(
        fontSize = style.fontSize,
        lineHeight = style.lineHeight,
        fontWeight = style.fontWeight,
        textAlign = style.textAlign,
    )
    val translationStyle = TextStyle(
        fontSize = style.fontSize * style.translationScale,
        lineHeight = style.lineHeight * style.translationScale,
        fontWeight = FontWeight.Normal,
        textAlign = style.textAlign,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(
                start = style.startPadding,
                end = style.endPadding,
                top = style.lineSpacing,
                bottom = style.lineSpacing,
            )
    ) {
        KaraokeText(
            text = group.main.text,
            style = mainStyle,
            baseColor = style.inactiveColor,
            highlightColor = style.activeColor,
            showOverlay = isCurrent || showOverlay,
            overlayAlpha = { overlayAlpha },
            sungChars = {
                if (isCurrent) sungCharCount(group, positionProvider())
                else group.main.text.length.toFloat()
            },
        )
        group.translations.forEach { translation ->
            Text(
                text = translation.text,
                style = translationStyle,
                color = if (isCurrent) style.activeColor.copy(alpha = 0.85f) else style.inactiveColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun UnsyncedLyricsList(lines: List<String>, style: LyricsStyle) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = 48.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        itemsIndexed(lines) { _, line ->
            Text(
                text = line,
                color = style.activeColor.copy(alpha = 0.85f),
                fontSize = style.fontSize * 0.8f,
                lineHeight = style.lineHeight * 0.8f,
                fontWeight = style.fontWeight,
                textAlign = style.textAlign,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = style.startPadding, end = style.endPadding, top = 6.dp, bottom = 6.dp)
            )
        }
    }
}

@Composable
private fun LyricsPlaceholder(style: LyricsStyle) {
    ShimmerHost(
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .padding(start = style.startPadding, end = style.endPadding)
    ) {
        repeat(8) {
            TextPlaceholder(height = 20.dp, modifier = Modifier.padding(vertical = 6.dp))
        }
    }
}

private data class PreviewState(val display: LyricsDisplay, val index: Int)

/**
 * 封面页下方的三行歌词预览：上一句、当前句、下一句。
 */
@Composable
fun LyricsPreview(
    display: LyricsDisplay,
    positionProvider: () -> Long,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 23.sp,
) {
    val latestPosition by rememberUpdatedState(positionProvider)
    val state by remember(display) {
        derivedStateOf(structuralEqualityPolicy()) {
            val index = if (display is LyricsDisplay.Synced) findCurrentGroup(display.groups, latestPosition()) else 0
            PreviewState(display, index)
        }
    }

    AnimatedContent(
        targetState = state,
        transitionSpec = {
            val forward = targetState.index >= initialState.index
            val enter = slideInVertically { if (forward) it / 3 else -it / 3 } + fadeIn(tween(300))
            val exit = slideOutVertically { if (forward) -it / 3 else it / 3 } + fadeOut(tween(300))
            enter togetherWith exit
        },
        label = "lyricsPreview",
        modifier = modifier,
    ) { preview ->
        val lines: List<Pair<String, Boolean>> = when (val d = preview.display) {
            is LyricsDisplay.Synced -> {
                val i = preview.index
                if (i < 0) {
                    listOf("" to false, d.groups.firstOrNull()?.main?.text.orEmpty() to false, d.groups.getOrNull(1)?.main?.text.orEmpty() to false)
                } else {
                    listOf(
                        d.groups.getOrNull(i - 1)?.main?.text.orEmpty() to false,
                        d.groups.getOrNull(i)?.main?.text.orEmpty() to true,
                        d.groups.getOrNull(i + 1)?.main?.text.orEmpty() to false,
                    )
                }
            }

            is LyricsDisplay.Unsynced -> d.lines.filter { it.isNotBlank() }.take(3).map { it to false }
            LyricsDisplay.NotFound -> listOf("" to false, stringResource(R.string.lyrics_not_found) to false)
            LyricsDisplay.Loading -> emptyList()
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            repeat(3) { row ->
                val (text, highlighted) = lines.getOrNull(row) ?: ("" to false)
                Text(
                    text = text,
                    color = if (highlighted) activeColor else inactiveColor,
                    fontSize = fontSize,
                    lineHeight = lineHeight,
                    fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

/** 歌词页底部的来源标签，点击打开歌词菜单。 */
@Composable
fun LyricsSourceBadge(
    source: LyricsSource?,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(20.dp)
                .border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
        ) {
            Text(text = stringResource(R.string.lyrics_badge), color = color, fontSize = 11.sp, lineHeight = 11.sp)
        }
        source?.let {
            Text(
                text = stringResource(it.labelRes),
                color = color,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private val LyricsSource.labelRes: Int
    get() = when (this) {
        LyricsSource.EMBEDDED -> R.string.lyrics_source_embedded
        LyricsSource.LOCAL -> R.string.lyrics_source_local
        LyricsSource.ONLINE -> R.string.lyrics_source_online
    }

/**
 * 播放位置。播放时每帧读取，保证歌词逐字高亮与滚动跟手；暂停时低频轮询，以响应外部跳转。
 */
@Composable
fun rememberPlayerPosition(playerConnection: PlayerConnection): MutableLongState {
    val position = remember { mutableLongStateOf(playerConnection.player.currentPosition) }
    val isPlaying by playerConnection.isPlaying.collectAsState()
    LaunchedEffect(isPlaying) {
        while (isActive) {
            if (isPlaying) {
                withFrameMillis { }
            } else {
                delay(250)
            }
            position.longValue = playerConnection.player.currentPosition
        }
    }
    return position
}

private const val LyricsUserScrollHoldMillis = 3_000L
