/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.playback

import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.STATE_ENDED
import androidx.media3.common.Timeline
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.uninitializedLyric
import com.dd3boh.outertune.extensions.currentMetadata
import com.dd3boh.outertune.extensions.getCurrentQueueIndex
import com.dd3boh.outertune.extensions.getQueueWindows
import com.dd3boh.outertune.extensions.metadata
import com.dd3boh.outertune.lyrics.PlayerLyrics
import com.dd3boh.outertune.playback.queues.Queue
import com.dd3boh.outertune.radio.RadioPlaybackState
import com.dd3boh.outertune.radio.RadioPlayerManager
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import org.akanework.gramophone.logic.utils.SemanticLyrics
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConnection(
    binder: MediaControllerViewModel,
    val database: MusicDatabase,
) : Player.Listener {
    val TAG = PlayerConnection::class.simpleName.toString()

    val service = binder.getService()!!
    val queueBoard = service.queueBoard
    val player = service.player
    val scope = binder.viewModelScope

    val playbackState = MutableStateFlow(player.playbackState)
    private val playWhenReady = MutableStateFlow(player.playWhenReady)
    val isPlaying = combine(playbackState, playWhenReady) { playbackState, playWhenReady ->
        playWhenReady && playbackState != STATE_ENDED
    }.stateIn(scope, SharingStarted.Lazily, player.playWhenReady && player.playbackState != STATE_ENDED)
    val waitingForNetworkConnection: StateFlow<Boolean> = service.waitingForNetworkConnection.asStateFlow()
    val mediaMetadata = MutableStateFlow(player.currentMetadata)
    val currentSong = mediaMetadata.flatMapLatest {
        database.song(it?.id)
    }

    // 最近一次加载结果：重新展开播放器时直接复用，避免歌词区域重复闪烁加载状态
    @Volatile
    private var lastLoadedLyrics: PlayerLyrics? = null

    // 歌词菜单中编辑、搜索或刷新得到的歌词，仅对对应歌曲生效
    private val lyricsOverride = MutableStateFlow<PlayerLyrics?>(null)

    /**
     * 当前歌曲歌词。null 表示正在加载；[PlayerLyrics.lyrics] 为 null 表示未找到。
     *
     * 歌词读取包含 TagLib JNI、文件与网络 IO，放在 IO 线程执行，避免切歌时阻塞主线程。
     * 切歌时先发出 null，避免短暂显示上一首歌曲的歌词。
     */
    val currentLyrics: StateFlow<PlayerLyrics?> = combine(
        mediaMetadata
            .distinctUntilChangedBy { it?.id }
            .transformLatest { metadata ->
                if (metadata == null) {
                    emit(null)
                    return@transformLatest
                }
                lastLoadedLyrics?.takeIf { it.mediaId == metadata.id }?.let {
                    emit(it)
                    return@transformLatest
                }
                emit(null)
                val loaded = try {
                    service.lyricsHelper.getLyricsWithSource(metadata)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportException(e)
                    null
                }
                val result = PlayerLyrics(
                    mediaId = metadata.id,
                    lyrics = loaded?.lyrics?.takeUnless { it == uninitializedLyric },
                    source = loaded?.source,
                )
                lastLoadedLyrics = result
                emit(result)
            }
            .flowOn(Dispatchers.IO),
        lyricsOverride,
    ) { loaded, override ->
        if (override != null && override.mediaId == loaded?.mediaId) override else loaded
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val currentMediaItemIndex = MutableStateFlow(-1)

    val queueWindows = MutableStateFlow<List<Timeline.Window>>(emptyList())

    var queuePlaylistId = MutableStateFlow<String?>(null)
    val currentWindowIndex = MutableStateFlow(-1)

    val shuffleModeEnabled = MutableStateFlow(false)
    val repeatMode = MutableStateFlow(REPEAT_MODE_OFF)

    val canSkipPrevious = MutableStateFlow(true)
    val canSkipNext = MutableStateFlow(true)

    val error = MutableStateFlow<PlaybackException?>(null)

    init {
        player.addListener(this)

        playbackState.value = player.playbackState
        playWhenReady.value = player.playWhenReady
        queuePlaylistId.value = service.queuePlaylistId
        queueWindows.value = player.getQueueWindows()
        currentWindowIndex.value = player.getCurrentQueueIndex()
        currentMediaItemIndex.value = player.currentMediaItemIndex
        shuffleModeEnabled.value = player.shuffleModeEnabled
        repeatMode.value = player.repeatMode

        scope.launch {
            mediaMetadata.value = player.currentMetadata ?: database.getResumptionQueue()?.getCurrentSong()
        }
    }

    fun playQueue(
        queue: Queue,
        shouldResume: Boolean = false,
        replace: Boolean = true,
        isRadio: Boolean = false,
        title: String? = null
    ) {
        RadioPlayerManager.stop()
        service.playQueue(
            queue = queue,
            shouldResume = shouldResume,
            replace = replace,
            title = title,
            isRadio = isRadio
        )
    }

    /**
     * Add item to queue, right after current playing item
     */
    fun enqueueNext(item: MediaItem) = enqueueNext(listOf(item))

    /**
     * Add items to queue, right after current playing item
     */
    fun enqueueNext(items: List<MediaItem>) {
        service.enqueueNext(items)
    }

    /**
     * Add item to end of current queue
     */
    fun enqueueEnd(item: MediaItem) = enqueueEnd(listOf(item))

    /**
     * Add items to end of current queue
     */
    fun enqueueEnd(items: List<MediaItem>) {
        service.enqueueEnd(items)
    }

    fun toggleLike() {
        service.toggleLike()
    }

    fun toggleLibrary() {
        service.toggleLibrary()
    }

    /**
     * 歌词菜单编辑、搜索或刷新后调用，立即替换指定歌曲的显示歌词。
     */
    fun overrideLyrics(mediaId: String, lyrics: SemanticLyrics?) {
        lyricsOverride.value = PlayerLyrics(
            mediaId = mediaId,
            lyrics = lyrics?.takeUnless { it == uninitializedLyric },
            source = null,
        )
    }

    override fun onPlaybackStateChanged(state: Int) {
        playbackState.value = state
        error.value = player.playerError
    }

    override fun onPlayWhenReadyChanged(newPlayWhenReady: Boolean, reason: Int) {
        playWhenReady.value = newPlayWhenReady
        if (newPlayWhenReady && RadioPlayerManager.playbackState.value != RadioPlaybackState.IDLE) {
            RadioPlayerManager.stop()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        mediaMetadata.value = mediaItem?.metadata
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        queueWindows.value = player.getQueueWindows()
        queuePlaylistId.value = service.queuePlaylistId
        currentMediaItemIndex.value = player.currentMediaItemIndex
        currentWindowIndex.value = player.getCurrentQueueIndex()
        updateCanSkipPreviousAndNext()
    }

    /**
     * Shuffles the queue
     */
    fun triggerShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
        updateCanSkipPreviousAndNext()
    }

    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
        shuffleModeEnabled.value = enabled
        updateCanSkipPreviousAndNext()
    }

    override fun onRepeatModeChanged(mode: Int) {
        repeatMode.value = mode
        updateCanSkipPreviousAndNext()
    }

    override fun onPlayerErrorChanged(playbackError: PlaybackException?) {
        if (playbackError != null) {
            reportException(playbackError)
        }
        error.value = playbackError
    }

    private fun updateCanSkipPreviousAndNext() {
        if (!player.currentTimeline.isEmpty) {
            val window = player.currentTimeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
            canSkipPrevious.value = player.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                    || !window.isLive()
                    || player.isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            canSkipNext.value = window.isLive() && window.isDynamic
                    || player.isCommandAvailable(COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        } else {
            canSkipPrevious.value = false
            canSkipNext.value = false
        }
    }

    fun dispose() {
        player.removeListener(this)
    }

    fun softKillPlayer() {
        Log.i(TAG, "Stopping player and uninitializing queue")
        player.clearMediaItems()
        service.deInitQueue()
    }
}
