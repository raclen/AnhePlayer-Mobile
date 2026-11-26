package com.dd3boh.outertune.source.lx

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

data class LxTopListInfo(
    val id: String,
    val name: String,
    val source: LxSearchSource,
    val description: String = "",
    val updateFrequency: String = "",
    val thumbnailUrl: String? = null,
    val songCount: Int? = null,
    val previewSongs: List<String> = emptyList(),
)

data class LxRecommendTag(
    val id: String,
    val name: String,
)

data class LxRecommendTagGroup(
    val title: String,
    val tags: List<LxRecommendTag>,
)

data class LxRecommendTagsResult(
    val pinned: List<LxRecommendTag>,
    val groups: List<LxRecommendTagGroup>,
)

/**
 * 免登录在线发现客户端（对齐 PC 端排行榜与热门歌单）。
 *
 * 所有接口均走公开无登录端点，返回标准洛雪来源（kg / wy / tx），
 * 播放与下载经由 LxSourceRuntime，不依赖酷狗概念版账号。
 */
object LxDiscoverClient {
    val supportedSources = listOf(LxSearchSource.KUGOU, LxSearchSource.NETEASE, LxSearchSource.QQ)

    private val api by lazy {
        LxDiscoverApi(
            request = { url, parameters, referer ->
                val response = LxPlaylistClient.client.get(url) {
                    parameters.forEach { (key, value) -> parameter(key, value) }
                    header("Referer", referer)
                }
                LxPlaylistHttpResponse(response.status.value, response.body<String>())
            },
            playlistSongs = LxPlaylistClient::songs,
            playlistSearch = LxPlaylistClient::search,
        )
    }

    fun supports(source: LxSearchSource): Boolean = source in supportedSources

    suspend fun topLists(source: LxSearchSource): Result<List<LxTopListInfo>> = api.topLists(source)

    suspend fun topListSongs(id: String, source: LxSearchSource, page: Int = 1): Result<LxPage<LxMusicInfo>> =
        api.topListSongs(id, source, page)

    suspend fun recommendTags(source: LxSearchSource): Result<LxRecommendTagsResult> = api.recommendTags(source)

    suspend fun recommendPlaylists(
        source: LxSearchSource,
        tagId: String = "",
        tagName: String = "",
        page: Int = 1,
    ): Result<LxPage<LxPlaylistInfo>> = api.recommendPlaylists(source, tagId, tagName, page)
}

private class DiscoverResponseException(message: String) : IllegalStateException(message)

internal class LxDiscoverApi(
    private val now: () -> Long = System::currentTimeMillis,
    private val playlistSongs: suspend (String, LxSearchSource, Int) -> Result<LxPage<LxMusicInfo>> = { _, _, _ ->
        Result.failure(DiscoverResponseException("歌单详情服务未配置"))
    },
    private val playlistSearch: suspend (String, LxSearchSource, Int) -> Result<LxPage<LxPlaylistInfo>> = { _, _, _ ->
        Result.failure(DiscoverResponseException("歌单搜索服务未配置"))
    },
    private val request: suspend (String, Map<String, String>, String) -> LxPlaylistHttpResponse,
) {
    private data class CacheEntry(val createdAt: Long, val value: Any)

    private val cache = object : LinkedHashMap<String, CacheEntry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean = size > 32
    }

    suspend fun topLists(source: LxSearchSource): Result<List<LxTopListInfo>> = safe(source, "排行榜") {
        validateSource(source)
        val cacheKey = "toplists:${source.sourceId}"
        cached<List<LxTopListInfo>>(cacheKey)?.let { return@safe it }
        val items = when (source) {
            LxSearchSource.KUGOU -> {
                val root = get(source, "https://mobiles.kugou.com/api/v3/rank/list", mapOf("version" to "9108", "plat" to "0"))
                val list = rows(root.obj("data") ?: invalid(source), "info", source, null)
                list.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val id = item.string("rankid", "id").takeIf(playlistIdPattern::matches) ?: return@mapNotNull null
                    val name = playlistText(item.string("rankname", "name")).takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val cover = playlistCover(item.string("imgurl", "img_cover", "bannerurl", "album_img_9"))
                    LxTopListInfo(
                        id = id,
                        name = name,
                        source = source,
                        description = playlistText(item.string("intro")),
                        updateFrequency = playlistText(item.string("update_frequency")),
                        thumbnailUrl = cover,
                        songCount = item.string("songcount", "total").toIntOrNull()?.takeIf { it >= 0 },
                    )
                }
            }
            LxSearchSource.NETEASE -> {
                val root = get(source, "https://music.163.com/api/toplist/detail", emptyMap())
                val list = rows(root, "list", source, null)
                list.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val id = item.string("id").takeIf(playlistIdPattern::matches) ?: return@mapNotNull null
                    val name = playlistText(item.string("name")).takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val previews = (item["tracks"] as? JsonArray).orEmpty().take(3).mapNotNull { track ->
                        val obj = track as? JsonObject ?: return@mapNotNull null
                        val first = playlistText(obj.string("first"))
                        val second = playlistText(obj.string("second"))
                        when {
                            first.isNotBlank() && second.isNotBlank() -> "$first - $second"
                            first.isNotBlank() -> first
                            else -> null
                        }
                    }
                    LxTopListInfo(
                        id = id,
                        name = name,
                        source = source,
                        description = playlistText(item.string("description")),
                        updateFrequency = playlistText(item.string("updateFrequency")),
                        thumbnailUrl = playlistCover(item.string("coverImgUrl")),
                        songCount = item.string("trackCount").toIntOrNull()?.takeIf { it >= 0 },
                        previewSongs = previews,
                    )
                }
            }
            LxSearchSource.QQ -> {
                val root = get(source, "https://c.y.qq.com/v8/fcg-bin/fcg_myqq_toplist.fcg", mapOf("format" to "json"))
                val list = rows(root.obj("data") ?: invalid(source), "topList", source, null)
                list.mapNotNull { element ->
                    val item = element as? JsonObject ?: return@mapNotNull null
                    val id = item.string("id").takeIf(playlistIdPattern::matches) ?: return@mapNotNull null
                    val name = playlistText(item.string("topTitle", "name")).takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val previews = (item["songList"] as? JsonArray).orEmpty().take(3).mapNotNull { track ->
                        val obj = track as? JsonObject ?: return@mapNotNull null
                        val songName = playlistText(obj.string("songname"))
                        val singerName = playlistText(obj.string("singername"))
                        when {
                            songName.isNotBlank() && singerName.isNotBlank() -> "$songName - $singerName"
                            songName.isNotBlank() -> songName
                            else -> null
                        }
                    }
                    LxTopListInfo(
                        id = id,
                        name = name,
                        source = source,
                        thumbnailUrl = playlistCover(item.string("picUrl")),
                        previewSongs = previews,
                    )
                }
            }
            else -> invalid(source)
        }.distinctBy { it.id }
        store(cacheKey, items)
        items
    }

    suspend fun topListSongs(id: String, source: LxSearchSource, page: Int = 1): Result<LxPage<LxMusicInfo>> {
        if (!LxDiscoverClient.supports(source)) {
            return Result.failure(DiscoverResponseException("暂不支持${source.displayName}排行榜"))
        }
        if (page < 1) {
            return Result.failure(DiscoverResponseException("排行榜页码必须大于零"))
        }
        val normalized = id.trim()
        if (!playlistIdPattern.matches(normalized)) {
            return Result.failure(DiscoverResponseException("排行榜编号无效"))
        }
        if (source == LxSearchSource.NETEASE) {
            return playlistSongs(normalized, LxSearchSource.NETEASE, page)
        }
        return safe(source, "排行榜") {
            val cacheKey = "toplist_songs:${source.sourceId}:$normalized:$page"
            cached<LxPage<LxMusicInfo>>(cacheKey)?.let { return@safe it }
            val result = when (source) {
                LxSearchSource.KUGOU -> {
                    val root = get(source, "https://mobiles.kugou.com/api/v3/rank/song", mapOf(
                        "rankid" to normalized,
                        "page" to "$page",
                        "pagesize" to "$PLAYLIST_LIMIT",
                        "version" to "9108",
                        "plat" to "0",
                    ))
                    val data = root.obj("data") ?: invalid(source)
                    val total = data.count("total")
                    remotePage(rows(data, "info", source, total), page, total, source, { it.id }, ::parseKugouSong)
                }
                LxSearchSource.QQ -> {
                    val root = get(source, "https://c.y.qq.com/v8/fcg-bin/fcg_v8_toplist_cp.fcg", mapOf(
                        "format" to "json",
                        "topid" to normalized,
                        "page" to "detail",
                        "type" to "top",
                        "song_begin" to "${offset(page)}",
                        "song_num" to "$PLAYLIST_LIMIT",
                    ))
                    val total = root.count("total_song_num")
                    val list = rows(root, "songlist", source, total)
                    remotePage(list, page, total, source, { it.id }) { entry ->
                        LxSearchClient.parseQqMusicInfo(entry.obj("data") ?: entry)
                    }
                }
                else -> invalid(source)
            }
            store(cacheKey, result)
            result
        }
    }

    suspend fun recommendTags(source: LxSearchSource): Result<LxRecommendTagsResult> = safe(source, "歌单分类") {
        validateSource(source)
        val cacheKey = "tags:${source.sourceId}"
        cached<LxRecommendTagsResult>(cacheKey)?.let { return@safe it }
        val result = when (source) {
            LxSearchSource.KUGOU -> {
                val root = get(source, "https://mobiles.kugou.com/api/v3/tag/list", mapOf("version" to "9108", "plat" to "0"))
                val rawGroups = rows(root.obj("data") ?: invalid(source), "info", source, null)
                val hot = mutableListOf<LxRecommendTag>()
                val groups = rawGroups.mapNotNull { groupEl ->
                    val group = groupEl as? JsonObject ?: return@mapNotNull null
                    val title = playlistText(group.string("name"))
                    val tags = (group["children"] as? JsonArray).orEmpty().mapNotNull { childEl ->
                        val child = childEl as? JsonObject ?: return@mapNotNull null
                        val name = playlistText(child.string("name")).takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val id = child.string("special_tag_id", "id").ifBlank { name }
                        val tag = LxRecommendTag(id = id, name = name)
                        val isHot = child.string("is_hot")
                        if (isHot == "1" || isHot.equals("true", ignoreCase = true)) {
                            hot += tag
                        }
                        tag
                    }.distinctBy { it.name }
                    if (tags.isEmpty()) null else LxRecommendTagGroup(title = title.ifBlank { "分类" }, tags = tags)
                }
                val pinned = (if (hot.isNotEmpty()) hot else groups.flatMap { it.tags }).distinctBy { it.name }.take(8)
                LxRecommendTagsResult(pinned = pinned, groups = groups)
            }
            LxSearchSource.NETEASE -> {
                val root = get(source, "https://music.163.com/api/playlist/hottags", emptyMap())
                val tags = rows(root, "tags", source, null).mapNotNull { tagEl ->
                    val obj = tagEl as? JsonObject ?: return@mapNotNull null
                    val name = playlistText(obj.string("name")).takeIf(String::isNotBlank) ?: return@mapNotNull null
                    LxRecommendTag(id = name, name = name)
                }.distinctBy { it.name }
                LxRecommendTagsResult(
                    pinned = tags.take(8),
                    groups = if (tags.isEmpty()) emptyList() else listOf(LxRecommendTagGroup("热门分类", tags)),
                )
            }
            LxSearchSource.QQ -> {
                val root = get(source, "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_tag_conf.fcg", mapOf(
                    "format" to "json",
                    "outCharset" to "utf-8",
                ))
                val categories = rows(root.obj("data") ?: invalid(source), "categories", source, null)
                val groups = categories.mapNotNull { groupEl ->
                    val group = groupEl as? JsonObject ?: return@mapNotNull null
                    val title = playlistText(group.string("categoryGroupName"))
                    val tags = (group["items"] as? JsonArray).orEmpty().mapNotNull { itemEl ->
                        val item = itemEl as? JsonObject ?: return@mapNotNull null
                        val id = item.string("categoryId").takeIf(String::isNotBlank) ?: return@mapNotNull null
                        val name = playlistText(item.string("categoryName")).takeIf(String::isNotBlank) ?: return@mapNotNull null
                        LxRecommendTag(id = id, name = name)
                    }.distinctBy { it.id }
                    if (tags.isEmpty()) null else LxRecommendTagGroup(title = title.ifBlank { "分类" }, tags = tags)
                }
                val pinned = groups.flatMap { it.tags }
                    .filterNot { it.id == "10000000" || it.name == "全部" }
                    .distinctBy { it.id }
                    .take(8)
                LxRecommendTagsResult(pinned = pinned, groups = groups)
            }
            else -> invalid(source)
        }
        store(cacheKey, result)
        result
    }

    suspend fun recommendPlaylists(
        source: LxSearchSource,
        tagId: String = "",
        tagName: String = "",
        page: Int = 1,
    ): Result<LxPage<LxPlaylistInfo>> {
        if (!LxDiscoverClient.supports(source)) {
            return Result.failure(DiscoverResponseException("暂不支持${source.displayName}热门歌单"))
        }
        if (page < 1) {
            return Result.failure(DiscoverResponseException("歌单页码必须大于零"))
        }
        if (source == LxSearchSource.KUGOU) {
            val keyword = tagName.trim().takeUnless { it.isBlank() || it == "全部" } ?: "热门"
            return playlistSearch(keyword, LxSearchSource.KUGOU, page)
        }
        return safe(source, "热门歌单") {
            val normalizedId = tagId.trim()
            val normalizedName = tagName.trim()
            val cacheKey = "playlists:${source.sourceId}:$normalizedId:$normalizedName:$page"
            cached<LxPage<LxPlaylistInfo>>(cacheKey)?.let { return@safe it }
            val result = when (source) {
                LxSearchSource.NETEASE -> {
                    val category = normalizedName.ifBlank { "全部" }
                    val root = get(source, "https://music.163.com/api/playlist/list", mapOf(
                        "cat" to category,
                        "order" to "hot",
                        "offset" to "${offset(page)}",
                        "limit" to "$PLAYLIST_LIMIT",
                        "total" to if (page == 1) "true" else "false",
                    ))
                    val total = root.count("total")
                    val list = rows(root, "playlists", source, total)
                    val pageResult = remotePage(list, page, total, source, { it.id }) { parsePlaylist(it, LxSearchSource.NETEASE) }
                    val moreFlag = root.string("more")
                    if (moreFlag.equals("false", ignoreCase = true)) pageResult.copy(hasMore = false) else pageResult
                }
                LxSearchSource.QQ -> {
                    val categoryId = normalizedId.takeIf { it.isNotBlank() && it != "all" } ?: "10000000"
                    val begin = offset(page)
                    val root = get(source, "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg", mapOf(
                        "format" to "json",
                        "outCharset" to "utf-8",
                        "categoryId" to categoryId,
                        "sortId" to "5",
                        "sin" to "$begin",
                        "ein" to "${begin + PLAYLIST_LIMIT - 1}",
                    ))
                    val data = root.obj("data") ?: invalid(source)
                    val total = data.count("sum")
                    remotePage(rows(data, "list", source, total), page, total, source, { it.id }) {
                        parsePlaylist(it, LxSearchSource.QQ)
                    }
                }
                else -> invalid(source)
            }
            store(cacheKey, result)
            result
        }
    }

    private suspend fun get(source: LxSearchSource, url: String, parameters: Map<String, String>): JsonObject {
        val referer = when (source) {
            LxSearchSource.NETEASE -> "https://music.163.com/"
            LxSearchSource.KUGOU -> "https://www.kugou.com/"
            LxSearchSource.QQ -> "https://y.qq.com/"
            else -> invalid(source)
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
            LxSearchSource.KUGOU -> if (root.string("status").let { it.isNotBlank() && it != "1" } ||
                root.string("errcode").let { it.isNotBlank() && it != "0" }) invalid(source)
            LxSearchSource.QQ -> if (root.string("code") != "0") invalid(source)
            LxSearchSource.KUWO, LxSearchSource.QISHUI -> invalid(source)
        }
        return root
    }

    private fun validateSource(source: LxSearchSource) {
        if (!LxDiscoverClient.supports(source)) {
            throw DiscoverResponseException("暂不支持${source.displayName}发现内容")
        }
    }

    private suspend fun <T : Any> safe(source: LxSearchSource, label: String, action: suspend () -> T): Result<T> = try {
        currentCoroutineContext().ensureActive()
        val value = withTimeoutOrNull(PLAYLIST_TIMEOUT_MS) { action() }
            ?: throw DiscoverResponseException("${source.displayName}${label}请求超时，请稍后重试")
        currentCoroutineContext().ensureActive()
        Result.success(value)
    } catch (error: CancellationException) {
        throw error
    } catch (error: DiscoverResponseException) {
        Result.failure(error)
    } catch (_: IllegalStateException) {
        Result.failure(DiscoverResponseException("${source.displayName}${label}服务暂不可用或返回了无效数据"))
    } catch (_: Exception) {
        Result.failure(DiscoverResponseException("${source.displayName}${label}请求失败，请检查网络或稍后重试"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> cached(key: String): T? = synchronized(cache) {
        cache[key]?.takeIf { now() - it.createdAt in 0 until 300_000L }?.value as? T
    }

    private suspend fun store(key: String, value: Any) {
        currentCoroutineContext().ensureActive()
        synchronized(cache) { cache[key] = CacheEntry(now(), value) }
    }
}
