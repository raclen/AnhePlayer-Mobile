package com.dd3boh.outertune.source.lx

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仅验证目录解析和分页边界，不涉及 UI 或外部网络。 */
class LxSongSearchPageTest {
    private val song = """{"id":"28742824","name":"家，太远了","artist":"王杰","album":"路","duration":"284","albumid":"63897","albumpic":"http://img2.kuwo.cn/cover.jpg","MINFO":"bitrate:320,size:10Mb"}"""

    @Test
    fun parsesKuwoPlaylistSongsWithStablePlaybackParameters() {
        val info = KuwoSearchClient.parseMusicInfo(lxJson.parseToJsonElement(song).jsonObject)!!
        assertEquals("kw_28742824", info.id)
        assertEquals("28742824", info.songmid)
        assertEquals("王杰", info.singer)
        assertEquals("04:44", info.interval)
        assertEquals("https://img2.kuwo.cn/cover.jpg", info.meta.picUrl)
        assertEquals("320k", info.meta.qualitys.single().type)
        val restored = lxJson.decodeFromString<LxMusicInfo>(info.toMediaMetadata().toSongEntity().sourceData!!)
        assertEquals(info, restored)
    }

    @Test
    fun keepsNextPageWhenDuplicateOrInvalidRowsReduceVisibleCount() {
        val valid = lxJson.parseToJsonElement("""{"hash":"test-hash","songname":"家，太远了","singername":"王杰","duration":284}""")
        val invalid = lxJson.parseToJsonElement("""{"hash":""}""")
        val page = LxSearchClient.songPage(JsonArray(List(14) { valid } + invalid), 1, 30, LxSearchClient::parseKugouMusicInfo)
        assertEquals(1, page.items.size)
        assertTrue(page.hasMore)
        assertEquals("kg_test-hash", page.items.single().toMediaMetadata().id)
        assertEquals("test-hash", page.items.single().hash)
    }

    @Test
    fun stopsAtFinalOrEmptyPage() {
        val items = JsonArray(listOf(lxJson.parseToJsonElement("""{"hash":"test-hash"}""")))
        assertFalse(LxSearchClient.songPage(items, 2, 16, LxSearchClient::parseKugouMusicInfo).hasMore)
        assertFalse(LxSearchClient.songPage(JsonArray(emptyList()), 2, 30, LxSearchClient::parseKugouMusicInfo).hasMore)
    }

    @Test
    fun missingTotalUsesRawPageSize() {
        val item = lxJson.parseToJsonElement("""{"hash":"test-hash"}""")
        assertTrue(LxSearchClient.songPage(JsonArray(List(15) { item }), 1, null, LxSearchClient::parseKugouMusicInfo).hasMore)
        assertFalse(LxSearchClient.songPage(JsonArray(List(14) { item }), 1, null, LxSearchClient::parseKugouMusicInfo).hasMore)
    }

    @Test
    fun neteaseKeepsArtistAndPlatformParameters() {
        val info = LxSearchClient.parseNeteaseMusicInfo(lxJson.parseToJsonElement("""{"id":123,"name":"家，太远了","ar":[{"name":"王杰"}],"dt":284000,"al":{"id":456,"name":"路","picUrl":"https://example.com/cover.jpg"}}""").jsonObject)!!
        assertEquals("wy_123", info.id)
        assertEquals("123", info.musicId)
        assertEquals("王杰", info.singer)
        assertEquals(284, info.toMediaMetadata().duration)
        assertEquals("456", info.meta.albumId)
    }
}
