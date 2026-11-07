package com.dd3boh.outertune.source.kugou

enum class KugouRadioMode(
    val apiValue: String,
    val title: String,
    val subtitle: String,
    val playlistId: String,
) {
    HEART("normal", "红心电台", "根据你的口味持续刷新", "kgc_radio_normal"),
    NICHE("small", "小众电台", "给你一点不那么主流的惊喜", "kgc_radio_small"),
    PEAK("peak", "速览电台", "切到更带劲的高能推荐频道", "kgc_radio_peak");

    companion object {
        fun fromQueue(playlistId: String?, title: String?, source: String?): KugouRadioMode? {
            if (source != KUGOU_CONCEPT_SOURCE) return null
            entries.firstOrNull { it.playlistId == playlistId }?.let { return it }
            // v2.0.21 的红心队列没有专用 ID；其他频道、每日推荐和外部电台不能按标题猜测。
            return HEART.takeIf { playlistId.isNullOrEmpty() && title == HEART.title }
        }
    }
}
