package com.dd3boh.outertune.source.lx

import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class LxMusicInfo(
    val id: String,
    val songmid: String,
    val name: String,
    val singer: String,
    val source: String,
    val interval: String? = null,
    val meta: LxMusicMeta,
    val hash: String? = null,
    val mid: String? = null,
    val musicId: String? = null,
    // 汽水取流需要完整 track（vid、bit_rates 等），随 sourceData 一同持久化。
    val raw: JsonObject? = null,
)

@Serializable
data class LxMusicMeta(
    val songId: String,
    val albumName: String = "",
    val albumId: String? = null,
    val picUrl: String? = null,
    val qualitys: List<LxQuality> = emptyList(),
    val _qualitys: Map<String, LxQualityInfo> = emptyMap(),
)

@Serializable
data class LxQuality(
    val type: String,
    val size: String? = null,
)

@Serializable
data class LxQualityInfo(
    val size: String? = null,
)

internal val lxJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** 只在脚本边界展开原始参数，数据库和 UI 继续保留带平台前缀的稳定歌曲 ID。 */
internal fun JsonObject.toScriptMusicInfo(): JsonObject {
    val track = this["raw"] as? JsonObject ?: return this
    return buildJsonObject {
        track.forEach { (key, value) -> put(key, value) }
        this@toScriptMusicInfo.forEach { (key, value) -> put(key, value) }
        val trackId = track["id"] ?: this@toScriptMusicInfo["songmid"]
        if (trackId != null) {
            put("id", trackId)
            put("songId", trackId)
        }
    }
}

fun LxMusicInfo.toMediaMetadata() = MediaMetadata(
    id = id,
    title = name,
    artists = listOf(MediaMetadata.Artist(id = null, name = singer)),
    duration = interval.toDurationSeconds(),
    thumbnailUrl = meta.picUrl,
    album = meta.albumName.takeIf { it.isNotBlank() }?.let {
        MediaMetadata.Album(id = meta.albumId ?: "$source:${meta.albumName}", title = it)
    },
    genre = null,
    source = source,
    sourceId = meta.songId,
    sourceData = lxJson.encodeToString(this),
)

private fun String?.toDurationSeconds(): Int {
    val parts = this?.split(':')?.mapNotNull(String::toIntOrNull).orEmpty()
    return when (parts.size) {
        2 -> parts[0] * 60 + parts[1]
        3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
        else -> -1
    }
}
