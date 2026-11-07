package com.dd3boh.outertune.source.kugou

import com.dd3boh.outertune.source.SourceCapabilities
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class KugouProtocolTest {
    @Test fun `signature is ordered and includes exact body`() {
        val params = linkedMapOf("z" to "中文", "a" to "1")
        val body = "{\"a\":1}"
        assertEquals(KugouCrypto.md5("LnT6xpN3khm36zse0QzvmgTZ3waWdRSAa=1z=中文${body}LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"), KugouCrypto.signature(params, body))
        assertEquals(KugouCrypto.signature(params, body), KugouCrypto.signature(params.toSortedMap(), body))
        assertNotEquals(KugouCrypto.signature(params, body), KugouCrypto.signature(params, "{}"))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", KugouCrypto.md5("abc"))
    }
    @Test fun `device id remains unsigned and RSA key is usable`() {
        assertTrue(java.math.BigInteger(KugouCrypto.mid("anhe-test-installation")).signum() > 0)
        assertEquals(256, KugouCrypto.rsa("{}", raw = true).length)
        assertEquals(256, KugouCrypto.rsa("{}").length)
        val key = "abcdefghijklmnop"
        val cipher = KugouCrypto.encryptLogin("中文测试", key)
        assertEquals("中文测试", KugouCrypto.decryptLogin(cipher, key))
    }
    @Test fun `concept and lx never share identity or download capability`() {
        val raw = Json.parseToJsonElement("""{"FileHash":"ABCDEF0123456789ABCDEF0123456789","OriSongName":"<em>晴天</em>","SingerName":"歌手","AlbumID":"9007199254740993","MixSongID":"9007199254740995","Duration":269,"Image":"https://example.com/{size}.jpg"}""").let { it as kotlinx.serialization.json.JsonObject }
        val song = checkNotNull(KugouParser.song(raw))
        assertEquals("kgc_abcdef0123456789abcdef0123456789", song.id)
        assertEquals(KUGOU_CONCEPT_SOURCE, song.source)
        assertEquals(269, song.duration)
        assertEquals("晴天", song.title)
        assertTrue(song.sourceData!!.contains("9007199254740993"))
        assertTrue(song.sourceData!!.contains("9007199254740995"))
        assertFalse(SourceCapabilities.canDownload(song))
        assertFalse(SourceCapabilities.canDownload(song.copy(source = "kg")))
        assertTrue(SourceCapabilities.canDownload("kg"))
        assertTrue(SourceCapabilities.canDownload("qs"))
        assertFalse(SourceCapabilities.canDownload("kg", true))
        assertFalse(SourceCapabilities.canDownload(null))
        val restored = song.toSongEntity()
        assertEquals(song.source, restored.source)
        assertEquals(song.sourceData, restored.sourceData)
    }
    @Test fun `playlist milliseconds and hash are distinct from collection file id`() {
        val raw = Json.parseToJsonElement("""{"hash":"abcdef0123456789abcdef0123456789","name":"歌手 - 歌名","timelen":123000,"fileid":"not-a-song-hash"}""") as kotlinx.serialization.json.JsonObject
        val song = checkNotNull(KugouParser.song(raw))
        assertEquals(123, song.duration)
        assertEquals("歌名", song.title)
        assertEquals("歌手", song.artists.first().name)
        assertNull(KugouParser.song(Json.parseToJsonElement("""{"fileid":"123","name":"坏记录"}""") as kotlinx.serialization.json.JsonObject))
    }
    @Test fun `pagination uses raw counts not filtered songs`() {
        val body = Json.parseToJsonElement("""{"status":1,"data":{"total":30,"lists":[{"FileHash":"bad"}]}}""") as kotlinx.serialization.json.JsonObject
        val page = KugouParser.page(body, 1, 15, "lists")
        assertTrue(page.hasMore)
        assertTrue(page.songs.isEmpty())
    }
    @Test fun `membership alone does not establish today's claim`() {
        fun body(value: String) = Json.parseToJsonElement(value) as kotlinx.serialization.json.JsonObject
        assertFalse(KugouParser.containsClaimDate(body("""{"status":1,"data":{"is_vip":1}}"""), "2026-11-07"))
        assertFalse(KugouParser.containsClaimDate(body("""{"data":[{"receive_day":"2026-11-06","status":1}]}"""), "2026-11-07"))
        assertFalse(KugouParser.containsClaimDate(body("""{"data":[{"receive_day":"2026-11-07","status":0}]}"""), "2026-11-07"))
        assertFalse(KugouParser.containsClaimDate(body("""{"data":[{"receive_day":"2026-11-07"}]}"""), "2026-11-07"))
        assertTrue(KugouParser.containsClaimDate(body("""{"data":[{"receive_day":"2026-11-07","status":1}]}"""), "2026-11-07"))
    }
}
