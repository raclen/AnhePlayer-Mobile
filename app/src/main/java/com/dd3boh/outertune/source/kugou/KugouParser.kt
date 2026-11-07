package com.dd3boh.outertune.source.kugou

import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object KugouParser {
    private fun clean(value: String): String = value.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim()
    private fun cover(value: String): String? = value.replace("{size}", "480").replace("http://", "https://").takeIf { it.startsWith("https://") }
    fun song(item: JsonObject): MediaMetadata? {
        val audio = item.obj("audio_info")
        val deprecated = item.obj("deprecated")
        val hash = item.text("hash", "FileHash", "filehash").ifBlank { audio.text("hash") }.ifBlank { deprecated.text("hash") }.lowercase()
        if (!hash.matches(Regex("[a-f0-9]{32}"))) return null
        val combinedName = clean(item.text("name", "filename", "FileName"))
        val singer = clean(item.text("singername", "SingerName", "author_name", "singer").ifBlank {
            item.rows("authors").joinToString("、") { it.text("author_name", "name") }
        }.ifBlank { combinedName.substringBefore(" - ", "") })
        val title = clean(item.text("ori_audio_name", "OriSongName", "SongName", "songname", "songname_original", "audio_name").ifBlank {
            combinedName.substringAfter(" - ", combinedName)
        })
        if (title.isBlank()) return null
        val album = item.obj("album_info")
        val albumId = item.text("album_id", "AlbumID").ifBlank { album.text("album_id") }
        val albumName = clean(item.text("album_name", "AlbumName").ifBlank { item.obj("albuminfo").text("name") }.ifBlank { album.text("album_name") })
        val trans = item.obj("trans_param")
        val image = cover(item.text("Image", "image", "cover", "sizable_cover").ifBlank {
            trans.text("union_cover", "sizable_cover", "album_sizable_cover")
        })
        val seconds = (item.number("Duration", "duration") ?: deprecated.number("duration"))?.toInt()
        val milliseconds = item.number("timelen", "timelength", "timeLength") ?: audio.number("timelength")
        val duration = seconds?.takeIf { it > 0 } ?: milliseconds?.takeIf { it > 0 }?.div(1000)?.toInt() ?: -1
        val audioId = item.text("album_audio_id", "MixSongID", "audio_id", "mixsongid").ifBlank { item.obj("base").text("album_audio_id") }
        val raw = buildJsonObject {
            put("version", 1)
            put("hash", hash)
            put("album_id", albumId)
            put("album_audio_id", audioId)
            put("hash_320", item.text("HQFileHash", "320hash", "hash_320").ifBlank { audio.text("hash_320") })
            put("hash_flac", item.text("SQFileHash", "sqhash", "hash_flac").ifBlank { audio.text("hash_flac") })
        }
        return MediaMetadata(
            id = KUGOU_CONCEPT_ID_PREFIX + hash,
            title = title,
            artists = listOf(MediaMetadata.Artist(null, singer.ifBlank { "未知歌手" })),
            duration = duration,
            thumbnailUrl = image,
            album = albumName.takeIf { it.isNotBlank() }?.let { MediaMetadata.Album("kgc_album_${albumId.ifBlank { albumName }}", it) },
            genre = null,
            source = KUGOU_CONCEPT_SOURCE,
            sourceId = hash,
            sourceData = raw.toString(),
        )
    }
    fun songs(rows: List<JsonObject>): List<MediaMetadata> = rows.mapNotNull(::song).distinctBy { it.id }
    fun playlist(item: JsonObject): KugouCollection? {
        val id = item.text("global_collection_id", "GlobalCollectionId", "GlobalCollectionID", "gid", "Gid")
        if (id.isBlank()) return null
        val title = clean(item.text("specialname", "SpecialName", "name", "title"))
        return KugouCollection(id, title.ifBlank { "酷狗歌单" }, cover(item.text("flexible_cover", "imgurl", "ImgUrl", "cover", "pic")), clean(item.text("intro", "description")))
    }
    fun rank(item: JsonObject): KugouCollection? {
        val id = item.text("rankid", "rank_id")
        if (id.isBlank()) return null
        return KugouCollection(id, clean(item.text("rankname", "rank_name", "name")), cover(item.text("imgurl", "cover")), clean(item.text("intro")))
    }
    fun page(body: JsonObject, page: Int, limit: Int = 15, vararg rowKeys: String): KugouSongPage {
        val data = body.obj("data")
        val rows = data.rows(*rowKeys).ifEmpty { body.rows(*rowKeys) }
        val total = data.number("total", "count", "total_count")
        val info = data.obj("list_info")
        return KugouSongPage(songs(rows), if (total != null) page.toLong() * limit < total else rows.size >= limit,
            clean(info.text("name")), clean(info.text("intro")), cover(info.text("pic")))
    }
    fun containsClaimDate(body: JsonObject, date: String): Boolean {
        // 只接受领取记录的明确日期字段，不把任意字符串或 VIP 当前有效当作今日成功。
        fun inspect(value: kotlinx.serialization.json.JsonElement): Boolean = when (value) {
            is JsonObject -> {
                val claimedDate = value.text("receive_day", "receive_date", "claim_date")
                (claimedDate == date && value.number("status", "receive_status") == 1L) || value.values.any(::inspect)
            }
            is JsonArray -> value.any(::inspect)
            else -> false
        }
        return inspect(body)
    }
}
