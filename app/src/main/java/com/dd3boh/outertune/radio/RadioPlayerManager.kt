package com.dd3boh.outertune.radio

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

enum class RadioPlaybackState {
    IDLE,
    BUFFERING,
    PLAYING,
    PAUSED,
    ERROR
}

/**
 * 电台音频流播放器。
 *
 * 说明：电台流大多为 http/https 音频流（MP3/AAC）或 HLS(.m3u8)。
 * HLS 依赖 media3-exoplayer-hls 模块，已通过 composite build 依赖替换接入；
 * 数据源统一走 OkHttp 并带上 User-Agent，避免部分电台服务端拒绝默认 UA。
 */
object RadioPlayerManager {
    private const val TAG = "RadioPlayerManager"
    private const val USER_AGENT = "AnhePlayer-Mobile/2.0 (Android)"

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

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent(USER_AGENT)

        val dataSourceFactory = DefaultDataSource.Factory(app, okHttpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        exoPlayer = ExoPlayer.Builder(app)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
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
                                _playbackState.value =
                                    if (playWhenReady) RadioPlaybackState.PLAYING else RadioPlaybackState.PAUSED
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
                        Log.e(TAG, "Radio player error: ${error.errorCodeName}", error)
                        _playbackState.value = RadioPlaybackState.ERROR
                        _errorMessage.value = toFriendlyMessage(error)
                    }
                })
            }
    }

    fun play(station: RadioStation) {
        val player = exoPlayer ?: return
        val url = station.playUrl
        if (url.isBlank()) {
            _playbackState.value = RadioPlaybackState.ERROR
            _errorMessage.value = "该电台没有可用的播放地址"
            return
        }

        _currentStation.value = station
        _playbackState.value = RadioPlaybackState.BUFFERING
        _errorMessage.value = null

        try {
            // 上一次播放失败时先重置，避免带着错误状态复用播放器
            if (player.playerError != null) {
                player.stop()
            }

            val uri = Uri.parse(url)
            val builder = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(station.stationUuid)

            // 部分电台地址不带 .m3u8 后缀（或是重定向后的短链接），
            // 用接口返回的 hls 标记显式指定 HLS 类型，避免被当成普通音频流解析
            if (station.isHlsStream) {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }

            player.setMediaItem(builder.build())
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback for ${station.name}", e)
            _playbackState.value = RadioPlaybackState.ERROR
            _errorMessage.value = e.toFriendlyMessage()
        }
    }

    fun togglePlay() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            val station = _currentStation.value
            if (player.playbackState == Player.STATE_IDLE && station != null) {
                play(station)
            } else if (player.playerError != null && station != null) {
                play(station)
            } else {
                player.play()
            }
        }
    }

    /** 当前电台是否处于可重试的失败状态 */
    fun hasCurrentStation(): Boolean = _currentStation.value != null

    fun retry() {
        _currentStation.value?.let { play(it) }
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

    /** 把底层异常翻译成用户能看懂的中文提示 */
    private fun toFriendlyMessage(error: PlaybackException): String {
        val httpCode = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                if (httpCode != null) "电台服务器返回 $httpCode，可能已停播" else "电台服务器拒绝连接，可能已停播"

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> "网络连接超时，请检查网络后重试"

            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "电台流地址已失效"

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ->
                "该电台的流格式暂不支持"

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                "电台流数据异常，可能需要换一台"

            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "该电台的音频编码不受支持"

            else -> {
                // 兜底：不把异常类名直接抛给用户，只做粗略归类
                val cause = generateSequence(error as Throwable) { it.cause }.lastOrNull()
                if (cause is ClassNotFoundException) {
                    "播放内核缺少该电台所需的解码模块"
                } else {
                    "该电台暂时无法播放，试试下一台"
                }
            }
        }
    }

    private fun Throwable.toFriendlyMessage(): String {
        val cause = generateSequence(this) { it.cause }.lastOrNull()
        return when {
            cause is ClassNotFoundException -> "播放内核缺少该电台所需的解码模块"
            this is HttpDataSource.InvalidResponseCodeException -> "电台服务器返回 ${this.responseCode}，可能已停播"
            else -> "该电台暂时无法播放，试试下一台"
        }
    }
}
