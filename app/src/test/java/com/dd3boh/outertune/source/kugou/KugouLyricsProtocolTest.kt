package com.dd3boh.outertune.source.kugou

import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dd3boh.outertune.lyrics.LyricsCachePolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseLrc
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class KugouLyricsProtocolTest {
    private fun body(raw: String) = kugouJson.parseToJsonElement(raw) as JsonObject
    private fun response(content: String) = buildJsonObject {
        put("status", 200); put("error_code", 0); put("contenttype", 1)
        put("content", KugouCrypto.encodeBase64(content.toByteArray(Charsets.UTF_8)))
    }

    @Test fun `lyric search accepts real 200 status and errcode semantics`() {
        val result = body("""{"status":200,"errcode":200,"info":"OK","candidates":[{"id":"9007199254740993","accesskey":"test-lyric-key"}]}""")
        val candidate = checkNotNull(KugouLyricsProtocol.candidate(result))
        assertEquals("9007199254740993", candidate.text("id"))
        assertEquals("test-lyric-key", candidate.text("accesskey"))
        assertEquals("9007199254740993", KugouLyricsProtocol.downloadQuery(candidate)["id"])
        // 200 仅在歌词协议中代表成功，不放宽账号、VIP 或播放业务校验。
        assertTrue(runCatching { KugouApi { KugouSession() }.ensureSuccess(result) }.exceptionOrNull() is KugouApiException)
    }

    @Test fun `search skips malformed candidates and supports nested legacy response`() {
        val result = body("""{"status":1,"data":{"candidates":[{"id":"bad"},{"accesskey":"bad"},{"id":"valid","accesskey":"valid-key"}]}}""")
        assertEquals("valid", KugouLyricsProtocol.candidate(result)?.text("id"))
    }

    @Test fun `404 and empty candidates mean no lyric rather than protocol failure`() {
        assertNull(KugouLyricsProtocol.candidate(body("""{"status":404,"errcode":404,"candidates":[]}""")))
        assertNull(KugouLyricsProtocol.candidate(body("""{"status":200,"errcode":200,"candidates":[]}""")))
        assertNull(KugouLyricsProtocol.decodeContent(body("""{"status":404,"error_code":404}""")))
        assertNull(KugouLyricsProtocol.decodeContent(body("""{"status":200,"error_code":0,"content":""}""")))
    }

    @Test fun `downloaded utf8 base64 feeds the real timestamp lyric parser`() {
        val lrc = "[00:01.00]第一句歌词\n[00:02.50]第二句歌词\n"
        val decoded = checkNotNull(KugouLyricsProtocol.decodeContent(response(lrc)))
        assertEquals(lrc, decoded)
        val parsed = parseLrc(decoded, trimEnabled = false, multiLineEnabled = true) as SemanticLyrics.SyncedLyrics
        assertEquals(listOf("第一句歌词", "第二句歌词"), parsed.text.map { it.text })
        assertEquals(listOf(1000uL, 2500uL), parsed.text.map { it.start })
    }

    @Test fun `utf8 bom is removed and blank decoded text is not cached`() {
        val bom = 0xFEFF.toChar().toString()
        assertEquals("[00:01.00]中文", KugouLyricsProtocol.decodeContent(response(bom + "[00:01.00]中文")))
        assertNull(KugouLyricsProtocol.decodeContent(response(" \n")))
    }

    @Test fun `invalid encoding never exposes original content or exception cause`() {
        val invalid = body("""{"status":200,"error_code":0,"content":"%%%private-data%%%"}""")
        val base64Failure = runCatching { KugouLyricsProtocol.decodeContent(invalid) }.exceptionOrNull()!!
        assertTrue(base64Failure is IOException)
        assertNull(base64Failure.cause)
        assertFalse(base64Failure.message!!.contains("private-data"))
        val invalidUtf8 = buildJsonObject {
            put("status", 200); put("error_code", 0)
            put("content", KugouCrypto.encodeBase64(byteArrayOf(0xC3.toByte(), 0x28)))
        }
        assertTrue(runCatching { KugouLyricsProtocol.decodeContent(invalidUtf8) }.exceptionOrNull() is IOException)
    }

    @Test fun `business failures preserve safe numeric code without response secrets`() {
        val error = runCatching {
            KugouLyricsProtocol.candidate(body("""{"status":200,"errcode":200,"error_code":123,"errmsg":"test-token https://private.example.test?accesskey=test-key"}"""))
        }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("123"))
        assertFalse(error.message!!.contains("test-token"))
        assertFalse(error.message!!.contains("https://"))
        assertNull(error.cause)
    }

    @Test fun `lyric requests keep hash duration and large ids without account credentials`() {
        val session = KugouSession(guid = "test-device", userId = "test-user", token = "test-token")
        val api = KugouApi { session }
        val query = KugouLyricsProtocol.searchQuery("abcdef0123456789abcdef0123456789", "9007199254740995", 269, "测试歌曲")
        val request = api.buildRequest("/v1/search", query, "", post = false, host = "lyrics.kugou.com", includeAccount = false, defaults = false)
        assertEquals("https", request.url.scheme)
        assertEquals("lyrics.kugou.com", request.url.host)
        assertEquals("269000", request.url.queryParameter("duration"))
        assertEquals("9007199254740995", request.url.queryParameter("album_audio_id"))
        assertEquals("abcdef0123456789abcdef0123456789", request.url.queryParameter("hash"))
        assertNull(request.url.queryParameter("token"))
        assertNull(request.url.queryParameter("userid"))
        val candidate = body("""{"id":"9007199254740993","accesskey":"test-key"}""")
        val download = api.buildRequest("/download", KugouLyricsProtocol.downloadQuery(candidate), "", post = false,
            host = "lyrics.kugou.com", includeAccount = false, defaults = false)
        assertEquals("lrc", download.url.queryParameter("fmt"))
        assertNull(download.url.queryParameter("token"))
        assertNull(download.url.queryParameter("userid"))
        assertEquals("0", KugouLyricsProtocol.searchQuery("hash", "", -1, "title")["duration"])
    }

    @Test fun `old failed concept lyrics are retried but valid lyrics and other sources remain unchanged`() {
        assertNull(LyricsCachePolicy.usable(KUGOU_CONCEPT_SOURCE, LYRICS_NOT_FOUND))
        assertNull(LyricsCachePolicy.usable(KUGOU_CONCEPT_SOURCE, null))
        val valid = "[00:01.00]有效歌词"
        assertEquals(valid, LyricsCachePolicy.usable(KUGOU_CONCEPT_SOURCE, valid))
        assertEquals(LYRICS_NOT_FOUND, LyricsCachePolicy.usable("kg", LYRICS_NOT_FOUND))
        assertEquals(LYRICS_NOT_FOUND, LyricsCachePolicy.usable(null, LYRICS_NOT_FOUND))
    }
}
