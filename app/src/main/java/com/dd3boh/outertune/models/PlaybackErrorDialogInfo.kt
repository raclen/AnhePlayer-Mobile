package com.dd3boh.outertune.models

/**
 * 播放失败弹窗数据载体
 */
data class PlaybackErrorDialogInfo(
    val songTitle: String? = null,
    val artistName: String? = null,
    val platformName: String = "当前平台",
    val source: String? = null,
    val isLocal: Boolean = false,
    val sourceUrl: String = "https://anhe-player.loc.cc/sources/",
    val detailMessage: String? = null,
    val errorCode: Int? = null,
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)
