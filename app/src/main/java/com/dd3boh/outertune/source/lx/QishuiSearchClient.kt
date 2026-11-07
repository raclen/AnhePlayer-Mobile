package com.dd3boh.outertune.source.lx

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.math.roundToInt

/** 与桌面端共用汽水官方 App 搜索协议，播放和下载仍交给用户导入的洛雪脚本。 */
object QishuiSearchClient {
    private const val SEARCH_URL = "https://api.qishui.com/luna/search/track"
    private const val CACHE_TTL_MS = 5 * 60 * 1000L
    private const val CACHE_SIZE = 20
    private val searchTags = Regex("<[^>]+>")

    private data class CacheEntry(val createdAt: Long, val songs: List<LxMusicInfo>)

    private val cache = object : LinkedHashMap<String, CacheEntry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > CACHE_SIZE
    }

    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        }
    }

    // 保持与 AnhePlayer 最新汽水提交一致的客户端参数，避免接口返回 empty_search。
    private val apiParams = mapOf(
        "device_platform" to "android",
        "os" to "android",
        "ssmix" to "a",
        "cdid" to "46556f98-1720-4248-83da-62b74b60b46a",
        "channel" to "official",
        "aid" to "386088",
        "app_name" to "luna",
        "version_code" to "100198030",
        "version_name" to "19.8.0",
        "manifest_version_code" to "100198030",
        "update_version_code" to "100198030",
        "resolution" to "1080*1920",
        "dpi" to "480",
        "device_type" to "ABR-AL80",
        "device_brand" to "HUAWEI",
        "language" to "zh",
        "os_api" to "35",
        "os_version" to "15",
        "ac" to "wifi",
        "device_model" to "ABR-AL80",
        "package" to "com.luna.music",
        "device_id" to "2204957404565290",
        "iid" to "2204957404569386",
    )

    suspend fun search(query: String, limit: Int = 15): Result<List<LxMusicInfo>> = runCatching {
        val normalizedQuery = query.trim()
        check(normalizedQuery.isNotEmpty()) { "搜索关键词不能为空" }
        val resultLimit = limit.coerceIn(1, 15)
        val cacheKey = "$normalizedQuery|$resultLimit"
        synchronized(cache) {
            cache[cacheKey]?.takeIf { System.currentTimeMillis() - it.createdAt < CACHE_TTL_MS }?.songs
        }?.let { return@runCatching it }

        val response = client.get(SEARCH_URL) {
            apiParams.forEach { (key, value) -> parameter(key, value) }
            parameter("_rticket", System.currentTimeMillis().toString())
            parameter("q", normalizedQuery)
            parameter("cursor", "0")
            parameter("count", resultLimit.toString())
            headers {
                append("User-Agent", "com.luna.music/100198030 (Linux; U; Android 15; zh_CN_#Hans; ABR-AL80; Build/V417IR;tt-ok/3.12.13.19)")
                append("Content-Type", "application/json; charset=UTF-8")
                append("X-Argus", "=")
                append("x-common-params-v2", "channel=official&aid=386088&device_id=2081836196178571")
            }
        }
        check(response.status.isSuccess()) { "汽水搜索请求失败：${response.status}" }
        parseResponse(response.body<String>(), resultLimit).also { songs ->
            synchronized(cache) { cache[cacheKey] = CacheEntry(System.currentTimeMillis(), songs) }
        }
    }.onFailure { if (it is CancellationException) throw it }

    internal fun parseResponse(body: String, limit: Int = 15): List<LxMusicInfo> {
        val root = lxJson.parseToJsonElement(body) as? JsonObject ?: error("汽水搜索响应格式错误")
        val status = root["status_info"] as? JsonObject
        val code = status?.string("code")?.ifBlank { root.string("status_code") }.orEmpty()
        check(code.isBlank() || code == "0") {
            "汽水搜索失败：${status?.string("message")?.takeIf(String::isNotBlank) ?: code}"
        }
        val groups = root["result_groups"] as? JsonArray ?: return emptyList()
        return groups.asSequence()
            .flatMap { ((it as? JsonObject)?.get("data") as? JsonArray).orEmpty().asSequence() }
            .mapNotNull { entry ->
                val item = entry as? JsonObject ?: return@mapNotNull null
                val entity = item["entity"] as? JsonObject
                ((entity?.get("track") ?: item["track"]) as? JsonObject)?.toMusicInfo()
            }
            .distinctBy { it.id }
            // 官方接口可能忽略 count 返回 20 项，移动端仍限制首屏为 15 项。
            .take(limit.coerceIn(1, 15))
            .toList()
    }

    private fun JsonObject.toMusicInfo(): LxMusicInfo? {
        val songId = string("id").ifBlank { return null }
        val title = cleanText(string("name").ifBlank { string("title") }).ifBlank { return null }
        val album = this["album"] as? JsonObject
        val artistInfo = this["artists"] ?: this["author_info"]
        val singer = when (artistInfo) {
            is JsonArray -> artistInfo.mapNotNull { artist ->
                when (artist) {
                    is JsonPrimitive -> artist.contentOrNull
                    is JsonObject -> artist.string("name").ifBlank {
                        artist.string("singername").ifBlank { artist.string("author_name") }
                    }
                    else -> null
                }?.let(::cleanText)?.takeIf(String::isNotBlank)
            }.joinToString(" / ")
            is JsonPrimitive -> cleanText(artistInfo.contentOrNull.orEmpty())
            else -> ""
        }.ifBlank { "未知歌手" }
        val duration = string("duration").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
            ?.let { if (it > 10_000) it / 1000 else it }?.roundToInt()
        return LxMusicInfo(
            id = "qs_$songId",
            songmid = songId,
            name = title,
            singer = singer,
            source = LxSearchSource.QISHUI.sourceId,
            interval = duration?.let { "%02d:%02d".format(it / 60, it % 60) },
            meta = LxMusicMeta(
                songId = songId,
                albumName = cleanText(album?.string("name").orEmpty()),
                albumId = album?.string("id")?.takeIf(String::isNotBlank),
                picUrl = coverUrl(album?.get("url_cover")) ?: coverUrl(this["cover_url"]),
                // 汽水 bit_rates 使用独立音质枚举，不冒充洛雪 qualitys，由脚本负责映射。
            ),
            raw = JsonObject(this + ("id" to JsonPrimitive(songId))),
        )
    }

    internal fun coverUrl(cover: JsonElement?): String? {
        if (cover is JsonPrimitive) return cover.contentOrNull?.takeIf { it.startsWith("http") }
        val info = cover as? JsonObject ?: return null
        val uri = info.string("uri")
        val template = info.string("template_prefix")
        if (uri.isNotBlank() && template.isNotBlank()) {
            return "https://p3-luna.douyinpic.com/img/$uri~$template-resize:960:960.png"
        }
        val first = (info["urls"] as? JsonArray)?.firstOrNull()
            ?.let { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.startsWith("http") } ?: return null
        return if (uri.isBlank() || first.contains(uri)) first else "$first$uri"
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun cleanText(value: String): String = value.replace(searchTags, "")
        .replace("&nbsp;", " ").replace("&amp;", "&").trim()
}
