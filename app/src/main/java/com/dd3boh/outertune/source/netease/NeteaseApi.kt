package com.dd3boh.outertune.source.netease

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 网易云音乐 API 客户端
 *
 * 实现了二维码登录、状态轮询、用户信息获取、每日推荐歌曲、私人雷达以及私人漫游。
 */
object NeteaseApi {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val NETEASE_ORIGIN = "https://music.163.com"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun postWeapi(path: String, data: JSONObject, cookie: String = ""): Pair<JSONObject, List<String>> {
        // 自动注入 csrf_token
        if (!data.has("csrf_token")) {
            val csrf = extractCookieValue(cookie, "__csrf")
            data.put("csrf_token", csrf)
        }

        val encrypted = NeteaseCrypto.encryptWeapi(data.toString())
        val body = encrypted.toFormBody().toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType())

        val reqBuilder = Request.Builder()
            .url(NETEASE_ORIGIN + path)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", "$NETEASE_ORIGIN/")
            .header("Origin", NETEASE_ORIGIN)

        if (cookie.isNotBlank()) {
            reqBuilder.header("Cookie", cookie)
        }

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val setCookies = response.headers("Set-Cookie")
            val respBody = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != 301) {
                throw IOException("HTTP ${response.code}: $respBody")
            }
            val json = runCatching { JSONObject(respBody) }.getOrElse {
                JSONObject().put("code", response.code).put("raw", respBody)
            }
            return Pair(json, setCookies)
        }
    }

    private fun extractCookieValue(cookieHeader: String, name: String): String {
        val regex = Regex("""(?:^|;\s*)$name=([^;]*)""")
        return regex.find(cookieHeader)?.groupValues?.get(1).orEmpty()
    }

    /**
     * 生成登录二维码的 unikey
     */
    suspend fun createQrKey(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (json, _) = postWeapi("/weapi/login/qrcode/unikey", JSONObject().put("type", 1))
            val unikey = json.optString("unikey")
            if (unikey.isBlank()) {
                throw IOException(json.optString("message", "获取二维码 Key 失败"))
            }
            unikey
        }
    }

    /**
     * 检查二维码扫码状态
     */
    suspend fun checkQrLogin(unikey: String): QrLoginStatus = withContext(Dispatchers.IO) {
        try {
            val data = JSONObject().put("key", unikey).put("type", 1)
            val (json, setCookies) = postWeapi("/weapi/login/qrcode/client/login", data)
            val code = json.optInt("code", -1)

            when (code) {
                800 -> QrLoginStatus.Expired
                801 -> QrLoginStatus.WaitingScan
                802 -> QrLoginStatus.WaitingConfirm
                803 -> {
                    // 授权成功，从 Set-Cookie 中提取完整的 cookie，或者读取 body 里的 cookie 字段
                    val cookiesFromHeader = setCookies.joinToString("; ") { it.substringBefore(";") }
                    val cookieFromBody = json.optString("cookie")
                    val combinedCookie = buildString {
                        if (cookiesFromHeader.isNotBlank()) append(cookiesFromHeader)
                        if (cookieFromBody.isNotBlank()) {
                            if (isNotEmpty()) append("; ")
                            append(cookieFromBody)
                        }
                    }
                    QrLoginStatus.Success(combinedCookie)
                }
                else -> {
                    val msg = json.optString("message", "未知状态码: $code")
                    QrLoginStatus.Error(msg)
                }
            }
        } catch (e: Exception) {
            QrLoginStatus.Error(e.message ?: "网络请求失败")
        }
    }

    /**
     * 获取当前账号资料
     */
    suspend fun getProfile(cookie: String): Result<NeteaseAccountProfile> = withContext(Dispatchers.IO) {
        runCatching {
            if (cookie.isBlank()) throw IOException("未登录")
            val (json, _) = postWeapi("/weapi/w/nuser/account/get", JSONObject(), cookie)
            val profile = json.optJSONObject("profile")
            val account = json.optJSONObject("account")
            val userId = profile?.optString("userId")?.takeIf { it.isNotBlank() }
                ?: account?.optString("id")
                ?: throw IOException("未能识别网易云账号 UID，请重新登录")

            NeteaseAccountProfile(
                userId = userId,
                nickname = profile?.optString("nickname").orEmpty().trim(),
                avatarUrl = profile?.optString("avatarUrl").orEmpty().replace("http://", "https://"),
                vipType = profile?.optInt("vipType", 0) ?: 0,
                signature = profile?.optString("signature").orEmpty().trim()
            )
        }
    }

    /**
     * 获取每日推荐歌曲
     */
    suspend fun getDailyRecommendSongs(cookie: String): Result<List<NeteaseSongItem>> = withContext(Dispatchers.IO) {
        runCatching {
            if (cookie.isBlank()) throw IOException("需要登录网易云音乐后查看每日推荐")
            val data = JSONObject().put("offset", 0).put("total", true).put("limit", 30)
            val (json, _) = postWeapi("/weapi/v3/discovery/recommend/songs", data, cookie)
            val dailySongsArray = json.optJSONObject("data")?.optJSONArray("dailySongs")
                ?: json.optJSONArray("recommend")
                ?: throw IOException(json.optString("message", "获取每日推荐歌曲失败"))

            val list = mutableListOf<NeteaseSongItem>()
            for (i in 0 until dailySongsArray.length()) {
                val songObj = dailySongsArray.optJSONObject(i) ?: continue
                parseSong(songObj)?.let { list.add(it) }
            }
            list
        }
    }

    /**
     * 获取私人漫游（私人 FM）歌曲
     */
    suspend fun getPrivateFmSongs(cookie: String): Result<List<NeteaseSongItem>> = withContext(Dispatchers.IO) {
        runCatching {
            if (cookie.isBlank()) throw IOException("需要登录网易云音乐后收听私人漫游")
            val data = JSONObject().put("offset", 0).put("total", true).put("limit", 30)
            val (json, _) = postWeapi("/weapi/v1/radio/get", data, cookie)
            val songsArray = json.optJSONArray("data")
                ?: json.optJSONObject("data")?.optJSONArray("songs")
                ?: json.optJSONArray("songs")
                ?: throw IOException("获取私人漫游歌曲失败")

            val list = mutableListOf<NeteaseSongItem>()
            for (i in 0 until songsArray.length()) {
                val item = songsArray.optJSONObject(i) ?: continue
                val songObj = item.optJSONObject("songInfo") ?: item.optJSONObject("song") ?: item
                parseSong(songObj)?.let { list.add(it) }
            }
            list
        }
    }

    /**
     * 获取私人雷达歌单及歌曲
     */
    suspend fun getPrivateRadar(cookie: String): Result<NeteaseRadarResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (cookie.isBlank()) throw IOException("需要登录网易云音乐后探索私人雷达")

            var radarPlaylistId: String? = null
            var radarTitle = "私人雷达"
            var radarDesc = "根据你的音乐口味定制，每天更新"
            var radarCover: String? = null

            // 1. 先在每日推荐歌单里找「私人雷达」
            try {
                val (recJson, _) = postWeapi("/weapi/v1/discovery/recommend/resource", JSONObject(), cookie)
                val recList = recJson.optJSONArray("recommend")
                if (recList != null) {
                    for (i in 0 until recList.length()) {
                        val pl = recList.optJSONObject(i) ?: continue
                        val name = pl.optString("name")
                        if (name.contains("私人雷达")) {
                            radarPlaylistId = pl.optString("id")
                            radarTitle = name
                            radarDesc = pl.optString("copywriter").ifBlank { pl.optString("description") }
                            radarCover = pl.optString("picUrl").ifBlank { pl.optString("coverImgUrl") }
                            break
                        }
                    }
                }
            } catch (_: Exception) {
            }

            // 2. 找不到时从用户歌单找
            if (radarPlaylistId.isNullOrBlank()) {
                val profile = getProfile(cookie).getOrNull()
                if (profile != null && profile.userId.isNotBlank()) {
                    try {
                        val userPlData = JSONObject().put("uid", profile.userId).put("offset", 0).put("limit", 100)
                        val (userPlJson, _) = postWeapi("/weapi/user/playlist", userPlData, cookie)
                        val plArray = userPlJson.optJSONArray("playlist")
                        if (plArray != null) {
                            for (i in 0 until plArray.length()) {
                                val pl = plArray.optJSONObject(i) ?: continue
                                val name = pl.optString("name")
                                if (name.contains("私人雷达")) {
                                    radarPlaylistId = pl.optString("id")
                                    radarTitle = name
                                    radarDesc = pl.optString("description")
                                    radarCover = pl.optString("coverImgUrl")
                                    break
                                }
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            // 3. 固定默认私人雷达歌单 ID 兜底尝试
            if (radarPlaylistId.isNullOrBlank()) {
                radarPlaylistId = "3136952023"
            }

            // 4. 拉取歌单歌曲详情
            try {
                val detailData = JSONObject().put("id", radarPlaylistId).put("n", 1000).put("s", 8)
                val (detailJson, _) = postWeapi("/weapi/v6/playlist/detail", detailData, cookie)
                val playlist = detailJson.optJSONObject("playlist")
                if (playlist != null) {
                    val name = playlist.optString("name").ifBlank { radarTitle }
                    val desc = playlist.optString("description").ifBlank { radarDesc }
                    val cover = playlist.optString("coverImgUrl").ifBlank { radarCover }
                    val tracksArray = playlist.optJSONArray("tracks")

                    val songs = mutableListOf<NeteaseSongItem>()
                    if (tracksArray != null) {
                        for (i in 0 until tracksArray.length()) {
                            val track = tracksArray.optJSONObject(i) ?: continue
                            parseSong(track)?.let { songs.add(it) }
                        }
                    }

                    if (songs.isNotEmpty()) {
                        return@runCatching NeteaseRadarResult(
                            playlistId = radarPlaylistId,
                            title = name,
                            description = desc,
                            coverUrl = cover?.replace("http://", "https://"),
                            isFallback = false,
                            songs = songs
                        )
                    }
                }
            } catch (_: Exception) {
            }

            // 5. 最终兜底：使用每日推荐歌曲模拟雷达
            val fallbackDaily = getDailyRecommendSongs(cookie).getOrDefault(emptyList())
            NeteaseRadarResult(
                playlistId = radarPlaylistId,
                title = "私人雷达 (推荐好歌)",
                description = "为你定制的早安灵感与每日私人推荐",
                coverUrl = fallbackDaily.firstOrNull()?.coverUrl,
                isFallback = true,
                songs = fallbackDaily
            )
        }
    }

    private fun parseSong(songObj: JSONObject): NeteaseSongItem? {
        val id = songObj.optString("id").takeIf { it.isNotBlank() } ?: return null
        val name = songObj.optString("name").ifBlank { "未知歌曲" }

        // 歌手列表
        val arArray = songObj.optJSONArray("ar") ?: songObj.optJSONArray("artists")
        val singers = mutableListOf<String>()
        if (arArray != null) {
            for (i in 0 until arArray.length()) {
                val ar = arArray.optJSONObject(i) ?: continue
                val arName = ar.optString("name").trim()
                if (arName.isNotBlank()) singers.add(arName)
            }
        }
        val singer = if (singers.isNotEmpty()) singers.joinToString(" / ") else "未知歌手"

        // 专辑
        val alObj = songObj.optJSONObject("al") ?: songObj.optJSONObject("album")
        val albumName = alObj?.optString("name").orEmpty()
        val albumId = alObj?.optString("id")
        val coverUrl = alObj?.optString("picUrl")?.replace("http://", "https://")

        // 时长
        val durationMs = songObj.optLong("dt", songObj.optLong("duration", 0L))
        val durationSec = (durationMs / 1000).toInt()

        val reason = songObj.optString("reason").takeIf { it.isNotBlank() }

        return NeteaseSongItem(
            id = id,
            name = name,
            singer = singer,
            albumName = albumName,
            albumId = albumId,
            coverUrl = coverUrl,
            durationSeconds = durationSec,
            reason = reason
        )
    }
}
