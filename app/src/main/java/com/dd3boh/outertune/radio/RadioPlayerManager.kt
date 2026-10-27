package com.dd3boh.outertune.radio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.constants.RadioLastStationUuidKey
import com.dd3boh.outertune.utils.AppLogManager
import com.dd3boh.outertune.utils.CoilBitmapLoader
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * 配合 [RadioPlaybackService] 在后台提供前台服务保活与通知栏媒体控制。
 */
object RadioPlayerManager {
    private const val TAG = "RadioPlayerManager"
    private const val USER_AGENT = "AnhePlayer-Mobile/2.0 (Android)"

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var appContext: Context? = null

    val session: MediaSession?
        get() = mediaSession

    private val _currentStation = MutableStateFlow<RadioStation?>(null)
    val currentStation: StateFlow<RadioStation?> = _currentStation.asStateFlow()

    private val _playbackState = MutableStateFlow(RadioPlaybackState.IDLE)
    val playbackState: StateFlow<RadioPlaybackState> = _playbackState.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    var onSkipNext: (() -> Unit)? = null
    var onSkipPrevious: (() -> Unit)? = null

    private var stationList: List<RadioStation> = emptyList()
    private var currentIndex: Int = -1

    fun updateStationList(list: List<RadioStation>, index: Int) {
        stationList = list
        currentIndex = index
    }

    fun skipNext() {
        if (onSkipNext != null) {
            onSkipNext?.invoke()
        } else if (stationList.isNotEmpty()) {
            val nextIndex = (currentIndex + 1) % stationList.size
            currentIndex = nextIndex
            play(stationList[nextIndex])
        }
    }

    fun skipPrev() {
        if (onSkipPrevious != null) {
            onSkipPrevious?.invoke()
        } else if (stationList.isNotEmpty()) {
            val prevIndex = if (currentIndex - 1 < 0) stationList.size - 1 else currentIndex - 1
            currentIndex = prevIndex
            play(stationList[prevIndex])
        }
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app

        if (exoPlayer == null) {
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

            val player = ExoPlayer.Builder(app)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(audioAttributes, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> {
                            _playbackState.value = RadioPlaybackState.BUFFERING
                            _errorMessage.value = null
                        }
                        Player.STATE_READY -> {
                            _playbackState.value =
                                if (player.playWhenReady) RadioPlaybackState.PLAYING else RadioPlaybackState.PAUSED
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

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (player.playbackState == Player.STATE_READY) {
                        _playbackState.value =
                            if (playWhenReady) RadioPlaybackState.PLAYING else RadioPlaybackState.PAUSED
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
                    AppLogManager.e(TAG, "Radio player error: ${error.errorCodeName} - ${error.message}", error)
                    _playbackState.value = RadioPlaybackState.ERROR
                    _errorMessage.value = toFriendlyMessage(error)
                }
            })

            exoPlayer = player
        }

        val player = exoPlayer ?: return

        if (mediaSession == null) {
            // 创建 MediaSession 以接入系统媒体控制体系（锁屏/耳机按键/Quick Settings）
            val sessionActivityIntent = PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val sessionCallback = object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    val availableCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                        .add(Player.COMMAND_SEEK_TO_NEXT)
                        .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                        .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailablePlayerCommands(availableCommands)
                        .build()
                }

                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onPlayerCommandRequest(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    playerCommand: Int
                ): Int {
                    when (playerCommand) {
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                            skipNext()
                            return SessionResult.RESULT_SUCCESS
                        }
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                            skipPrev()
                            return SessionResult.RESULT_SUCCESS
                        }
                    }
                    return super.onPlayerCommandRequest(session, controller, playerCommand)
                }
            }

            try {
                mediaSession = MediaSession.Builder(app, player)
                    .setId("RadioPlaybackSession")
                    .setBitmapLoader(CoilBitmapLoader(app))
                    .setSessionActivity(sessionActivityIntent)
                    .setCallback(sessionCallback)
                    .build()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaSession for radio", e)
            }
        }
    }

    fun play(station: RadioStation) {
        AppLogManager.i(TAG, "Play station: ${station.name} (${station.playUrl})")
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

        // 启动前台服务，保持后台运行与通知栏控制，避免约 3 分钟后被系统终止
        appContext?.let { ctx ->
            RadioPlaybackService.start(ctx)
            CoroutineScope(Dispatchers.IO).launch {
                ctx.dataStore.edit { it[RadioLastStationUuidKey] = station.stationUuid }
            }
        }

        try {
            // 上一次播放失败时先重置，避免带着错误状态复用播放器
            if (player.playerError != null) {
                player.stop()
            }

            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(station.displayTitle)
                .setArtist(station.displaySubtitle)
                .setAlbumTitle(station.displayCodecAndBitrate)
                .setArtworkUri(station.favicon?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) })
                .build()

            val uri = Uri.parse(url)
            val builder = MediaItem.Builder()
                .setUri(uri)
                .setMediaId(station.stationUuid)
                .setMediaMetadata(mediaMetadata)

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
                appContext?.let { ctx ->
                    RadioPlaybackService.start(ctx)
                }
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
        try {
            val player = exoPlayer
            if (player != null) {
                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    player.stop()
                } else {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        try {
                            player.stop()
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to stop ExoPlayer asynchronously", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop ExoPlayer", e)
        }
        _playbackState.value = RadioPlaybackState.IDLE
        appContext?.let { ctx ->
            RadioPlaybackService.stop(ctx)
        }
    }

    fun release() {
        stop()
        try {
            mediaSession?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release MediaSession", e)
        }
        mediaSession = null
        try {
            exoPlayer?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release ExoPlayer", e)
        }
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
