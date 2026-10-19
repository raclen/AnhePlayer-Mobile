package com.dd3boh.outertune.constants

import androidx.compose.runtime.Immutable

/**
 * 预设曲线的固定频段（Hz），与 AutoEQ FixedBandEQ 的 10 段对齐。
 */
val EqualizerPresetFrequencies = floatArrayOf(
    31f, 62f, 125f, 250f, 500f, 1_000f, 2_000f, 4_000f, 8_000f, 16_000f
)

const val DefaultEqualizerPresetId = "original_sound"

@Immutable
data class EqualizerPreset(
    val id: String,
    val title: String,
    val tag: String?,
    val author: String,
    /** 10 段增益（dB），频点对应 [EqualizerPresetFrequencies]。 */
    val gains: List<Float>,
    /** 预增益（dB），用于抵消正增益造成的削波；AutoEQ 预设置直接采用其 Preamp 值。 */
    val preamp: Float,
)

/**
 * 音效预设。
 *
 * 耳机类预设的曲线来自公开的 AutoEQ FixedBandEQ 数据：
 * https://github.com/jaakkopasanen/AutoEq
 * 界面上的标签与作者按 Salt Player 均衡器列表复刻，仅作展示。
 */
object EqualizerPresets {
    val all = listOf(
        EqualizerPreset(
            id = "original_sound",
            title = "Original Sound",
            tag = "定制原声",
            author = "Moriafly",
            gains = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
            preamp = 0f
        ),
        EqualizerPreset(
            id = "bass",
            title = "低音 Bass",
            tag = "通用",
            author = "Moriafly",
            gains = listOf(6f, 5f, 4f, 2f, 1f, 0f, -1f, -1f, -1f, -1f),
            preamp = -6f
        ),
        EqualizerPreset(
            id = "clear_v2",
            title = "清澈 Clear V2",
            tag = "通用",
            author = "Syaoran",
            gains = listOf(-2f, -1f, 0f, 0f, 1f, 2f, 3f, 3f, 2f, 1f),
            preamp = -3f
        ),
        EqualizerPreset(
            id = "clear_sweet",
            title = "清澈悦耳",
            tag = "椒盐自调",
            author = "Moriafly",
            gains = listOf(-1f, 0f, 1f, 1f, 2f, 2f, 2f, 1f, 0f, -1f),
            preamp = -2f
        ),
        EqualizerPreset(
            id = "akg_k240_mkii",
            title = "AKG K240 MKII",
            tag = null,
            author = "Headphone.com",
            gains = listOf(6.6f, 5.1f, -2.0f, -3.9f, -0.9f, 1.3f, 0.8f, 3.7f, -1.0f, 2.5f),
            preamp = -7.5f
        ),
        EqualizerPreset(
            id = "apple_airpods",
            title = "Apple AirPods",
            tag = null,
            author = "oratory1990",
            gains = listOf(6.3f, 5.2f, 2.2f, 0.3f, 1.4f, 2.7f, -3.9f, 0.0f, -2.8f, -11.5f),
            preamp = -7.4f
        ),
        EqualizerPreset(
            id = "apple_airpods_max",
            title = "Apple AirPods Max",
            tag = null,
            author = "Crinacle, GRAS 43AG-7",
            gains = listOf(-4.1f, -0.9f, -1.6f, -2.1f, -0.4f, -2.9f, 1.6f, 2.9f, 4.3f, -10.6f),
            preamp = -4.2f
        ),
        EqualizerPreset(
            id = "apple_airpods_pro",
            title = "Apple AirPods Pro",
            tag = "热门",
            author = "Rtings",
            gains = listOf(3.5f, 1.9f, 1.6f, -1.2f, -2.5f, 1.9f, -0.7f, 2.3f, 0.0f, -12.0f),
            preamp = -3.9f
        ),
        EqualizerPreset(
            id = "beats_pro",
            title = "Beats Pro",
            tag = null,
            author = "Innerfidelity",
            gains = listOf(5.2f, -1.2f, -1.7f, -2.9f, -0.3f, 3.7f, 0.4f, -2.6f, 3.1f, 4.3f),
            preamp = -4.9f
        ),
        EqualizerPreset(
            id = "bose_quietcomfort_35_ii",
            title = "Bose QuietComfort 35 II",
            tag = null,
            author = "Rtings",
            gains = listOf(-0.8f, -0.9f, -1.0f, -1.2f, 1.1f, 2.2f, -1.5f, 0.6f, -1.0f, -2.4f),
            preamp = -2.1f
        ),
    )

    fun findById(id: String?): EqualizerPreset =
        all.firstOrNull { it.id == id } ?: all.first()
}
