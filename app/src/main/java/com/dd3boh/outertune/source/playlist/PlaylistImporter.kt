package com.dd3boh.outertune.source.playlist

import android.text.Html
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.lx.LxMusicInfo
import com.dd3boh.outertune.source.lx.LxMusicMeta
import com.dd3boh.outertune.source.lx.LxQuality
import com.dd3boh.outertune.source.lx.LxQualityInfo
import com.dd3boh.outertune.source.lx.lxJson
import com.dd3boh.outertune.source.lx.toMediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class PlaylistPlatform(val displayName: String, val sourceId: String) {
    AUTO("自动识别", ""),
    NETEASE("网易云音乐", "wy"),
    QQ("QQ 音乐", "tx"),
    KUGOU("酷狗音乐", "kg"),
    KUWO("酷我音乐", "kw");

    companion object {
        val selectablePlatforms = listOf(AUTO, NETEASE, QQ, KUGOU, KUWO)
    }
}

data class ImportedPlaylist(
    val title: String,
    val coverUrl: String? = null,
    val platform: PlaylistPlatform,
    val songs: List<MediaMetadata>,
)

object PlaylistImporter {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val defaultQualities = listOf(LxQuality("128k"))
    private val defaultQualityMap = mapOf("128k" to LxQualityInfo())

    suspend fun importPlaylist(
        rawInput: String,
        specifiedPlatform: PlaylistPlatform = PlaylistPlatform.AUTO,
    ): ImportedPlaylist = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("输入内容不能为空")
        }

        val resolvedUrlOrText = resolveShareUrl(trimmed)
        val platform = if (specifiedPlatform != PlaylistPlatform.AUTO) {
            specifiedPlatform
        } else {
            detectPlatform(resolvedUrlOrText)
        }

        if (platform == PlaylistPlatform.AUTO) {
            throw IllegalArgumentException("未能自动识别歌单平台，请手动选择平台")
        }

        val playlistId = parsePlaylistId(resolvedUrlOrText, platform)
            ?: throw IllegalArgumentException("未能识别出 ${platform.displayName} 的歌单 ID，请检查链接或输入")

        when (platform) {
            PlaylistPlatform.NETEASE -> importNetease(playlistId)
            PlaylistPlatform.QQ -> importQq(playlistId)
            PlaylistPlatform.KUGOU -> importKugou(playlistId)
            PlaylistPlatform.KUWO -> importKuwo(playlistId)
            PlaylistPlatform.AUTO -> error("Unreachable")
        }
    }

    private fun resolveShareUrl(input: String): String {
        val urlRegex = Regex("""https?://[^\s"'<>，。；、\u4e00-\u9fa5]+""")
        val match = urlRegex.find(input)?.value ?: return input
        return try {
            val request = Request.Builder()
                .url(match)
                .header("User-Agent", USER_AGENT)
                .build()
            httpClient.newCall(request).execute().use { response ->
                response.request.url.toString()
            }
        } catch (_: Exception) {
            match
        }
    }

    fun detectPlatform(text: String): PlaylistPlatform {
        val lower = text.lowercase()
        return when {
            "163.com" in lower || "163cn.tv" in lower || "netease" in lower -> PlaylistPlatform.NETEASE
            "qq.com" in lower || "y.qq.com" in lower -> PlaylistPlatform.QQ
            "kugou.com" in lower -> PlaylistPlatform.KUGOU
            "kuwo.cn" in lower -> PlaylistPlatform.KUWO
            else -> PlaylistPlatform.AUTO
        }
    }

    fun parsePlaylistId(text: String, platform: PlaylistPlatform): String? {
        val trimmed = text.trim()
        if (trimmed.matches(Regex("""^\d{4,}$"""))) {
            return trimmed
        }

        return when (platform) {
            PlaylistPlatform.NETEASE -> {
                extractParam(trimmed, "id", "playlistId")
                    ?: Regex("""/playlist(?:/detail)?/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
            }
            PlaylistPlatform.QQ -> {
                extractParam(trimmed, "disstid", "id", "dirid")
                    ?: Regex("""/playlist/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
                    ?: Regex("""/n/ryqq/playlist/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
            }
            PlaylistPlatform.KUGOU -> {
                extractParam(trimmed, "specialid", "global_collection_id", "id")
                    ?: Regex("""/special/single/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
                    ?: Regex("""/plist/list/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
                    ?: Regex("""/playlist/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
                    ?: Regex("""collection_[\w-]+""", RegexOption.IGNORE_CASE).find(trimmed)?.value
            }
            PlaylistPlatform.KUWO -> {
                extractParam(trimmed, "pid", "playlistId", "id")
                    ?: Regex("""/playlist(?:_detail)?/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
                    ?: Regex("""/play_detail/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)
            }
            PlaylistPlatform.AUTO -> null
        }
    }

    private fun extractParam(url: String, vararg paramNames: String): String? {
        for (name in paramNames) {
            val match = Regex("""[?&#]${name}=([^&#]+)""", RegexOption.IGNORE_CASE).find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    // ── 网易云音乐 ──
    private suspend fun importNetease(playlistId: String): ImportedPlaylist {
        val url = "https://music.163.com/api/v6/playlist/detail?id=$playlistId&n=1000&s=0"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", "os=pc")
            .build()

        val jsonString = executeGet(request)
        val root = parseJson(jsonString)
        val playlistObj = root.obj("playlist")
        if (playlistObj.isEmpty()) {
            throw IOException("网易云歌单获取失败或歌单不存在")
        }

        val title = cleanText(playlistObj.string("name")).ifBlank { "网易云歌单 $playlistId" }
        val coverUrl = playlistObj.string("coverImgUrl").toHttpsOrNull()

        val tracks = playlistObj.rows("tracks")
        val trackIds = playlistObj.rows("trackIds").mapNotNull { it.number("id")?.toString() }

        val musicList = mutableListOf<MediaMetadata>()
        tracks.forEach { track ->
            mapNeteaseSong(track)?.let { musicList.add(it.toMediaMetadata()) }
        }

        // 如果歌单歌曲超过 1000 首，对后续歌曲分批拉取详情
        if (trackIds.size > tracks.size) {
            val remainingIds = trackIds.drop(tracks.size)
            val chunks = remainingIds.chunked(500)
            for (chunk in chunks) {
                val detailMap = fetchNeteaseSongDetails(chunk)
                for (id in chunk) {
                    val detail = detailMap[id] ?: continue
                    mapNeteaseSong(detail)?.let { musicList.add(it.toMediaMetadata()) }
                }
            }
        }

        if (musicList.isEmpty()) {
            throw IOException("该歌单暂无歌曲或无法读取")
        }

        return ImportedPlaylist(
            title = title,
            coverUrl = coverUrl,
            platform = PlaylistPlatform.NETEASE,
            songs = musicList,
        )
    }

    private suspend fun fetchNeteaseSongDetails(ids: List<String>): Map<String, JsonObject> {
        if (ids.isEmpty()) return emptyMap()
        val idsParam = ids.joinToString(prefix = "[", postfix = "]") { it }
        val url = "https://music.163.com/api/song/detail/?ids=$idsParam"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://music.163.com/")
            .build()
        return try {
            val json = parseJson(executeGet(request))
            val songs = json.rows("songs")
            songs.associateBy { it.string("id") }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun mapNeteaseSong(song: JsonObject): LxMusicInfo? {
        val songId = song.string("id").ifBlank { return null }
        val album = song.obj("al").ifEmpty { song.obj("album") }
        val name = cleanText(song.string("name"))
        val singer = song.rows("ar").ifEmpty { song.rows("artists") }
            .mapNotNull { cleanText(it.string("name")).takeIf(String::isNotBlank) }
            .joinToString(" / ")
            .ifBlank { "未知歌手" }

        val durationMs = song.number("dt") ?: (song.number("duration") ?: 0L)
        val durationSec = (durationMs / 1000).toInt()
        val interval = "%02d:%02d".format(durationSec / 60, durationSec % 60)
        val albumName = cleanText(album.string("name"))
        val albumId = album.string("id").takeIf(String::isNotBlank)
        val picUrl = album.string("picUrl").toHttpsOrNull()

        return LxMusicInfo(
            id = "wy_$songId",
            songmid = songId,
            name = name,
            singer = singer,
            source = "wy",
            interval = interval,
            musicId = songId,
            meta = LxMusicMeta(
                songId = songId,
                albumName = albumName,
                albumId = albumId,
                picUrl = picUrl,
                qualitys = defaultQualities,
                _qualitys = defaultQualityMap,
            ),
        )
    }

    // ── QQ 音乐 ──
    private suspend fun importQq(playlistId: String): ImportedPlaylist {
        val url = "https://c.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?disstid=$playlistId&type=1&json=1&utf8=1&onlysong=0&format=json&g_tk=5381&loginUin=0&hostUin=0&inCharset=utf8&outCharset=utf-8&notice=0&platform=yqq&needNewCode=0"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://y.qq.com/")
            .build()

        val jsonString = executeGet(request)
        val root = parseJson(jsonString)
        val cdlist = root.rows("cdlist")
        if (cdlist.isEmpty()) {
            throw IOException("QQ 音乐歌单获取失败或歌单不存在")
        }

        val cd = cdlist[0]
        val title = cleanText(cd.string("dissname")).ifBlank { "QQ 音乐歌单 $playlistId" }
        val coverUrl = cd.string("logo").toHttpsOrNull()
        val songlist = cd.rows("songlist")

        val musicList = songlist.mapNotNull { item ->
            val songMid = item.string("songmid").ifBlank { return@mapNotNull null }
            val name = cleanText(item.string("songname", "name"))
            val singer = item.rows("singer")
                .mapNotNull { cleanText(it.string("name")).takeIf(String::isNotBlank) }
                .joinToString(" / ")
                .ifBlank { "未知歌手" }

            val intervalSec = item.number("interval")?.toInt() ?: 0
            val interval = "%02d:%02d".format(intervalSec / 60, intervalSec % 60)
            val albumName = cleanText(item.string("albumname"))
            val albumMid = item.string("albummid")
            val picUrl = if (albumMid.isNotBlank()) {
                "https://y.gtimg.cn/music/photo_new/T002R300x300M000$albumMid.jpg"
            } else null

            LxMusicInfo(
                id = "tx_$songMid",
                songmid = songMid,
                name = name,
                singer = singer,
                source = "tx",
                interval = interval,
                mid = songMid,
                meta = LxMusicMeta(
                    songId = songMid,
                    albumName = albumName,
                    albumId = item.string("albumid").takeIf(String::isNotBlank),
                    picUrl = picUrl,
                    qualitys = defaultQualities,
                    _qualitys = defaultQualityMap,
                ),
            ).toMediaMetadata()
        }

        if (musicList.isEmpty()) {
            throw IOException("QQ 音乐歌单暂无歌曲或读取失败")
        }

        return ImportedPlaylist(
            title = title,
            coverUrl = coverUrl,
            platform = PlaylistPlatform.QQ,
            songs = musicList,
        )
    }

    // ── 酷狗音乐 ──
    private suspend fun importKugou(playlistId: String): ImportedPlaylist {
        // 先获取歌单基本信息（名称、封面）
        var title = "酷狗歌单 $playlistId"
        var coverUrl: String? = null

        try {
            val infoUrl = "http://mobilecdn.kugou.com/api/v3/special/info?specialid=$playlistId"
            val infoReq = Request.Builder()
                .url(infoUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.kugou.com/")
                .build()
            val infoJson = parseJson(executeGet(infoReq))
            val infoData = infoJson.obj("data")
            val name = cleanText(infoData.string("specialname"))
            if (name.isNotBlank()) title = name
            coverUrl = infoData.string("imgurl").replace("{size}", "480").toHttpsOrNull()
        } catch (_: Exception) {}

        val musicList = mutableListOf<MediaMetadata>()
        var page = 1
        var total = Int.MAX_VALUE

        while (musicList.size < total && page <= 20) {
            val songUrl = "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$playlistId&page=$page&pagesize=200&plat=0&version=9108"
            val songReq = Request.Builder()
                .url(songUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://www.kugou.com/")
                .build()

            val songJson = parseJson(executeGet(songReq))
            val data = songJson.obj("data")
            total = data.number("total")?.toInt() ?: total
            val list = data.rows("info")
            if (list.isEmpty()) break

            list.forEach { item ->
                val hash = item.string("hash", "HASH").ifBlank { return@forEach }
                val parsedFilename = splitKugouFilename(item.string("filename"))
                val songName = cleanText(item.string("songname", "audio_name").ifBlank { parsedFilename.title })
                val singerName = cleanText(item.string("singername", "author_name").ifBlank { parsedFilename.artist })
                    .ifBlank { "未知歌手" }

                val durationSec = item.number("duration")?.toInt() ?: 0
                val interval = "%02d:%02d".format(durationSec / 60, durationSec % 60)
                val transParam = item.obj("trans_param")
                val artworkRaw = transParam.string("union_cover").ifBlank {
                    item.string("album_sizable_cover", "imgurl", "img")
                }
                val artwork = artworkRaw.replace("{size}", "480").toHttpsOrNull()

                val info = LxMusicInfo(
                    id = "kg_$hash",
                    songmid = hash,
                    name = songName,
                    singer = singerName,
                    source = "kg",
                    interval = interval,
                    hash = hash,
                    meta = LxMusicMeta(
                        songId = hash,
                        albumName = cleanText(item.string("album_name", "albumname", "remark")),
                        albumId = item.string("album_id").takeIf(String::isNotBlank),
                        picUrl = artwork,
                        qualitys = defaultQualities,
                        _qualitys = defaultQualityMap,
                    ),
                )
                musicList.add(info.toMediaMetadata())
            }

            page++
        }

        if (musicList.isEmpty()) {
            throw IOException("酷狗歌单暂无歌曲或读取失败")
        }

        return ImportedPlaylist(
            title = title,
            coverUrl = coverUrl,
            platform = PlaylistPlatform.KUGOU,
            songs = musicList,
        )
    }

    private data class KugouFilename(val artist: String, val title: String)

    private fun splitKugouFilename(value: String): KugouFilename {
        val clean = cleanText(value)
        val match = Regex("""^(.+?)\s+-\s+(.+)$""").find(clean)
        return if (match != null) {
            KugouFilename(match.groupValues[1].trim(), match.groupValues[2].trim())
        } else {
            KugouFilename("", clean)
        }
    }

    // ── 酷我音乐 ──
    private suspend fun importKuwo(playlistId: String): ImportedPlaylist {
        val url = "http://nplserver.kuwo.cn/pl.svc?op=getlistinfo&pid=$playlistId&pn=0&rn=1000&encode=utf8&keyset=pl2012&identity=kuwo&pcmp4=1&vipver=MUSIC_9.1.1.2_BCS2&newver=1"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "https://www.kuwo.cn/")
            .build()

        val jsonString = executeGet(request)
        val root = parseJson(jsonString)
        val title = cleanText(root.string("title")).ifBlank { "酷我歌单 $playlistId" }
        val coverUrl = root.string("pic").toHttpsOrNull()
        val musiclist = root.rows("musiclist")

        val musicList = musiclist.mapNotNull { item ->
            val rid = item.string("id", "MUSICRID").removePrefix("MUSIC_").ifBlank { return@mapNotNull null }
            val name = cleanText(item.string("name", "SONGNAME"))
            val singer = cleanText(item.string("artist", "ARTIST")).ifBlank { "未知歌手" }
            val album = cleanText(item.string("album", "ALBUM"))
            val durationSec = item.number("duration", "DURATION")?.toInt() ?: 0
            val interval = "%02d:%02d".format(durationSec / 60, durationSec % 60)
            val albumpic = item.string("albumpic", "web_albumpic_short")
            val picUrl = if (albumpic.startsWith("http", ignoreCase = true)) {
                albumpic.toHttpsOrNull()
            } else if (albumpic.isNotBlank()) {
                "https://img4.kuwo.cn/star/albumcover/$albumpic"
            } else null

            LxMusicInfo(
                id = "kw_$rid",
                songmid = rid,
                name = name,
                singer = singer,
                source = "kw",
                interval = interval,
                musicId = rid,
                meta = LxMusicMeta(
                    songId = rid,
                    albumName = album,
                    albumId = item.string("albumid").takeIf(String::isNotBlank),
                    picUrl = picUrl,
                    qualitys = defaultQualities,
                    _qualitys = defaultQualityMap,
                ),
            ).toMediaMetadata()
        }

        if (musicList.isEmpty()) {
            throw IOException("酷我歌单暂无歌曲或读取失败")
        }

        return ImportedPlaylist(
            title = title,
            coverUrl = coverUrl,
            platform = PlaylistPlatform.KUWO,
            songs = musicList,
        )
    }

    private fun executeGet(request: Request): String {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP 请求失败：${response.code}")
            }
            return response.body.string()
        }
    }

    private fun parseJson(content: String): JsonObject {
        val element = lxJson.parseToJsonElement(content)
        return element as? JsonObject ?: JsonObject(emptyMap())
    }

    private fun cleanText(value: String): String {
        val trimmed = value.trim()
        return if ('&' in trimmed || '<' in trimmed) {
            Html.fromHtml(trimmed, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        } else {
            trimmed
        }
    }

    private fun String.toHttpsOrNull(): String? =
        takeIf(String::isNotBlank)?.replaceFirst("http://", "https://")

    private fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())

    private fun JsonObject.rows(vararg keys: String): List<JsonObject> =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }
        }.orEmpty()

    private fun JsonObject.string(vararg keys: String): String =
        keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.contentOrNull
        }.orEmpty().trim()

    private fun JsonObject.number(vararg keys: String): Long? =
        keys.firstNotNullOfOrNull { key ->
            this[key]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        }
}
