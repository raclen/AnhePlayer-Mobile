package com.dd3boh.outertune.source.lx

import android.text.Html
import android.util.Base64
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.MessageDigest

/** 搜索元数据适配层，播放地址始终由洛雪 JS 音源解析。 */
object LxSearchClient {
    internal const val RESULT_LIMIT = 15
    private const val KUGOU_SEARCH_URL = "https://mobiles.kugou.com/api/v3/search/song"
    private const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get/web"
    private const val QQ_SEARCH_URL = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp"

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        defaultRequest {
            headers.append("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            headers.append("Referer", "https://y.qq.com/")
        }
    }

    suspend fun search(query: String, source: LxSearchSource): Result<List<LxMusicInfo>> =
        searchPage(query, source).map { it.items }

    suspend fun searchPage(query: String, source: LxSearchSource, page: Int = 1): Result<LxPage<LxMusicInfo>> = runCatching {
        val normalizedQuery = query.trim()
        check(normalizedQuery.isNotEmpty()) { "搜索关键词不能为空" }
        require(page > 0) { "搜索页码无效" }
        when (source) {
            LxSearchSource.KUGOU -> searchKugou(normalizedQuery, page)
            LxSearchSource.NETEASE -> searchNetease(normalizedQuery, page)
            LxSearchSource.QQ -> searchQq(normalizedQuery, page)
            LxSearchSource.KUWO -> KuwoSearchClient.searchPage(normalizedQuery, page, RESULT_LIMIT).getOrThrow()
            // 公共 SDK 的现有目录协议只验证了首屏，不伪造后续分页请求。
            LxSearchSource.QISHUI -> {
                check(page == 1) { "汽水暂不支持加载更多" }
                LxPage(QishuiSearchClient.search(normalizedQuery, RESULT_LIMIT).getOrThrow(), false)
            }
        }
    }.onFailure { if (it is CancellationException) throw it }

    suspend fun loadCover(item: LxMusicInfo): String? {
        item.meta.picUrl?.let { return it }
        return when (item.source) {
            LxSearchSource.KUWO.sourceId -> KuwoSearchClient.loadCover(item.meta.songId)
            else -> null
        }
    }

    private suspend fun searchKugou(query: String, page: Int): LxPage<LxMusicInfo> {
        val response = client.get(KUGOU_SEARCH_URL) {
            parameter("format", "json")
            parameter("keyword", query)
            parameter("page", page)
            parameter("pagesize", RESULT_LIMIT)
            parameter("showtype", 1)
        }
        check(response.status.isSuccess()) { "酷狗搜索请求失败：${response.status.value}" }
        val root = lxJson.parseToJsonElement(response.body<String>()).jsonObjectOrError()
        check(root.string("status") == "1") { "酷狗搜索服务暂不可用" }
        val data = root.getObject("data") ?: error("酷狗搜索返回了无效数据")
        val items = data.getArray("info") ?: error("酷狗搜索缺少歌曲列表")
        return songPage(items, page, data.string("total").toLongOrNull(), ::parseKugouMusicInfo)
    }

    internal fun parseKugouMusicInfo(item: JsonObject): LxMusicInfo? {
        val hash = item.string("hash").ifBlank { return null }
        return LxMusicInfo(
            id = "kg_$hash", songmid = hash,
            name = decode(item.string("songname")), singer = decode(item.string("singername")),
            source = LxSearchSource.KUGOU.sourceId,
            interval = item.string("duration").toIntOrNull()?.toDuration(), hash = hash,
            meta = LxMusicMeta(
                songId = hash, albumName = decode(item.string("album_name")),
                albumId = item.string("album_id").takeIf(String::isNotBlank),
                picUrl = (item.string("image").ifBlank {
                    item.getObject("trans_param")?.string("union_cover").orEmpty()
                }).replace("{size}", "480").toHttpsOrNull(),
                qualitys = defaultQualities, _qualitys = defaultQualityMap,
            ),
        )
    }

    private suspend fun searchNetease(query: String, page: Int): LxPage<LxMusicInfo> {
        val response = client.get(NETEASE_SEARCH_URL) {
            parameter("s", query)
            parameter("type", 1)
            parameter("offset", (page - 1) * RESULT_LIMIT)
            parameter("total", true)
            parameter("limit", RESULT_LIMIT)
            parameter("csrf_token", "")
        }
        check(response.status.isSuccess()) { "网易云搜索请求失败：${response.status.value}" }
        val root = lxJson.parseToJsonElement(response.body<String>()).jsonObjectOrError()
        check(root.string("code") == "200") { "网易云搜索服务暂不可用" }
        val data = root.getObject("result") ?: error("网易云搜索返回了无效数据")
        val items = data.getArray("songs") ?: if (data.string("songCount") == "0") JsonArray(emptyList())
            else error("网易云搜索缺少歌曲列表")
        return songPage(items, page, data.string("songCount").toLongOrNull(), ::parseNeteaseMusicInfo)
    }

    internal fun parseNeteaseMusicInfo(item: JsonObject): LxMusicInfo? {
        val songId = item.string("id").ifBlank { return null }
        val album = item.getObject("album") ?: item.getObject("al")
        return LxMusicInfo(
            id = "wy_$songId", songmid = songId, name = decode(item.string("name")),
            singer = item.getArray("artists", "ar").orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("name")?.takeIf(String::isNotBlank) }.joinToString(" / "),
            source = LxSearchSource.NETEASE.sourceId,
            interval = item.string("duration", "dt").toLongOrNull()?.div(1000)?.toInt()?.toDuration(),
            musicId = songId,
            meta = LxMusicMeta(
                songId = songId, albumName = album?.string("name").orEmpty(),
                albumId = album?.string("id")?.takeIf(String::isNotBlank),
                picUrl = album?.string("picUrl")?.toHttpsOrNull() ?: album?.string("picId")?.let(::neteaseCoverUrl),
                qualitys = defaultQualities, _qualitys = defaultQualityMap,
            ),
        )
    }

    private suspend fun searchQq(query: String, page: Int): LxPage<LxMusicInfo> {
        val response = client.get(QQ_SEARCH_URL) {
            parameter("p", page)
            parameter("n", RESULT_LIMIT)
            parameter("w", query)
            parameter("format", "json")
        }
        check(response.status.isSuccess()) { "QQ 音乐搜索请求失败：${response.status.value}" }
        val root = lxJson.parseToJsonElement(response.body<String>()).jsonObjectOrError()
        check(root.string("code") == "0") { "QQ 音乐搜索服务暂不可用" }
        val data = root.getObject("data")?.getObject("song") ?: error("QQ 音乐搜索返回了无效数据")
        val items = data.getArray("list") ?: error("QQ 音乐搜索缺少歌曲列表")
        return songPage(items, page, data.string("totalnum").toLongOrNull(), ::parseQqMusicInfo)
    }

    internal fun parseQqMusicInfo(item: JsonObject): LxMusicInfo? {
        val songMid = item.string("songmid").ifBlank { return null }
        val albumMid = item.string("albummid")
        return LxMusicInfo(
            id = "tx_$songMid", songmid = songMid, name = decode(item.string("songname")),
            singer = item.getArray("singer").orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("name")?.takeIf(String::isNotBlank) }.joinToString(" / "),
            source = LxSearchSource.QQ.sourceId,
            interval = item.string("interval").toIntOrNull()?.toDuration(), mid = songMid,
            meta = LxMusicMeta(
                songId = songMid, albumName = decode(item.string("albumname")),
                albumId = item.string("albumid").takeIf(String::isNotBlank),
                picUrl = albumMid.takeIf(String::isNotBlank)?.let { "https://y.gtimg.cn/music/photo_new/T002R500x500M000$it.jpg" },
                qualitys = defaultQualities, _qualitys = defaultQualityMap,
            ),
        )
    }

    internal fun songPage(
        items: JsonArray, page: Int, total: Long?, parse: (JsonObject) -> LxMusicInfo?,
    ): LxPage<LxMusicInfo> = LxPage(
        items.mapNotNull { (it as? JsonObject)?.let(parse) }.distinctBy { it.id },
        // 分页按上游原始条数推进，不能因无效项或去重后不足 15 条就截断后续结果。
        items.isNotEmpty() && (total?.let { page.toLong() * RESULT_LIMIT < it } ?: (items.size >= RESULT_LIMIT)),
    )

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrError(): JsonObject =
        this as? JsonObject ?: error("搜索接口返回了无效数据")

    private fun JsonObject.getObject(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.getArray(vararg keys: String): JsonArray? = keys.firstNotNullOfOrNull { this[it] as? JsonArray }
    private fun JsonObject.string(vararg keys: String): String =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()
    private fun Int.toDuration() = "%02d:%02d".format(this / 60, this % 60)
    private fun String.toHttpsOrNull(): String? = takeIf(String::isNotBlank)?.replaceFirst("http://", "https://")

    /** 网易云搜索接口只返回 picId，按官方 CDN 的编码规则生成封面地址。 */
    private fun neteaseCoverUrl(picId: String): String? {
        val id = picId.toLongOrNull()?.takeIf { it > 0 }?.toString() ?: return null
        val source = id.toByteArray(Charsets.UTF_8)
        val magic = NETEASE_COVER_MAGIC.toByteArray(Charsets.UTF_8)
        source.forEachIndexed { index, value -> source[index] = (value.toInt() xor magic[index % magic.size].toInt()).toByte() }
        val encoded = Base64.encodeToString(MessageDigest.getInstance("MD5").digest(source), Base64.NO_WRAP)
            .replace('/', '_').replace('+', '-')
        return "https://p3.music.126.net/$encoded/$id.jpg"
    }

    private fun decode(value: String): String = if ('&' !in value && '<' !in value) value
        else Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
    private val defaultQualities = listOf(LxQuality("128k"))
    private val defaultQualityMap = mapOf("128k" to LxQualityInfo())
    private const val NETEASE_COVER_MAGIC = "3go8&$8*3*3h0k(2)2"
}
