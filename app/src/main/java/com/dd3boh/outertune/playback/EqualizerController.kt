package com.dd3boh.outertune.playback

import android.content.Context
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.dd3boh.outertune.constants.DefaultEqualizerPresetId
import com.dd3boh.outertune.constants.EqualizerEnabledKey
import com.dd3boh.outertune.constants.EqualizerPreset
import com.dd3boh.outertune.constants.EqualizerPresetFrequencies
import com.dd3boh.outertune.constants.EqualizerPresetKey
import com.dd3boh.outertune.constants.EqualizerPresets
import com.dd3boh.outertune.utils.dataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * 应用内均衡器控制器。
 *
 * 由 MusicService 持有，监听 DataStore 中的开关与预设，把 10 段预设曲线插值映射到
 * 设备实际的均衡器频段。效果器绑定 ExoPlayer 的 audioSessionId，切歌不会失效。
 */
class EqualizerController(
    private val context: Context,
    private val player: ExoPlayer,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "EqualizerController"

        /**
         * 查询设备是否实现了均衡器音效。
         *
         * 部分 ROM 在音频会话尚未激活时会拒绝创建效果器，这与“设备不支持”是两回事，
         * 因此能力判定只认系统音效描述符；查询异常或返回空列表时保持乐观，交由创建结果决定。
         */
        private fun queryDeviceEqualizerSupport(): Boolean {
            return try {
                val effects = AudioEffect.queryEffects()
                effects.isEmpty() || effects.any { it.type == AudioEffect.EFFECT_TYPE_EQUALIZER }
            } catch (e: Exception) {
                Log.w(TAG, "查询设备音效能力失败", e)
                true
            }
        }
    }

    private val lock = Any()

    @Volatile
    private var equalizer: Equalizer? = null
    private var observeJob: Job? = null

    @Volatile
    private var currentEnabled = false

    @Volatile
    private var currentPresetId = DefaultEqualizerPresetId

    /** 当前设备是否具备均衡器能力；页面用它做降级提示。 */
    val isAvailable = MutableStateFlow(queryDeviceEqualizerSupport())

    private val playerListener = object : Player.Listener {
        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            // 会话变化时效果器需要重新绑定，否则会作用在旧的 AudioTrack 上
            scope.launch {
                withContext(Dispatchers.Default) {
                    synchronized(lock) { releaseEqualizerInternal() }
                }
                applySettingsSafely(currentEnabled, currentPresetId)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 部分 ROM 只在音频流真正开始后才允许挂载效果器，这里补一次重试
            if (isPlaying && currentEnabled && equalizer == null) {
                scope.launch { applySettingsSafely(currentEnabled, currentPresetId) }
            }
        }
    }

    fun start() {
        player.addListener(playerListener)
        observeJob = scope.launch {
            context.dataStore.data
                .map { prefs ->
                    (prefs[EqualizerEnabledKey] ?: false) to
                        (prefs[EqualizerPresetKey] ?: DefaultEqualizerPresetId)
                }
                .distinctUntilChanged()
                .collect { (enabled, presetId) ->
                    applySettingsSafely(enabled, presetId)
                }
        }
    }

    fun release() {
        observeJob?.cancel()
        observeJob = null
        player.removeListener(playerListener)
        synchronized(lock) { releaseEqualizerInternal() }
    }

    /**
     * 应用设置，并吞掉非取消类异常。
     *
     * MusicService 的协程作用域没有异常处理器，这里漏出的异常会直接杀死进程。
     */
    private suspend fun applySettingsSafely(enabled: Boolean, presetId: String) {
        try {
            applySettings(enabled, presetId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "应用均衡器设置失败", e)
        }
    }

    private suspend fun applySettings(enabled: Boolean, presetId: String) {
        currentEnabled = enabled
        currentPresetId = presetId

        // ExoPlayer 的公开 getter 必须在应用主线程调用，后台线程访问会被
        // verifyApplicationThread 判定为错误线程并抛出 IllegalStateException
        val audioSessionId = withContext(Dispatchers.Main.immediate) {
            player.audioSessionId
        }

        withContext(Dispatchers.Default) {
            synchronized(lock) {
                // 关闭时不需要创建效果器；播放器尚未建立音频会话时创建必然失败，
                // 会误判为设备不支持，所以等开关真正打开或会话就绪后再绑定
                if (!enabled) {
                    releaseEqualizerInternal()
                    return@synchronized
                }

                val eq = ensureEqualizer(audioSessionId) ?: return@synchronized
                try {
                    val preset = EqualizerPresets.findById(presetId)
                    if (eq.numberOfBands <= 0) {
                        isAvailable.value = queryDeviceEqualizerSupport()
                        return@synchronized
                    }

                    // 修改频段前先关闭，避免在部分设备上出现瞬时爆音
                    eq.enabled = false
                    val levelRange = eq.bandLevelRange
                    val minLevel = levelRange[0].toInt()
                    val maxLevel = levelRange[1].toInt()

                    for (band in 0 until eq.numberOfBands.toInt()) {
                        val centerFreq = eq.getCenterFreq(band.toShort())
                        if (centerFreq <= 0) continue
                        val gain = interpolateGain(centerFreq / 1000f, preset)
                        val level = (gain * 100f).roundToInt().coerceIn(minLevel, maxLevel)
                        eq.setBandLevel(band.toShort(), level.toShort())
                    }

                    eq.enabled = true
                    isAvailable.value = true
                } catch (e: Exception) {
                    Log.w(TAG, "应用均衡器预设失败", e)
                    isAvailable.value = queryDeviceEqualizerSupport()
                }
            }
        }
    }

    private fun ensureEqualizer(audioSessionId: Int): Equalizer? {
        equalizer?.let { return it }

        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET) {
            // 会话建立后 onAudioSessionIdChanged 会再次尝试绑定
            Log.i(TAG, "播放器音频会话尚未建立，等待会话就绪后再绑定均衡器")
            return null
        }

        return try {
            val eq = Equalizer(0, audioSessionId)
            equalizer = eq
            eq.enabled = false
            isAvailable.value = true
            eq
        } catch (e: Exception) {
            Log.w(TAG, "当前音频会话无法创建均衡器，audioSessionId=$audioSessionId", e)
            releaseEqualizerInternal()
            // 创建失败不等同于设备不支持，只有系统明确没有均衡器实现时才降级
            isAvailable.value = queryDeviceEqualizerSupport()
            null
        }
    }

    private fun releaseEqualizerInternal() {
        equalizer?.let {
            try {
                it.enabled = false
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        equalizer = null
    }

    /** 把 10 段预设按对数频率插值到设备频段，并叠加预增益。 */
    private fun interpolateGain(freqHz: Float, preset: EqualizerPreset): Float {
        val freqs = EqualizerPresetFrequencies
        val gains = preset.gains
        val gain = when {
            freqHz <= freqs.first() -> gains.first()
            freqHz >= freqs.last() -> gains.last()
            else -> {
                var result = gains.last()
                for (i in 0 until freqs.lastIndex) {
                    if (freqHz <= freqs[i + 1]) {
                        val x0 = ln(freqs[i].toDouble())
                        val x1 = ln(freqs[i + 1].toDouble())
                        val t = ((ln(freqHz.toDouble()) - x0) / (x1 - x0)).toFloat()
                        result = gains[i] + (gains[i + 1] - gains[i]) * t
                        break
                    }
                }
                result
            }
        }
        return gain + preset.preamp
    }
}
