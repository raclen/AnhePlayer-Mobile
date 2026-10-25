package com.dd3boh.outertune.radio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RadioPlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR
}

object RadioPlayerManager {
    private const val TAG = "RadioPlayerManager"

    private var exoPlayer: ExoPlayer? = null
    private var appContext: Context? = null

    private val _currentStation = MutableStateFlow<RadioStation?>(null)
    val currentStation: StateFlow<RadioStation?> = _currentStation.asStateFlow()

    private val _playbackState = MutableStateFlow(RadioPlaybackState.IDLE)
    val playbackState: StateFlow<RadioPlaybackState> = _playbackState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun initialize(context: Context) {
        if (exoPlayer != null) return
        val app = context.applicationContext
        appContext = app

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(app)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> {
                                _playbackState.value = RadioPlaybackState.BUFFERING
                                _errorMessage.value = null
                            }
                            Player.STATE_READY -> {
                                _playbackState.value = if (playWhenReady) RadioPlaybackState.PLAYING else RadioPlaybackState.PAUSED
                                _errorMessage.value = null
                            }
                            Player.STATE_ENDED -> {
                                _playbackState.value = RadioPlaybackState.IDLE
                            }
                            Player.STATE_IDLE -> {
                                if (_playbackState.value != RadioPlaybackState.ERROR) {
                                    _playbackState.value = RadioPlaybackState.IDLE
                                }
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            _playbackState.value = RadioPlaybackState.PLAYING
                        } else if (_playbackState.value == RadioPlaybackState.PLAYING) {
                            _playbackState.value = RadioPlaybackState.PAUSED
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Radio player error: ${error.message}", error)
                        _playbackState.value = RadioPlaybackState.ERROR
                        _errorMessage.value = error.localizedMessage ?: "电台音频流无法连接或已下线"
                    }
                })
            }
    }

    fun play(station: RadioStation) {
        val player = exoPlayer ?: return
        val url = station.playUrl
        if (url.isBlank()) {
            _playbackState.value = RadioPlaybackState.ERROR
            _errorMessage.value = "无效的电台地址"
            return
        }

        _currentStation.value = station
        _playbackState.value = RadioPlaybackState.BUFFERING
        _errorMessage.value = null

        try {
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(url))
                .setMediaId(station.stationUuid)
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback for ${station.name}", e)
            _playbackState.value = RadioPlaybackState.ERROR
            _errorMessage.value = e.localizedMessage ?: "无法加载电台流"
        }
    }

    fun togglePlay() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE && _currentStation.value != null) {
                play(_currentStation.value!!)
            } else {
                player.play()
            }
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun stop() {
        exoPlayer?.stop()
        _playbackState.value = RadioPlaybackState.IDLE
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
    }
}
