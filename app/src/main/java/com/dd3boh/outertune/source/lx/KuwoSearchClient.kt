package com.dd3boh.outertune.source.lx

import android.text.Html
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

object KuwoSearchClient {
    private const val SEARCH_URL = "https://search.kuwo.cn/r.s"
    private const val COVER_URL = "https://artistpicserver.kuwo.cn/pic.web"
    private const val SEARCH_CACHE_TTL_MS = 5 * 60 * 1000L
    private const val SEARCH_CACHE_SIZE = 20
    private val coverSizePrefix = Regex("""^\d+/""")

    private data class SearchCacheEntry(
        val createdAt: Long,
        val result: LxPage<LxMusicInfo>,
    )

    private val searchCache = object : LinkedHashMap<String, SearchCacheEntry>(SEARCH_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SearchCacheEntry>?): Boolean =
            size > SEARCH_CACHE_SIZE
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        defaultRequest {
            headers.append("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        }
    }

    suspend fun search(query: String, page: Int = 1, limit: Int = 15): Result<List<LxMusicInfo>> =
        searchPage(query, page, limit).map { it.items }

    suspend fun searchPage(query: String, page: Int = 1, limit: Int = 15): Result<LxPage<LxMusicInfo>> = runCatching {
        val normalizedQuery = query.trim()
        check(normalizedQuery.isNotEmpty()) { "搜索关键词不能为空" }
        require(page > 0 && limit in 1..15) { "搜索分页参数无效" }
        val cacheKey = "$normalizedQuery|$page|$limit"
        synchronized(searchCache) {
            searchCache[cacheKey]
                ?.takeIf { System.currentTimeMillis() - it.createdAt < SEARCH_CACHE_TTL_MS }
                ?.result
        }?.let { return@runCatching it }

        val response = client.get(SEARCH_URL) {
            parameter("client", "kt")
            parameter("all", normalizedQuery)
            parameter("pn", (page - 1).coerceAtLeast(0))
            parameter("rn", limit)
            parameter("uid", "794762570")
            parameter("ver", "kwplayer_ar_9.2.2.1")
            parameter("vipver", "1")
            parameter("show_copyright_off", "1")
            parameter("newver", "1")
            parameter("ft", "music")
            parameter("cluster", "0")
            parameter("strategy", "2012")
            parameter("encoding", "utf8")
            parameter("rformat", "json")
            parameter("vermerge", "1")
            parameter("mobi", "1")
            parameter("issubtitle", "1")
        }
        check(response.status.isSuccess()) { "酷我搜索请求失败：${response.status}" }
        val root = lxJson.parseToJsonElement(response.body<String>()) as? JsonObject
            ?: error("酷我搜索返回了无效数据")
        val items = root["abslist"] as? JsonArray ?: error("酷我搜索缺少歌曲列表")
        LxPage(
            items.mapNotNull { (it as? JsonObject)?.let(::parseMusicInfo) }.distinctBy { it.id },
            items.isNotEmpty() && (root.string("TOTAL").toLongOrNull()?.let { page.toLong() * limit < it }
                ?: (items.size >= limit)),
        ).also { result ->
            synchronized(searchCache) {
                searchCache[cacheKey] = SearchCacheEntry(System.currentTimeMillis(), result)
            }
        }
    }.onFailure { if (it is CancellationException) throw it }

    internal fun parseMusicInfo(item: JsonObject): LxMusicInfo? = item.toMusicInfo()

    private fun JsonObject.toMusicInfo(): LxMusicInfo? {
        val songId = string("MUSICRID", "id").removePrefix("MUSIC_").ifBlank { return null }
        val title = decode(string("SONGNAME", "NAME", "name"))
        val singer = decode(string("ARTIST", "artist"))
        val albumName = decode(string("ALBUM", "album"))
        val qualities = parseQualities(string("N_MINFO").ifBlank { string("MINFO") })
        return LxMusicInfo(
            id = "kw_$songId",
            songmid = songId,
            name = title,
            singer = singer,
            source = "kw",
            interval = string("DURATION", "duration").toIntOrNull()?.let { "%02d:%02d".format(it / 60, it % 60) },
            meta = LxMusicMeta(
                songId = songId,
                albumName = albumName,
                albumId = string("ALBUMID", "albumid").takeIf(String::isNotBlank),
                picUrl = directCoverUrl(),
                qualitys = qualities,
                _qualitys = qualities.associate { it.type to LxQualityInfo(it.size) },
            ),
        )
    }

    suspend fun loadCover(songId: String): String? = runCatching {
        val response = client.get(COVER_URL) {
            parameter("corp", "kuwo")
            parameter("type", "rid_pic")
            parameter("pictype", "500")
            parameter("size", "500")
            parameter("rid", songId)
        }
        response.body<String>().trim().takeIf { it.startsWith("http") }?.let(::normalizeCoverUrl)
    }.getOrNull()

    private fun JsonObject.directCoverUrl(): String? {
        string("albumpic").takeIf { it.startsWith("http") }?.let { return normalizeCoverUrl(it) }
        val albumCoverPath = string("web_albumpic_short")
            .replace(coverSizePrefix, "")
            .takeIf(String::isNotBlank)
        if (albumCoverPath != null) {
            return "https://img1.kuwo.cn/star/albumcover/500/$albumCoverPath"
        }
        val mvCoverUrl = string("hts_MVPIC")
            .takeIf(String::isNotBlank)
            ?.let(::normalizeCoverUrl)
        if (mvCoverUrl != null) return mvCoverUrl

        return string("web_artistpic_short")
            .replace(coverSizePrefix, "")
            .takeIf(String::isNotBlank)
            ?.let { "https://img1.kuwo.cn/star/starheads/500/$it" }
    }

    /**
     * 部分酷我封面地址使用 kwcdn 域名，但 Android/部分网络环境无法建立 HTTPS 连接。
     * 同一资源在 kuwo.cn 域名下可用，统一转换为 HTTPS 后再交给 Coil。
     */
    private fun normalizeCoverUrl(url: String): String = url
        .replaceFirst("http://", "https://")
        .replace(".kwcdn.kuwo.cn", ".kuwo.cn")

    private fun parseQualities(value: String): List<LxQuality> {
        val qualityOrder = listOf("128k", "320k", "flac", "flac24bit")
        val found = mutableMapOf<String, String?>()
        value.split(';').forEach { part ->
            val fields = part.split(',').associate { field ->
                field.substringBefore(':') to field.substringAfter(':', "")
            }
            val type = when (fields["bitrate"]?.toIntOrNull()) {
                128 -> "128k"
                320 -> "320k"
                2000 -> "flac"
                4000, 20900, 22000 -> "flac24bit"
                else -> null
            }
            if (type != null) found[type] = fields["size"]
        }
        if (found.isEmpty()) found["128k"] = null
        return qualityOrder.mapNotNull { type -> found[type]?.let { LxQuality(type, it) } ?: if (found.containsKey(type)) LxQuality(type) else null }
    }

    private fun JsonObject.string(vararg keys: String): String = keys.firstNotNullOfOrNull {
        (this[it] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
    }.orEmpty()

    private fun decode(value: String): String =
        if ('&' !in value && '<' !in value) value
        else Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
}
