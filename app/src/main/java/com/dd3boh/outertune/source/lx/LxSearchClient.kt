package com.dd3boh.outertune.source.lx

import android.text.Html
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import android.util.Base64
import java.security.MessageDigest

/** 搜索元数据适配层，播放地址始终由洛雪 JS 音源解析。 */
object LxSearchClient {
    private const val RESULT_LIMIT = 15
    private const val KUGOU_SEARCH_URL = "https://mobiles.kugou.com/api/v3/search/song"
    private const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get/web"
    private const val QQ_SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"

    private val client = HttpClient(OkHttp) {
        defaultRequest {
            headers.append("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            headers.append("Referer", "https://y.qq.com/")
        }
    }

    suspend fun search(query: String, source: LxSearchSource): Result<List<LxMusicInfo>> = runCatching {
        val normalizedQuery = query.trim()
        check(normalizedQuery.isNotEmpty()) { "搜索关键词不能为空" }
        when (source) {
            LxSearchSource.KUGOU -> searchKugou(normalizedQuery)
            LxSearchSource.NETEASE -> searchNetease(normalizedQuery)
            LxSearchSource.QQ -> searchQq(normalizedQuery)
            LxSearchSource.KUWO -> KuwoSearchClient.search(normalizedQuery, limit = RESULT_LIMIT).getOrThrow()
            LxSearchSource.QISHUI -> QishuiSearchClient.search(normalizedQuery, limit = RESULT_LIMIT).getOrThrow()
        }
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun loadCover(item: LxMusicInfo): String? {
        item.meta.picUrl?.let { return it }
        return when (item.source) {
            LxSearchSource.KUWO.sourceId -> KuwoSearchClient.loadCover(item.meta.songId)
            else -> null
        }
    }

    private suspend fun searchKugou(query: String): List<LxMusicInfo> {
        val response = client.get(KUGOU_SEARCH_URL) {
            parameter("format", "json")
            parameter("keyword", query)
            parameter("page", 1)
            parameter("pagesize", RESULT_LIMIT)
            parameter("showtype", 1)
        }
        check(response.status.isSuccess()) { "酷狗搜索请求失败：${response.status}" }
        val items = lxJson.parseToJsonElement(response.body<String>()).jsonObject
            .getObject("data")
            ?.getArray("info")
            .orEmpty()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val hash = item.string("hash").ifBlank { return@mapNotNull null }
            val albumId = item.string("album_id").takeIf(String::isNotBlank)
            LxMusicInfo(
                id = "kg_$hash",
                songmid = hash,
                name = decode(item.string("songname")),
                singer = decode(item.string("singername")),
                source = LxSearchSource.KUGOU.sourceId,
                interval = item.string("duration").toIntOrNull()?.toDuration(),
                hash = hash,
                meta = LxMusicMeta(
                    songId = hash,
                    albumName = decode(item.string("album_name")),
                    albumId = albumId,
                    picUrl = (item.string("image").ifBlank {
                        item.getObject("trans_param")?.string("union_cover").orEmpty()
                    }).replace("{size}", "480").toHttpsOrNull(),
                    qualitys = defaultQualities,
                    _qualitys = defaultQualityMap,
                ),
            )
        }
    }

    private suspend fun searchNetease(query: String): List<LxMusicInfo> {
        val response = client.get(NETEASE_SEARCH_URL) {
            parameter("s", query)
            parameter("type", 1)
            parameter("offset", 0)
            parameter("total", true)
            parameter("limit", RESULT_LIMIT)
            parameter("csrf_token", "")
        }
        check(response.status.isSuccess()) { "网易云搜索请求失败：${response.status}" }
        val items = lxJson.parseToJsonElement(response.body<String>()).jsonObject
            .getObject("result")
            ?.getArray("songs")
            .orEmpty()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val songId = item.string("id").ifBlank { return@mapNotNull null }
            val album = item.getObject("album") ?: item.getObject("al")
            LxMusicInfo(
                id = "wy_$songId",
                songmid = songId,
                name = decode(item.string("name")),
                singer = item.getArray("artists", "ar").orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("name")?.takeIf(String::isNotBlank) }
                    .joinToString(" / "),
                source = LxSearchSource.NETEASE.sourceId,
                interval = item.string("duration", "dt").toLongOrNull()?.div(1000)?.toInt()?.toDuration(),
                musicId = songId,
                meta = LxMusicMeta(
                    songId = songId,
                    albumName = album?.string("name").orEmpty(),
                    albumId = album?.string("id")?.takeIf(String::isNotBlank),
                    picUrl = album?.string("picUrl")?.toHttpsOrNull()
                        ?: album?.string("picId")?.let(::neteaseCoverUrl),
                    qualitys = defaultQualities,
                    _qualitys = defaultQualityMap,
                ),
            )
        }
    }

    private suspend fun searchQq(query: String): List<LxMusicInfo> {
        val response = client.get(QQ_SEARCH_URL) {
            parameter("p", 1)
            parameter("n", RESULT_LIMIT)
            parameter("w", query)
            parameter("format", "json")
        }
        check(response.status.isSuccess()) { "QQ 音乐搜索请求失败：${response.status}" }
        val items = lxJson.parseToJsonElement(response.body<String>()).jsonObject
            .getObject("data")
            ?.getObject("song")
            ?.getArray("list")
            .orEmpty()
        return items.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val songMid = item.string("songmid").ifBlank { return@mapNotNull null }
            val albumMid = item.string("albummid")
            LxMusicInfo(
                id = "tx_$songMid",
                songmid = songMid,
                name = decode(item.string("songname")),
                singer = item.getArray("singer").orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("name")?.takeIf(String::isNotBlank) }
                    .joinToString(" / "),
                source = LxSearchSource.QQ.sourceId,
                interval = item.string("interval").toIntOrNull()?.toDuration(),
                mid = songMid,
                meta = LxMusicMeta(
                    songId = songMid,
                    albumName = decode(item.string("albumname")),
                    albumId = item.string("albumid").takeIf(String::isNotBlank),
                    picUrl = albumMid.takeIf(String::isNotBlank)
                        ?.let { "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg" },
                    qualitys = defaultQualities,
                    _qualitys = defaultQualityMap,
                ),
            )
        }
    }

    private fun JsonObject.getObject(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.getArray(vararg keys: String): JsonArray? =
        keys.firstNotNullOfOrNull { this[it] as? JsonArray }

    private fun JsonObject.string(vararg keys: String): String =
        keys.firstNotNullOfOrNull { this[it]?.jsonPrimitive?.contentOrNull }.orEmpty()

    private fun Int.toDuration() = "%02d:%02d".format(this / 60, this % 60)

    private fun String.toHttpsOrNull(): String? =
        takeIf(String::isNotBlank)?.replaceFirst("http://", "https://")

    /** 网易云搜索接口只返回 picId，按官方 CDN 的编码规则生成封面地址。 */
    private fun neteaseCoverUrl(picId: String): String? {
        val id = picId.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: return null
        val source = id.toByteArray(Charsets.UTF_8)
        val magic = NETEASE_COVER_MAGIC.toByteArray(Charsets.UTF_8)
        source.forEachIndexed { index, value ->
            source[index] = (value.toInt() xor magic[index % magic.size].toInt()).toByte()
        }
        val encoded = Base64.encodeToString(
            MessageDigest.getInstance("MD5").digest(source),
            Base64.NO_WRAP,
        ).replace('/', '_').replace('+', '-')
        return "https://p3.music.126.net/$encoded/$id.jpg"
    }

    private fun decode(value: String): String =
        if ('&' !in value && '<' !in value) value
        else Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()

    private val defaultQualities = listOf(LxQuality("128k"))
    private val defaultQualityMap = mapOf("128k" to LxQualityInfo())

    private const val NETEASE_COVER_MAGIC = "3go8&$8*3*3h0k(2)2"
}
