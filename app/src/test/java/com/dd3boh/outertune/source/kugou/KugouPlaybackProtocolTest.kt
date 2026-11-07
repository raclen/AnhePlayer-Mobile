package com.dd3boh.outertune.source.kugou

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class KugouPlaybackProtocolTest {
    private val hash = "abcdef0123456789abcdef0123456789"
    private val otherHash = "0123456789abcdef0123456789abcdef"
    private fun body(raw: String) = kugouJson.parseToJsonElement(raw) as JsonObject

    @Test fun `http only audio responses are playable without changing signed urls`() {
        val response = body("""{"status":1,"url":[" http://audio.example.test/song.mp3?sign=A%2Fb "]}""")
        assertEquals(listOf("http://audio.example.test/song.mp3?sign=A%2Fb"), KugouPlaybackProtocol.urls(response))
    }

    @Test fun `main and backup audio urls retain order and discard duplicates`() {
        val response = body("""{"url":["https://audio.example.test/main","http://audio.example.test/second"],"backupUrl":["https://audio.example.test/main",["http://backup.example.test/song"]],"backup_url":"https://backup.example.test/song"}""")
        assertEquals(listOf("https://audio.example.test/main", "http://audio.example.test/second", "http://backup.example.test/song", "https://backup.example.test/song"), KugouPlaybackProtocol.urls(response))
        assertEquals(listOf("https://backup.example.test/song"), KugouPlaybackProtocol.urls(body("""{"url":null,"backup_url":"https://backup.example.test/song"}""")))
    }

    @Test fun `audio parser rejects invalid schemes hosts userinfo and non strings`() {
        val response = body("""{"url":["", " ", "https://", "http:///no-host", "file:///song", "content://song", "javascript:alert(1)", "ftp://audio.example.test/song", "https://user:password@audio.example.test/song", "https://audio.example.test:99999/song", "https://audio.example.test/song#fragment", true, 123, {}]}""")
        assertTrue(KugouPlaybackProtocol.urls(response).isEmpty())
    }

    @Test fun `radio preserves matching related album ids and artwork in source data`() {
        val song = checkNotNull(KugouParser.song(body("""{
            "hash":"$hash","ori_audio_name":"电台歌曲","singerinfo":[{"name":"歌手"}],"time_length":210,
            "relate_goods":[
                {"hash":"$otherHash","album_id":"wrong-album","album_audio_id":"wrong-audio"},
                {"hash":"${hash.uppercase()}","album_id":"9007199254740993","album_audio_id":"9007199254740995","albumname":"专辑","info":{"image":"https://image.example.test/{size}.jpg"}}
            ]
        }""")))
        val info = body(checkNotNull(song.sourceData))
        assertEquals("9007199254740993", info.text("album_id"))
        assertEquals("9007199254740995", info.text("album_audio_id"))
        assertEquals(210, song.duration)
        assertEquals("歌手", song.artists.single().name)
        assertEquals("专辑", song.album?.title)
        assertEquals("https://image.example.test/480.jpg", song.thumbnailUrl)
        assertEquals(KUGOU_CONCEPT_SOURCE, song.source)
        assertEquals(song.sourceData, song.toSongEntity().sourceData)
    }

    @Test fun `radio supports hash128 songid and related only songs`() {
        val hash128 = checkNotNull(KugouParser.song(body("""{"hash":"0","hash_128":"$hash","songname":"歌曲","songid":"9007199254740997","time_length":180}""")))
        assertEquals(hash, hash128.sourceId)
        assertEquals("9007199254740997", body(hash128.sourceData!!).text("album_audio_id"))
        val related = checkNotNull(KugouParser.song(body("""{"relate_goods":[{"hash":"$hash","songname":"歌曲","album_id":"12","album_audio_id":"34","info":{"duration":201}}]}""")))
        assertEquals(201, related.duration)
        assertEquals("12", body(related.sourceData!!).text("album_id"))
        assertEquals("34", body(related.sourceData!!).text("album_audio_id"))
    }

    @Test fun `unrelated album ids are never applied to the selected hash`() {
        val song = checkNotNull(KugouParser.song(body("""{"hash":"$hash","songname":"歌曲","relate_goods":[{"hash":"$otherHash","album_id":"wrong","album_audio_id":"wrong"}]}""")))
        val info = body(song.sourceData!!)
        assertEquals("", info.text("album_id"))
        assertEquals("", info.text("album_audio_id"))
    }

    @Test fun `daily recommendation ids and milliseconds survive normalization`() {
        val song = checkNotNull(KugouParser.song(body("""{"hash":"$hash","songname":"每日歌曲","album_id":"101","audio_id":"102","timelength":223000}""")))
        assertEquals(223, song.duration)
        assertEquals("101", body(song.sourceData!!).text("album_id"))
        assertEquals("102", body(song.sourceData!!).text("album_audio_id"))
    }

    @Test fun `v5 signed playback request keeps ids key and account snapshot on https gateway`() {
        // 合成账号，只构造请求；音频 HTTP 兼容不能改变授权接口的 HTTPS 边界。
        val session = KugouSession(guid = "test-device", dfid = "test-dfid", userId = "test-user", token = "test-token")
        val info = body("""{"hash":"$hash","album_id":"9007199254740993","album_audio_id":"9007199254740995"}""")
        val api = KugouApi { session.copy(userId = "wrong-user", token = "wrong-token") }
        val query = KugouPlaybackProtocol.query(info, session)
        val request = api.buildRequest("/v5/url", query, "", post = false, router = "trackercdn.kugou.com", sessionOverride = session)
        assertEquals("https", request.url.scheme)
        assertEquals("gateway.kugou.com", request.url.host)
        assertEquals("trackercdn.kugou.com", request.header("x-router"))
        assertEquals("GET", request.method)
        assertEquals("/v5/url", request.url.encodedPath)
        assertEquals("9007199254740993", request.url.queryParameter("album_id"))
        assertEquals("9007199254740995", request.url.queryParameter("album_audio_id"))
        assertEquals(session.userId, request.url.queryParameter("userid"))
        assertEquals(session.token, request.url.queryParameter("token"))
        assertEquals("11430", request.url.queryParameter("clientver"))
        assertEquals("11430", request.url.queryParameter("version"))
        assertEquals("1", request.url.queryParameter("cdnBackup"))
        assertEquals("1", request.url.queryParameter("IsFreePart"))
        assertEquals(KugouCrypto.md5(hash + "185672dd44712f60bb1736df5a377e82" + "3116" + session.mid + session.userId), request.url.queryParameter("key"))
        val params = request.url.queryParameterNames.filter { it != "signature" }.associateWith { request.url.queryParameter(it)!! }
        assertEquals(KugouCrypto.signature(params), request.url.queryParameter("signature"))
    }

    @Test fun `safe playback failure shows permission or business reason but not arbitrary io details`() {
        val permission = KugouPlaybackProtocol.unavailable(body("""{"status":3,"error_msg":"test-token http://private.example.test"}"""))
        assertEquals("这首歌暂无可用播放权限", KugouPlaybackProtocol.safeFailure(IOException("outer", permission)))
        assertNull(KugouPlaybackProtocol.safeFailure(IOException("test-token http://private.example.test")))
        val api = KugouApi { KugouSession() }
        val business = runCatching { api.ensureSuccess(body("""{"status":0,"error_code":123,"error_msg":"test-token http://private.example.test"}""")) }.exceptionOrNull()!!
        val safe = KugouPlaybackProtocol.safeFailure(IOException("outer", business))!!
        assertTrue(safe.contains("123"))
        assertFalse(safe.contains("test-token"))
        assertFalse(safe.contains("http://"))
    }
}
