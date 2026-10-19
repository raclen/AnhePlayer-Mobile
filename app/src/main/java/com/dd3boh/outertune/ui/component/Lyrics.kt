/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.LocalMenuState
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.constants.LyricClickable
import com.dd3boh.outertune.constants.LyricFontSizeKey
import com.dd3boh.outertune.constants.LyricsPosition
import com.dd3boh.outertune.constants.LyricsTextPositionKey
import com.dd3boh.outertune.constants.ShowLyricsKey
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.menu.LyricsMenu
import com.dd3boh.outertune.ui.menu.MenuState
import com.dd3boh.outertune.ui.utils.fadingEdge
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.akanework.gramophone.logic.utils.LrcUtils
import java.io.File

/**
 * 横屏播放器中替换封面的歌词视图。竖屏播放器使用独立歌词页，见 PortraitPlayer。
 */
@Composable
fun Lyrics(
    sliderPositionProvider: () -> Long?,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val menuState = LocalMenuState.current
    var showLyrics by rememberPreference(ShowLyricsKey, false)

    val lyricsTextPosition by rememberEnumPreference(LyricsTextPositionKey, LyricsPosition.CENTER)
    val lyricsFontSize by rememberPreference(LyricFontSizeKey, 20)
    val lyricsClickable by rememberPreference(LyricClickable, true)

    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val playerLyrics by playerConnection.currentLyrics.collectAsState()
    val display = remember(playerLyrics) { playerLyrics.toLyricsDisplay() }

    val playerPosition = rememberPlayerPosition(playerConnection)
    val latestSliderPosition by rememberUpdatedState(sliderPositionProvider)

    val textColor = MaterialTheme.colorScheme.secondary
    val style = remember(textColor, lyricsFontSize, lyricsTextPosition) {
        LyricsStyle(
            activeColor = textColor,
            inactiveColor = textColor.copy(alpha = 0.5f),
            fontSize = lyricsFontSize.sp,
            lineHeight = (lyricsFontSize * 1.25f).sp,
            fontWeight = FontWeight.Bold,
            translationScale = 0.75f,
            textAlign = when (lyricsTextPosition) {
                LyricsPosition.LEFT -> TextAlign.Left
                LyricsPosition.CENTER -> TextAlign.Center
                LyricsPosition.RIGHT -> TextAlign.Right
            },
            startPadding = 24.dp,
            endPadding = 24.dp,
            lineSpacing = 8.dp,
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = 12.dp)
    ) {
        LyricsView(
            display = display,
            positionProvider = { latestSliderPosition() ?: playerPosition.longValue },
            onSeek = {
                playerConnection.player.seekTo(it)
                playerPosition.longValue = it
                haptic.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
            },
            style = style,
            clickable = lyricsClickable,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                .fadingEdge(vertical = 64.dp)
        )

        mediaMetadata?.let { metadata ->
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp)
            ) {
                IconButton(
                    onClick = { showLyrics = false }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = null,
                        tint = textColor
                    )
                }
                IconButton(
                    onClick = { menuState.showLyricsMenu(playerConnection, metadata) }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreHoriz,
                        contentDescription = null,
                        tint = textColor
                    )
                }
            }
        }
    }
}

/**
 * 打开歌词菜单（编辑、刷新、搜索、删除），结果立即应用到当前播放歌词。
 */
fun MenuState.showLyricsMenu(playerConnection: PlayerConnection, mediaMetadata: MediaMetadata) {
    show {
        LyricsMenu(
            lyricsProvider = {
                var dbLyric = runBlocking(Dispatchers.IO) {
                    playerConnection.service.database.lyrics(mediaMetadata.id).first()
                }

                // eye bleach to try to load local file for editor
                if (dbLyric == null && mediaMetadata.localPath != null) {
                    LrcUtils.loadLyricsFile(File(mediaMetadata.localPath))?.let {
                        dbLyric = LyricsEntity(mediaMetadata.id, it)
                    }
                }

                dbLyric
            },
            mediaMetadataProvider = { mediaMetadata },
            onRefreshRequest = { playerConnection.overrideLyrics(mediaMetadata.id, it) },
            onDismiss = this@showLyricsMenu::dismiss,
        )
    }
}
