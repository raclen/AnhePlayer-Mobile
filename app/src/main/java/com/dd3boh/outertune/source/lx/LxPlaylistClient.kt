package com.dd3boh.outertune.source.lx

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class LxPlaylistInfo(
    val id: String,
    val name: String,
    val source: LxSearchSource,
    val creator: String = "",
    val songCount: Int? = null,
    val thumbnailUrl: String? = null,
)

data class LxPage<T>(val items: List<T>, val hasMore: Boolean)

/** 只读取公开歌单元数据，歌曲取流仍经过洛雪边界，不访问概念版账号。 */
object LxPlaylistClient {
    private val client by lazy {
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = PLAYLIST_TIMEOUT_MS
                connectTimeoutMillis = PLAYLIST_TIMEOUT_MS
                socketTimeoutMillis = PLAYLIST_TIMEOUT_MS
            }
            defaultRequest {
                headers.append("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            }
        }
    }
    private val api by lazy {
        LxPlaylistApi { url, parameters, referer ->
            val response = client.get(url) {
                parameters.forEach { (key, value) -> parameter(key, value) }
                header("Referer", referer)
            }
            LxPlaylistHttpResponse(response.status.value, response.body<String>())
        }
    }

    fun supports(source: LxSearchSource): Boolean = when (source) {
        LxSearchSource.KUWO, LxSearchSource.NETEASE, LxSearchSource.KUGOU, LxSearchSource.QQ -> true
        LxSearchSource.QISHUI -> false
    }

    suspend fun search(query: String, source: LxSearchSource, page: Int = 1): Result<LxPage<LxPlaylistInfo>> =
        api.search(query, source, page)

    suspend fun songs(id: String, source: LxSearchSource, page: Int = 1): Result<LxPage<LxMusicInfo>> =
        api.songs(id, source, page)
}

private const val PLAYLIST_LIMIT = 15
private const val PLAYLIST_TIMEOUT_MS = 15_000L
private val playlistIdPattern = Regex("[1-9][0-9]*")
private class PlaylistResponseException(message: String) : IllegalStateException(message)
internal data class LxPlaylistHttpResponse(val status: Int, val body: String)

/** 注入 HTTP 和时钟以验证协议；缓存只保存元数据，整个操作最多等待 15 秒。 */
internal class LxPlaylistApi(
    private val now: () -> Long = System::currentTimeMillis,
    private val request: suspend (String, Map<String, String>, String) -> LxPlaylistHttpResponse,
) {
    private data class CacheKey(val source: LxSearchSource, val value: String, val page: Int, val songs: Boolean)
    private data class CacheEntry(val createdAt: Long, val value: LxPage<*>)
    private val cache = object : LinkedHashMap<CacheKey, CacheEntry>(20, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean = size > 20
    }

    suspend fun search(query: String, source: LxSearchSource, page: Int = 1): Result<LxPage<LxPlaylistInfo>> = safe(source) {
        validateSourceAndPage(source, page)
        val normalized = query.trim()
        if (normalized.isEmpty()) throw PlaylistResponseException("搜索关键词不能为空")
        val key = CacheKey(source, normalized, page, false)
        cached<LxPlaylistInfo>(key)?.let { return@safe it }
        val offset = offset(page)
        val root = when (source) {
            LxSearchSource.KUWO -> get(source, "https://search.kuwo.cn/r.s", mapOf(
                "all" to normalized, "ft" to "playlist", "itemset" to "new2012", "client" to "kt",
                "pn" to (page - 1).toString(), "rn" to "$PLAYLIST_LIMIT", "rformat" to "json",
                "encoding" to "utf8", "mobi" to "1",
            ))
            LxSearchSource.NETEASE -> get(source, "https://music.163.com/api/search/get/web", mapOf(
                "s" to normalized, "type" to "1000", "offset" to "$offset", "total" to "true",
                "limit" to "$PLAYLIST_LIMIT", "csrf_token" to "",
            ))
            LxSearchSource.KUGOU -> get(source, "https://mobiles.kugou.com/api/v3/search/special", mapOf(
                "keyword" to normalized, "page" to "$page", "pagesize" to "$PLAYLIST_LIMIT", "format" to "json",
            ))
            LxSearchSource.QQ -> get(source, "https://c.y.qq.com/soso/fcgi-bin/client_music_search_songlist", mapOf(
                "query" to normalized, "page_no" to (page - 1).toString(), "num_per_page" to "$PLAYLIST_LIMIT",
                "remoteplace" to "txt.yqq.playlist", "format" to "json", "inCharset" to "utf8",
                "outCharset" to "utf-8", "platform" to "yqq", "needNewCode" to "0",
            ))
            LxSearchSource.QISHUI -> invalid(source)
        }
        val container: JsonObject
        val rowsKey: String
        val total: Long?
        when (source) {
            LxSearchSource.KUWO -> {
                container = root
                rowsKey = "abslist"
                total = root.count("TOTAL")
            }
            LxSearchSource.NETEASE -> {
                container = root.obj("result") ?: invalid(source)
                rowsKey = "playlists"
                total = container.count("playlistCount")
            }
            LxSearchSource.KUGOU -> {
                container = root.obj("data") ?: invalid(source)
                rowsKey = "info"
                total = container.count("total")
            }
            LxSearchSource.QQ -> {
                container = root.obj("data") ?: invalid(source)
                rowsKey = "list"
                total = container.count("sum")
            }
            LxSearchSource.QISHUI -> invalid(source)
        }
        remotePage(rows(container, rowsKey, source, total), page, total, source, { it.id }) {
            parsePlaylist(it, source)
        }.also { store(key, it) }
    }

    suspend fun songs(id: String, source: LxSearchSource, page: Int = 1): Result<LxPage<LxMusicInfo>> = safe(source) {
        validateSourceAndPage(source, page)
        val normalized = id.trim()
        if (!playlistIdPattern.matches(normalized)) throw PlaylistResponseException("歌单编号无效")
        val key = CacheKey(source, normalized, page, true)
        cached<LxMusicInfo>(key)?.let { return@safe it }
        val result = when (source) {
            LxSearchSource.KUWO -> {
                val root = get(source, "https://nplserver.kuwo.cn/pl.svc", mapOf(
                    "op" to "getlistinfo", "pid" to normalized, "pn" to (page - 1).toString(),
                    "rn" to "$PLAYLIST_LIMIT", "encode" to "utf8", "keyset" to "pl2012", "vipver" to "1", "newver" to "1",
                ))
                if (root.string("result") != "ok") invalid(source)
                val total = root.count("total")
                remotePage(rows(root, "musiclist", source, total), page, total, source, { it.id }, KuwoSearchClient::parseMusicInfo)
            }
            LxSearchSource.NETEASE -> neteaseSongs(normalized, page)
            LxSearchSource.KUGOU -> {
                val root = get(source, "https://mobiles.kugou.com/api/v3/special/song", mapOf(
                    "specialid" to normalized, "page" to "$page", "pagesize" to "$PLAYLIST_LIMIT", "format" to "json",
                ))
                val data = root.obj("data") ?: invalid(source)
                val total = data.count("total")
                remotePage(rows(data, "info", source, total), page, total, source, { it.id }, ::parseKugouSong)
            }
            LxSearchSource.QQ -> {
                val root = get(source, "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg", mapOf(
                    "disstid" to normalized, "type" to "1", "json" to "1", "utf8" to "1", "onlysong" to "0",
                    "format" to "json", "g_tk" to "5381", "loginUin" to "0", "hostUin" to "0", "inCharset" to "utf8",
                    "outCharset" to "utf-8", "notice" to "0", "platform" to "yqq", "needNewCode" to "0",
                    "song_begin" to "${offset(page)}", "song_num" to "$PLAYLIST_LIMIT",
                ))
                val playlist = (root["cdlist"] as? JsonArray)?.firstOrNull() as? JsonObject ?: invalid(source)
                if (playlist.string("disstid") != normalized) invalid(source)
                val total = playlist.count("songnum")
                remotePage(rows(playlist, "songlist", source, total), page, total, source, { it.id }, LxSearchClient::parseQqMusicInfo)
            }
            LxSearchSource.QISHUI -> invalid(source)
        }
        store(key, result)
        result
    }

    private suspend fun neteaseSongs(id: String, page: Int): LxPage<LxMusicInfo> {
        val source = LxSearchSource.NETEASE
        val root = get(source, "https://music.163.com/api/v6/playlist/detail", mapOf("id" to id, "n" to "100000", "s" to "0"))
        val playlist = root.obj("playlist") ?: invalid(source)
        if (playlist.string("id") != id) invalid(source)
        val ids = (playlist["trackIds"] as? JsonArray)?.map {
            val songId = (it as? JsonObject)?.string("id").orEmpty()
            if (!playlistIdPattern.matches(songId)) invalid(source)
            songId
        } ?: invalid(source)
        // 匿名详情只有前 10 首元数据；以完整 trackIds 切页，不能把 tracks 当成整个歌单。
        if ((playlist.count("trackCount") ?: ids.size.toLong()) > ids.size) invalid(source)
        val begin = offset(page)
        if (begin >= ids.size) return LxPage(emptyList(), false)
        val pageIds = ids.drop(begin.toInt()).take(PLAYLIST_LIMIT)
        val catalog = (playlist["tracks"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }.associateBy { it.string("id") }.toMutableMap()
        val missing = pageIds.filterNot(catalog::containsKey).distinct()
        if (missing.isNotEmpty()) {
            // 每页最多一次批量补歌曲目录，不逐首请求封面、歌词或播放地址。
            val details = get(source, "https://music.163.com/api/song/detail/", mapOf(
                "ids" to missing.joinToString(prefix = "[", postfix = "]", separator = ","),
            ))
            val tracks = details["songs"] as? JsonArray ?: invalid(source)
            tracks.mapNotNull { it as? JsonObject }.forEach { catalog[it.string("id")] = it }
        }
        val songs = pageIds.mapNotNull { songId -> catalog[songId]?.let(LxSearchClient::parseNeteaseMusicInfo) }
            .filter { it.name.isNotBlank() }.distinctBy { it.id }
        if (songs.map { it.songmid }.toSet() != pageIds.toSet()) {
            throw PlaylistResponseException("网易云歌单本页歌曲信息不完整，请稍后重试")
        }
        return LxPage(songs, begin + pageIds.size < ids.size)
    }

    private suspend fun get(source: LxSearchSource, url: String, parameters: Map<String, String>): JsonObject {
        val referer = when (source) {
            LxSearchSource.KUWO -> "https://www.kuwo.cn/"
            LxSearchSource.NETEASE -> "https://music.163.com/"
            LxSearchSource.KUGOU -> "https://www.kugou.com/"
            LxSearchSource.QQ -> "https://y.qq.com/"
            LxSearchSource.QISHUI -> invalid(source)
        }
        val response = request(url, parameters, referer)
        if (response.status !in 200..299 || response.body.isBlank()) invalid(source)
        val root = try {
            lxJson.parseToJsonElement(response.body) as? JsonObject ?: invalid(source)
        } catch (_: IllegalArgumentException) {
            invalid(source)
        }
        when (source) {
            LxSearchSource.NETEASE -> if (root.string("code") != "200") invalid(source)
            LxSearchSource.KUGOU -> if (root.string("status") != "1" || root.string("errcode").let { it.isNotBlank() && it != "0" }) invalid(source)
            LxSearchSource.QQ -> if (root.string("code") != "0") invalid(source)
            LxSearchSource.KUWO -> {
                if (root.string("result").let { it.isNotBlank() && it != "ok" }) invalid(source)
                if (root.string("code").let { it.isNotBlank() && it != "200" && it != "0" }) invalid(source)
            }
            LxSearchSource.QISHUI -> invalid(source)
        }
        return root
    }

    private suspend fun <T : Any> safe(source: LxSearchSource, action: suspend () -> T): Result<T> = try {
        currentCoroutineContext().ensureActive()
        val value = withTimeoutOrNull(PLAYLIST_TIMEOUT_MS) { action() }
            ?: throw PlaylistResponseException("${source.displayName}歌单请求超时，请稍后重试")
        currentCoroutineContext().ensureActive()
        Result.success(value)
    } catch (error: CancellationException) {
        throw error
    } catch (error: PlaylistResponseException) {
        Result.failure(error)
    } catch (_: Exception) {
        Result.failure(PlaylistResponseException("${source.displayName}歌单请求失败，请检查网络或稍后重试"))
    }

    private fun validateSourceAndPage(source: LxSearchSource, page: Int) {
        if (!LxPlaylistClient.supports(source)) throw PlaylistResponseException("暂不支持${source.displayName}在线歌单")
        if (page < 1) throw PlaylistResponseException("歌单页码必须大于零")
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: CacheKey): LxPage<T>? = synchronized(cache) {
        // 键同时包含来源、操作和页码，两个类型的分页结果不会互相覆盖。
        cache[key]?.takeIf { now() - it.createdAt in 0 until 300_000L }?.value as? LxPage<T>
    }

    private suspend fun store(key: CacheKey, value: LxPage<*>) {
        currentCoroutineContext().ensureActive()
        synchronized(cache) { cache[key] = CacheEntry(now(), value) }
    }
}

private fun offset(page: Int): Long = (page.toLong() - 1) * PLAYLIST_LIMIT
private fun invalid(source: LxSearchSource): Nothing =
    throw PlaylistResponseException("${source.displayName}歌单服务暂不可用或返回了无效数据")

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.string(vararg keys: String): String = keys.firstNotNullOfOrNull {
    (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
}.orEmpty()
private fun JsonObject.count(key: String): Long? = string(key).toLongOrNull()?.takeIf { it >= 0 }
private fun rows(root: JsonObject, key: String, source: LxSearchSource, total: Long?): JsonArray =
    root[key] as? JsonArray ?: if (root[key] == null && total == 0L) JsonArray(emptyList()) else invalid(source)

private fun <T> remotePage(
    rows: JsonArray, page: Int, total: Long?, source: LxSearchSource, identity: (T) -> String, parse: (JsonObject) -> T?,
): LxPage<T> {
    val items = rows.take(PLAYLIST_LIMIT).mapNotNull { (it as? JsonObject)?.let(parse) }.distinctBy(identity)
    if (rows.isNotEmpty() && items.isEmpty()) invalid(source)
    if (rows.isEmpty() && total != null && total > offset(page)) invalid(source)
    // 使用上游页跨度/总数，而非去重后条数；QQ 可能一页只返回 13 条但仍有下一页。
    val hasMore = rows.isNotEmpty() && (total?.let { page.toLong() * PLAYLIST_LIMIT < it } ?: (rows.size >= PLAYLIST_LIMIT))
    return LxPage(items, hasMore)
}

private fun parsePlaylist(item: JsonObject, source: LxSearchSource): LxPlaylistInfo? {
    val id = when (source) {
        LxSearchSource.KUWO -> item.string("playlistid")
        LxSearchSource.NETEASE -> item.string("id")
        LxSearchSource.KUGOU -> item.string("specialid")
        LxSearchSource.QQ -> item.string("dissid")
        LxSearchSource.QISHUI -> return null
    }.takeIf(playlistIdPattern::matches) ?: return null
    val name = playlistText(item.string("name", "specialname", "dissname")).takeIf(String::isNotBlank) ?: return null
    val creator = when (source) {
        LxSearchSource.NETEASE -> item.obj("creator")?.string("nickname").orEmpty()
        LxSearchSource.QQ -> item.obj("creator")?.string("name").orEmpty()
        else -> item.string("nickname", "singername")
    }
    return LxPlaylistInfo(
        id = id, name = name, source = source, creator = playlistText(creator),
        songCount = item.string("songnum", "trackCount", "songcount", "song_count").toIntOrNull()?.takeIf { it >= 0 },
        thumbnailUrl = playlistCover(item.string("hts_pic", "pic", "coverImgUrl", "imgurl")),
    )
}

private fun parseKugouSong(item: JsonObject): LxMusicInfo? {
    val filename = playlistText(item.string("filename"))
    val separator = filename.indexOf(" - ")
    val title = item.string("songname", "audio_name").ifBlank { if (separator > 0) filename.substring(separator + 3).trim() else filename }
    val singer = item.string("singername", "author_name").ifBlank { if (separator > 0) filename.substring(0, separator).trim() else "" }
    if (title.isBlank()) return null
    return LxSearchClient.parseKugouMusicInfo(JsonObject(item + mapOf(
        "songname" to JsonPrimitive(title), "singername" to JsonPrimitive(singer),
        "album_name" to JsonPrimitive(item.string("album_name", "albumname", "remark")),
        "image" to JsonPrimitive(item.string("image", "album_sizable_cover", "imgurl", "img")),
    )))
}

private fun playlistCover(value: String): String? {
    val url = value.trim().replace("{size}", "480").replace(".kwcdn.kuwo.cn", ".kuwo.cn")
        .let { if (it.startsWith("//")) "https:$it" else it.replaceFirst("http://", "https://") }
    return url.takeIf { it.startsWith("https://") && runCatching { java.net.URI(it).host?.isNotBlank() == true }.getOrDefault(false) }
}

private val playlistTags = Regex("<[^>]*>")
private val playlistEntities = Regex("&(?:amp|lt|gt|quot|apos|nbsp|#\\d+|#x[0-9a-fA-F]+);")
private fun playlistText(value: String): String = playlistEntities.replace(value.replace(playlistTags, "")) { match ->
    when (val entity = match.value) {
        "&amp;" -> "&"
        "&lt;" -> "<"
        "&gt;" -> ">"
        "&quot;" -> "\""
        "&apos;" -> "'"
        "&nbsp;" -> " "
        else -> {
            val code = if (entity.startsWith("&#x")) entity.substring(3, entity.length - 1).toIntOrNull(16)
            else entity.substring(2, entity.length - 1).toIntOrNull()
            code?.takeIf { Character.isValidCodePoint(it) && it !in 0xD800..0xDFFF }
                ?.let { String(Character.toChars(it)) } ?: ""
        }
    }
}.trim()
