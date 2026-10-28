package com.dd3boh.outertune.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaStyleNotificationHelper
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.dd3boh.outertune.MainActivity
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.KeepAliveKey
import com.dd3boh.outertune.constants.RadioKeepAliveOnTaskClearKey
import com.dd3boh.outertune.constants.RadioWakeLockKey
import com.dd3boh.outertune.constants.StopMusicOnTaskClearKey
import com.dd3boh.outertune.utils.AppLogManager
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电台后台播放前台服务。
 *
 * 作用：
 * 1. 启动为前台服务（foregroundServiceType=mediaPlayback），防止退到后台时应用进程被系统判定为
 *    Cached 进程而在约 3 分钟后被系统 CachedAppOptimizer / 电池策略冻结或杀掉；
 * 2. 在系统通知栏展示正在播放的电台名称、简介与控制按键（上一台、播放/暂停、下一台、关闭）；
 * 3. 关联 MediaSession，支持锁屏媒体控制、蓝牙耳机按键与系统媒体中心（Quick Settings）。
 */
class RadioPlaybackService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var currentFaviconUrl: String? = null
    private var currentFaviconBitmap: Bitmap? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireLocks() {
        val enabled = dataStore.get(RadioWakeLockKey, true)
        if (!enabled) return

        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OuterTune:RadioWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24小时防休眠保护

            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                @Suppress("DEPRECATION")
                val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                } else {
                    WifiManager.WIFI_MODE_FULL
                }
                wifiLock = wm?.createWifiLock(lockType, "OuterTune:RadioWifiLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            wifiLock?.acquire()
            Log.d(TAG, "WakeLock and WifiLock acquired")
        } catch (e: Exception) {
            AppLogManager.e(TAG, "Failed to acquire WakeLock/WifiLock", e)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            Log.d(TAG, "WakeLock and WifiLock released")
        } catch (e: Exception) {
            AppLogManager.e(TAG, "Failed to release WakeLock/WifiLock", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // 持续监听当前播放电台与播放状态，动态同步前台服务与通知栏
        scope.launch {
            combine(RadioPlayerManager.currentStation, RadioPlayerManager.playbackState) { station, state ->
                station to state
            }.collect { (station, state) ->
                handlePlaybackStateUpdate(station, state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action")

        when (action) {
            ACTION_TOGGLE -> {
                RadioPlayerManager.togglePlay()
            }
            ACTION_PLAY -> {
                RadioPlayerManager.togglePlay()
            }
            ACTION_PAUSE -> {
                RadioPlayerManager.pause()
            }
            ACTION_STOP -> {
                RadioPlayerManager.stop()
                stopForegroundAndSelf()
                return START_NOT_STICKY
            }
            ACTION_NEXT -> {
                RadioPlayerManager.skipNext()
            }
            ACTION_PREV -> {
                RadioPlayerManager.skipPrev()
            }
            ACTION_START -> {
                // startForegroundService 必须在 5 秒内调用 startForeground
                val currentStation = RadioPlayerManager.currentStation.value
                val isPlaying = RadioPlayerManager.playbackState.value == RadioPlaybackState.PLAYING
                ensureForegroundNotification(currentStation, isPlaying)
            }
        }

        return START_STICKY
    }

    private fun handlePlaybackStateUpdate(station: RadioStation?, state: RadioPlaybackState) {
        when (state) {
            RadioPlaybackState.BUFFERING,
            RadioPlaybackState.PLAYING -> {
                acquireLocks()
                val isPlaying = (state == RadioPlaybackState.PLAYING)
                val overrideText = if (state == RadioPlaybackState.BUFFERING) {
                    RadioPlayerManager.errorMessage.value ?: "正在缓冲/连接..."
                } else null
                ensureForegroundNotification(station, isPlaying, overrideText)
                loadFaviconIfNeeded(station, isPlaying)
            }
            RadioPlaybackState.PAUSED -> {
                releaseLocks()
                val notification = buildNotification(station, isPlaying = false, currentFaviconBitmap)
                // 暂停时脱离强制前台，允许用户在通知栏划掉，但保留通知支持随时恢复播放
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                try {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to post paused notification", e)
                }
            }
            RadioPlaybackState.IDLE -> {
                releaseLocks()
                stopForegroundAndSelf()
            }
            RadioPlaybackState.ERROR -> {
                releaseLocks()
                val errorMsg = RadioPlayerManager.errorMessage.value ?: "播放中断"
                val notification = buildNotification(station, isPlaying = false, currentFaviconBitmap, overrideText = errorMsg)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                try {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(NOTIFICATION_ID, notification)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to post error notification", e)
                }
                scope.launch {
                    delay(30_000L)
                    if (RadioPlayerManager.playbackState.value == RadioPlaybackState.ERROR) {
                        stopForegroundAndSelf()
                    }
                }
            }
        }
    }

    private fun ensureForegroundNotification(station: RadioStation?, isPlaying: Boolean, overrideText: String? = null) {
        val notification = buildNotification(station, isPlaying, currentFaviconBitmap, overrideText)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
        }
    }

    private fun loadFaviconIfNeeded(station: RadioStation?, isPlaying: Boolean) {
        val faviconUrl = station?.favicon
        if (faviconUrl.isNullOrBlank()) {
            currentFaviconUrl = null
            currentFaviconBitmap = null
            return
        }
        if (faviconUrl == currentFaviconUrl && currentFaviconBitmap != null) {
            return
        }
        currentFaviconUrl = faviconUrl
        scope.launch(Dispatchers.IO) {
            try {
                val request = ImageRequest.Builder(this@RadioPlaybackService)
                    .data(faviconUrl)
                    .allowHardware(false)
                    .build()
                val result = imageLoader.execute(request)
                val bmp = result.image?.toBitmap()
                if (bmp != null && currentFaviconUrl == faviconUrl) {
                    currentFaviconBitmap = bmp
                    withContext(Dispatchers.Main) {
                        val updatedNotif = buildNotification(station, isPlaying, bmp)
                        val nm = getSystemService(NotificationManager::class.java)
                        nm?.notify(NOTIFICATION_ID, updatedNotif)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Failed to load station favicon: ${e.message}")
            }
        }
    }

    private fun buildNotification(
        station: RadioStation?,
        isPlaying: Boolean,
        largeIcon: Bitmap?,
        overrideText: String? = null
    ): Notification {
        val title = station?.displayTitle ?: getString(R.string.radio)
        val text = overrideText ?: buildString {
            val subtitle = station?.displaySubtitle
            val extra = station?.displayCodecAndBitrate
            if (!subtitle.isNullOrBlank()) {
                append(subtitle)
            }
            if (!extra.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(extra)
            }
            if (isEmpty()) {
                append("在线电台")
            }
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val togglePlayIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, RadioPlaybackService::class.java).apply {
                action = ACTION_TOGGLE
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, RadioPlaybackService::class.java).apply {
                action = ACTION_STOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, RadioPlaybackService::class.java).apply {
                action = ACTION_PREV
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val nextIntent = PendingIntent.getService(
            this,
            4,
            Intent(this, RadioPlaybackService::class.java).apply {
                action = ACTION_NEXT
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val isBuffering = (RadioPlayerManager.playbackState.value == RadioPlaybackState.BUFFERING)
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.small_icon)
            .setContentIntent(contentIntent)
            .setDeleteIntent(stopIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying || isBuffering)
            .setOnlyAlertOnce(true)

        if (largeIcon != null) {
            builder.setLargeIcon(largeIcon)
        }

        // Action 0: 上一台
        builder.addAction(
            R.drawable.skip_previous,
            "上一台",
            prevIntent
        )

        // Action 1: 播放/暂停
        val playPauseIcon = if (isPlaying) R.drawable.pause else R.drawable.play
        val playPauseText = if (isPlaying) "暂停" else "播放"
        builder.addAction(
            playPauseIcon,
            playPauseText,
            togglePlayIntent
        )

        // Action 2: 下一台
        builder.addAction(
            R.drawable.skip_next,
            "下一台",
            nextIntent
        )

        // Action 3: 停止
        builder.addAction(
            R.drawable.close,
            "关闭",
            stopIntent
        )

        // 连接 MediaSession，激活系统媒体控制器（锁屏卡片、快捷控制等）
        val session = RadioPlayerManager.session
        if (session != null) {
            try {
                val mediaStyle = MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
                builder.setStyle(mediaStyle)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply MediaStyle", e)
            }
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "电台播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "在线电台后台播放控制"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        val nm = getSystemService(NotificationManager::class.java)
        nm?.cancel(NOTIFICATION_ID)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved")
        val keepAliveOnClear = dataStore.get(RadioKeepAliveOnTaskClearKey, true)
        val globalKeepAlive = dataStore.get(KeepAliveKey, false)
        val isMusicStopOnClear = dataStore.get(StopMusicOnTaskClearKey, true)

        val shouldStop = !keepAliveOnClear && isMusicStopOnClear && !globalKeepAlive
        if (shouldStop) {
            RadioPlayerManager.stop()
            stopForegroundAndSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        serviceJob.cancel()
        stopForegroundAndSelf()
    }

    companion object {
        private const val TAG = "RadioPlaybackService"
        const val NOTIFICATION_ID = 889
        const val CHANNEL_ID = "radio_playback_channel"

        const val ACTION_START = "com.dd3boh.outertune.radio.ACTION_START"
        const val ACTION_PLAY = "com.dd3boh.outertune.radio.ACTION_PLAY"
        const val ACTION_PAUSE = "com.dd3boh.outertune.radio.ACTION_PAUSE"
        const val ACTION_TOGGLE = "com.dd3boh.outertune.radio.ACTION_TOGGLE"
        const val ACTION_STOP = "com.dd3boh.outertune.radio.ACTION_STOP"
        const val ACTION_NEXT = "com.dd3boh.outertune.radio.ACTION_NEXT"
        const val ACTION_PREV = "com.dd3boh.outertune.radio.ACTION_PREV"

        fun start(context: Context) {
            try {
                val intent = Intent(context, RadioPlaybackService::class.java).apply {
                    action = ACTION_START
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting RadioPlaybackService", e)
            }
        }

        fun stop(context: Context) {
            try {
                val intent = Intent(context, RadioPlaybackService::class.java).apply {
                    action = ACTION_STOP
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping RadioPlaybackService", e)
            }
        }
    }
}
