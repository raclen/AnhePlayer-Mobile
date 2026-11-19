package com.dd3boh.outertune.source.lx

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.UUID
import kotlin.math.roundToInt

/** 官方公共目录搜索，播放和下载仍交给用户导入的洛雪脚本。 */
object QishuiSearchClient {
    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        }
    }
    private val api by lazy {
        QishuiSearchApi { method, url, requestHeaders, body ->
            val response = client.request(url) {
                this.method = HttpMethod.parse(method)
                headers { requestHeaders.forEach { (name, value) -> append(name, value) } }
                body?.let { setBody(it) }
            }
            QishuiHttpResponse(response.status.value, response.body<String>())
        }
    }

    suspend fun search(query: String, limit: Int = 15): Result<List<LxMusicInfo>> = api.search(query, limit)

    internal fun parseResponse(body: String, limit: Int = 15): List<LxMusicInfo> =
        parseSearchResponse(checkedResponse(QishuiHttpResponse(200, body)), limit)

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
}

internal data class QishuiHttpResponse(val status: Int, val body: String)

/** 注入传输与时钟，便于验证非 UI 协议；令牌与查询缓存均只存在于当前进程内存。 */
internal class QishuiSearchApi(
    private val now: () -> Long = System::currentTimeMillis,
    private val request: suspend (String, String, Map<String, String>, String?) -> QishuiHttpResponse,
) {
    private data class TokenEntry(val value: String, val expiresAt: Long)
    private data class CacheEntry(val createdAt: Long, val songs: List<LxMusicInfo>)

    private val random = SecureRandom()
    private val deviceId = "${random.nextInt(9) + 1}" + (1..18).joinToString("") { random.nextInt(10).toString() }
    private val tokenMutex = Mutex()
    private var tokenEntry: TokenEntry? = null
    private val cache = object : LinkedHashMap<String, CacheEntry>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > 20
    }

    suspend fun search(query: String, limit: Int = 15): Result<List<LxMusicInfo>> = runCatching {
        val normalizedQuery = query.trim()
        check(normalizedQuery.isNotEmpty()) { "搜索关键词不能为空" }
        val resultLimit = limit.coerceIn(1, 15)
        val cacheKey = "$normalizedQuery|$resultLimit"
        synchronized(cache) {
            cache[cacheKey]?.takeIf { now() - it.createdAt in 0 until 300_000L }?.songs
        }?.let { return@runCatching it }

        // 移动端当前只展示首屏，保持 15 条上限，不新增分页 UI 或逐首资源请求。
        val searchId = UUID.randomUUID().toString()
        val body = JsonObject(mapOf(
            "q" to JsonPrimitive(normalizedQuery),
            "cursor" to JsonPrimitive("0"),
            "search_id" to JsonPrimitive(searchId),
            "from_search_id" to JsonPrimitive(searchId),
            "search_method" to JsonPrimitive("input"),
            "search_type" to JsonPrimitive("track"),
            "search_scene" to JsonPrimitive("normal"),
            "scene_name" to JsonPrimitive("search"),
            "ab_param" to JsonPrimitive(""),
            "last_viewed_media" to JsonObject(emptyMap()),
            "common_params" to JsonObject(emptyMap()),
        )).toString()
        parseSearchResponse(searchRequest(body), resultLimit).also { songs ->
            synchronized(cache) { cache[cacheKey] = CacheEntry(now(), songs) }
        }
    }.onFailure { if (it is CancellationException) throw it }

    private suspend fun searchRequest(body: String): JsonObject {
        var entry = getToken()
        for (attempt in 0..1) {
            try {
                return call("POST", buildUrl("open.douyin.com", "/search/"), body, entry.value)
            } catch (error: QishuiResponseException) {
                if (error.code != 28001008) throw error
                // 旧并发请求不能清空已刷新的令牌；令牌失效最多重试一次。
                tokenMutex.withLock { if (tokenEntry === entry) tokenEntry = null }
                if (attempt == 1) throw error
                entry = getToken()
            }
        }
        error("汽水搜索客户端初始化失败，请稍后重试")
    }

    private suspend fun getToken(): TokenEntry = tokenMutex.withLock {
        tokenEntry?.takeIf { it.expiresAt > now() }?.let { return@withLock it }
        val response = call("GET", buildUrl("open.qishui.com", "/client/token/get/", mapOf("trigger_type" to "cold_start")))
        val value = (response["client_token"] as? JsonPrimitive)?.takeIf { it.isString }
            ?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw QishuiResponseException("汽水搜索客户端初始化失败，请稍后重试")
        val currentTime = now()
        val serverExpiry = response.string("expires_time").toLongOrNull()
            ?.takeIf { it in 1..(Long.MAX_VALUE / 1000) }?.times(1000)
        if (serverExpiry != null && serverExpiry <= currentTime) {
            throw QishuiResponseException("汽水搜索客户端返回已过期令牌，请稍后重试")
        }
        val expiresAt = serverExpiry?.minus(30_000)?.coerceAtLeast(currentTime) ?: (currentTime + 300_000)
        TokenEntry(value, minOf(expiresAt, currentTime + 3_600_000)).also { tokenEntry = it }
    }

    private suspend fun call(method: String, url: String, body: String? = null, token: String? = null): JsonObject {
        val headers = mutableMapOf("User-Agent" to "LunaOpenSDK/1.0.10")
        if (body != null) headers["Content-Type"] = "application/json; charset=UTF-8"
        if (token != null) headers["access-token"] = token
        val response = try {
            request(method, url, headers, body)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // 底层异常可能包含请求 URL 或 Header，不传到界面或日志。
            throw QishuiResponseException("汽水搜索服务连接失败，请检查网络或稍后重试")
        }
        return checkedResponse(response)
    }

    private fun buildUrl(host: String, path: String, extra: Map<String, String> = emptyMap()): String {
        val params = COMMON_PARAMS + mapOf(
            "device_id" to deviceId,
            "did" to deviceId,
            "iid" to deviceId,
            "vehicle_id" to "qishui-$deviceId",
            "_timestamp" to (now() / 1000).toString(),
            "_nonce" to random.nextInt(65536).toString(),
        ) + extra
        val query = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "https://$host/api/luna/v1/platform$path?$query"
    }

    companion object {
        private val COMMON_PARAMS = mapOf(
            "aid" to "765213", "app_id" to "765213", "app_name" to "byte_autoservice_lite",
            "version_code" to "100010", "version_name" to "1.0.10", "channel" to "bydSong",
            "sdk_aid" to "753999", "caller_name" to "luna_open_sdk",
            "sdk_version_code" to "100000100", "sdk_version_name" to "0.0.1", "sdk_channel" to "Test",
            "vehicle_type" to "BYD", "device_brand" to "BYD", "device_type" to "BYD",
            "device_platform" to "android", "resolution" to "1920x1080", "os_version" to "13", "ac" to "wifi",
        )
    }
}

private class QishuiResponseException(message: String, val code: Int? = null) : IllegalStateException(message)

private fun checkedResponse(response: QishuiHttpResponse): JsonObject {
    if (response.status !in 200..299) {
        throw QishuiResponseException("汽水搜索请求失败（HTTP ${response.status}），请稍后重试")
    }
    val root = try {
        lxJson.parseToJsonElement(response.body) as? JsonObject
    } catch (_: IllegalArgumentException) {
        null
    } ?: throw QishuiResponseException("汽水搜索接口返回空响应或无效数据，请稍后重试")
    val code = (root["err_no"] as? JsonPrimitive)?.intOrNull
        ?: throw QishuiResponseException("汽水搜索接口返回了无法识别的数据，请稍后重试")
    val nestedValue = (root["data"] as? JsonObject)?.get("error_code")
    val nestedCode = if (nestedValue == null) 0 else (nestedValue as? JsonPrimitive)?.intOrNull
        ?: throw QishuiResponseException("汽水搜索接口返回了无法识别的数据，请稍后重试")
    val errorCode = if (code != 0) code else nestedCode
    if (errorCode != 0) {
        throw QishuiResponseException("汽水搜索服务暂不可用（错误码 $errorCode），请稍后重试", errorCode)
    }
    return root
}

private fun parseSearchResponse(root: JsonObject, limit: Int): List<LxMusicInfo> {
    val groups = (root["data"] as? JsonObject)?.get("result_groups") as? JsonArray
        ?: throw QishuiResponseException("汽水搜索接口缺少结果列表，请稍后重试")
    val checkedGroups = groups.map { entry ->
        val item = entry as? JsonObject
            ?: throw QishuiResponseException("汽水搜索结果分组格式异常，请稍后重试")
        val id = (item["id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (id.isNullOrBlank()) throw QishuiResponseException("汽水搜索结果分组格式异常，请稍后重试")
        item
    }
    val group = checkedGroups.firstOrNull { it.string("id") == "tracks" } ?: return emptyList()
    val entries = group["data"] as? JsonArray
        ?: throw QishuiResponseException("汽水搜索结果格式异常，请稍后重试")
    return entries.map { entry ->
        val track = ((entry as? JsonObject)?.get("entity") as? JsonObject)?.get("track") as? JsonObject
            ?: throw QishuiResponseException("汽水搜索结果缺少歌曲信息，请稍后重试")
        track.toMusicInfo()
    }.distinctBy { it.id }.take(limit.coerceIn(1, 15))
}

private val searchTags = Regex("<[^>]+>")

private fun JsonObject.toMusicInfo(): LxMusicInfo {
    val base = this["base_info"] as? JsonObject
        ?: throw QishuiResponseException("汽水搜索结果缺少歌曲信息，请稍后重试")
    // JSON 整数也按原始文本取值，不经过 Double，避免 19 位歌曲 ID 精度丢失。
    val songId = base.string("id").takeIf(String::isNotBlank)
        ?: throw QishuiResponseException("汽水搜索结果缺少歌曲 ID，请稍后重试")
    val title = cleanText(base.string("name")).takeIf(String::isNotBlank)
        ?: throw QishuiResponseException("汽水搜索结果缺少歌曲名称，请稍后重试")
    val related = this["related_info"] as? JsonObject
    val artists = (related?.get("artist_links") as? JsonArray).orEmpty().mapNotNull { artist ->
        val item = artist as? JsonObject ?: return@mapNotNull null
        val name = cleanText(item.string("name")).takeIf(String::isNotBlank) ?: return@mapNotNull null
        JsonObject(mapOf("id" to JsonPrimitive(item.string("id")), "name" to JsonPrimitive(name)))
    }
    val singer = artists.joinToString(" / ") { it.string("name") }.ifBlank { "未知歌手" }
    val album = related?.get("album_link") as? JsonObject
    val albumName = cleanText(album?.string("name").orEmpty())
    val albumId = album?.string("id")?.takeIf(String::isNotBlank)
    val picUrl = QishuiSearchClient.coverUrl((this["display_info"] as? JsonObject)?.get("cover_url"))
    val duration = base.string("duration_ms").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        ?.div(1000)?.takeIf { it <= Int.MAX_VALUE }
    val seconds = duration?.roundToInt()
    val interval = seconds?.let { "%02d:%02d".format(it / 60, it % 60) }
    // 映射为现有脚本兼容的 track 形状，只保留目录字段；player_info 等播放凭据不得持久化。
    val raw = JsonObject(buildMap {
        put("id", JsonPrimitive(songId))
        put("name", JsonPrimitive(title))
        put("artists", JsonArray(artists))
        put("singer", JsonPrimitive(singer))
        put("albumName", JsonPrimitive(albumName))
        put("album", JsonObject(buildMap {
            put("name", JsonPrimitive(albumName))
            albumId?.let { put("id", JsonPrimitive(it)) }
            picUrl?.let { put("url_cover", JsonPrimitive(it)) }
        }))
        duration?.let { put("duration", JsonPrimitive(it)) }
        interval?.let { put("interval", JsonPrimitive(it)) }
    })
    return LxMusicInfo(
        id = "qs_$songId", songmid = songId, name = title, singer = singer,
        source = LxSearchSource.QISHUI.sourceId, interval = interval,
        meta = LxMusicMeta(songId = songId, albumName = albumName, albumId = albumId, picUrl = picUrl),
        // 汽水 bit_rates 不冒充洛雪 qualitys，仍由脚本决定播放音质。
        raw = raw,
    )
}

private fun JsonObject.string(key: String): String =
    (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun cleanText(value: String): String = value.replace(searchTags, "")
    .replace("&nbsp;", " ").replace("&amp;", "&").trim()
