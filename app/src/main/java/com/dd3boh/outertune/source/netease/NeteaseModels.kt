package com.dd3boh.outertune.source.netease

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.lx.LxMusicInfo
import com.dd3boh.outertune.source.lx.LxMusicMeta
import com.dd3boh.outertune.source.lx.LxQuality
import com.dd3boh.outertune.source.lx.LxQualityInfo
import com.dd3boh.outertune.source.lx.toMediaMetadata

/**
 * 网易云音乐专区数据模型
 */
data class NeteaseAccountProfile(
    val userId: String = "",
    val nickname: String = "",
    val avatarUrl: String = "",
    val vipType: Int = 0,
    val signature: String = ""
) {
    val isVip: Boolean get() = vipType > 0
    val accountHint: String get() = if (userId.isNotBlank()) "UID: $userId" else ""
}

data class NeteaseSongItem(
    val id: String,
    val name: String,
    val singer: String,
    val albumName: String = "",
    val albumId: String? = null,
    val coverUrl: String? = null,
    val durationSeconds: Int = 0,
    val reason: String? = null
) {
    fun toMediaMetadata(): MediaMetadata {
        val interval = "%02d:%02d".format(durationSeconds / 60, durationSeconds % 60)
        return LxMusicInfo(
            id = "wy_$id",
            songmid = id,
            name = name,
            singer = singer,
            source = "wy",
            interval = interval,
            musicId = id,
            meta = LxMusicMeta(
                songId = id,
                albumName = albumName,
                albumId = albumId,
                picUrl = coverUrl,
                qualitys = listOf(LxQuality("128k")),
                _qualitys = mapOf("128k" to LxQualityInfo()),
            )
        ).toMediaMetadata()
    }
}

data class NeteaseRadarResult(
    val playlistId: String? = null,
    val title: String = "私人雷达",
    val description: String = "根据你的音乐口味定制，每天更新",
    val coverUrl: String? = null,
    val isFallback: Boolean = false,
    val songs: List<NeteaseSongItem> = emptyList()
)

sealed interface QrLoginStatus {
    object WaitingScan : QrLoginStatus
    object WaitingConfirm : QrLoginStatus
    data class Success(val cookie: String) : QrLoginStatus
    object Expired : QrLoginStatus
    data class Error(val message: String) : QrLoginStatus
}
