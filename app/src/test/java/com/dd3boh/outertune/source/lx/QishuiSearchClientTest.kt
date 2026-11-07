package com.dd3boh.outertune.source.lx

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 验证汽水搜索协议和脚本参数持久化，不涉及 UI 或外部网络。 */
class QishuiSearchClientTest {
    private val track = """{
        "id":"7146240707408168993",
        "name":" <em>因为</em>爱情&nbsp;&amp;现场 ",
        "artists":[{"name":"周杰伦"},{"name":"那英"}],
        "duration":202052,
        "album":{
            "id":"7145673767388186662",
            "name":"现场专辑",
            "url_cover":{
                "uri":"test/cover",
                "urls":["https://p3-luna.douyinpic.com/img/"],
                "template_prefix":"tplv-b829550vbb"
            }
        },
        "vid":"test-video-id",
        "bit_rates":[{"br":132188,"size":3339370,"quality":"higher"}],
        "future_field":{"nested":[1,null,"原始参数"]}
    }""".trimIndent()

    private fun response(vararg entries: String): String = """{
        "status_info":{"code":0},
        "result_groups":[{"data":[${entries.joinToString(",")}],"has_more":true}]
    }"""

    @Test
    fun mapsOfficialTrackAndCoverWithoutExtraRequests() {
        val song = QishuiSearchClient.parseResponse(response("""{"entity":{"track":$track}}""")).single()
        val metadata = song.toMediaMetadata()

        assertEquals("qs_7146240707408168993", song.id)
        assertEquals("7146240707408168993", song.songmid)
        assertEquals("qs", metadata.source)
        assertEquals("7146240707408168993", metadata.sourceId)
        assertEquals("因为爱情 &现场", metadata.title)
        assertEquals("周杰伦 / 那英", song.singer)
        assertEquals(202, metadata.duration)
        assertEquals("现场专辑", metadata.album!!.title)
        assertEquals("https://p3-luna.douyinpic.com/img/test/cover~tplv-b829550vbb-resize:960:960.png", metadata.thumbnailUrl)
        assertTrue(song.meta.qualitys.isEmpty())
    }

    @Test
    fun preservesRawTrackThroughSerializationAndSongEntity() {
        val song = QishuiSearchClient.parseResponse(response("""{"track":$track}""")).single()
        val entity = song.toMediaMetadata().toSongEntity()
        val restored = lxJson.decodeFromString<LxMusicInfo>(entity.sourceData!!)
        val scriptInfo = lxJson.parseToJsonElement(entity.sourceData!!).jsonObject.toScriptMusicInfo()

        assertEquals(song.id, entity.id)
        assertEquals(song.source, entity.source)
        assertEquals(song.meta.songId, entity.sourceId)
        assertEquals(song.raw, restored.raw)
        assertEquals(song.id, restored.toMediaMetadata().id)
        assertEquals(song.songmid, scriptInfo["id"]!!.jsonPrimitive.content)
        assertEquals(song.songmid, scriptInfo["songId"]!!.jsonPrimitive.content)
        assertEquals("test-video-id", scriptInfo["vid"]!!.jsonPrimitive.content)
        assertEquals(song.raw!!["bit_rates"], scriptInfo["bit_rates"])
        assertEquals(song.raw!!["future_field"], scriptInfo["future_field"])
        assertEquals(song.raw, scriptInfo["raw"])
    }

    @Test
    fun keepsLargeNumericIdsExactAndIgnoresMalformedEntries() {
        val songs = QishuiSearchClient.parseResponse(response(
            "null",
            "{}",
            """{"track":{"name":"没有 ID"}}""",
            """{"track":{"id":7146240707408168993,"title":"歌曲","duration":205,"author_info":[{"author_name":"歌手"}]}}""",
        ))
        val song = songs.single()
        assertEquals("7146240707408168993", song.songmid)
        assertTrue(song.raw!!["id"]!!.jsonPrimitive.isString)
        val scriptInfo = lxJson.parseToJsonElement(song.toMediaMetadata().sourceData!!).jsonObject.toScriptMusicInfo()
        assertEquals("7146240707408168993", scriptInfo["id"]!!.jsonPrimitive.content)
        assertTrue(scriptInfo["id"]!!.jsonPrimitive.isString)
        assertEquals("歌手", song.singer)
        assertEquals(205, song.toMediaMetadata().duration)
        assertFalse(song.toMediaMetadata().sourceData!!.isBlank())
    }

    @Test
    fun limitsAndDeduplicatesResultsEvenWhenServerIgnoresCount() {
        val entries = (1..20).map { """{"track":{"id":"$it","name":"歌曲 $it"}}""" }
        val songs = QishuiSearchClient.parseResponse(response(entries[0], *entries.toTypedArray()))
        assertEquals(15, songs.size)
        assertEquals(15, songs.map { it.id }.distinct().size)
        assertEquals(3, QishuiSearchClient.parseResponse(response(*entries.toTypedArray()), 3).size)
    }

    @Test
    fun supportsCoverVariantsAndMissingDuration() {
        assertEquals("https://cdn.example/cover.jpg", QishuiSearchClient.coverUrl(JsonPrimitive("https://cdn.example/cover.jpg")))
        assertEquals("https://cdn.example/cover.jpg", QishuiSearchClient.coverUrl(lxJson.parseToJsonElement("""{"uri":"cover.jpg","urls":["https://cdn.example/"]}""")))
        assertEquals("https://cdn.example/cover.jpg", QishuiSearchClient.coverUrl(lxJson.parseToJsonElement("""{"uri":"cover.jpg","urls":["https://cdn.example/cover.jpg"]}""")))
        assertNull(QishuiSearchClient.coverUrl(null))
        val metadata = QishuiSearchClient.parseResponse(response("""{"track":{"id":"1","name":"歌曲"}}""")).single().toMediaMetadata()
        assertEquals(-1, metadata.duration)
        assertEquals("未知歌手", metadata.artists.single().name)
    }

    @Test
    fun emptySearchIsEmptyInsteadOfParseFailure() {
        assertTrue(QishuiSearchClient.parseResponse("""{"status_info":{"code":0},"result_groups":[]}""").isEmpty())
        assertTrue(QishuiSearchClient.parseResponse("""{"status_info":{"code":0}}""").isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun reportsUpstreamErrorInsteadOfEmptySuccess() {
        QishuiSearchClient.parseResponse("""{"status_info":{"code":403,"message":"请求被拒绝"}}""")
    }

    @Test
    fun leavesExistingPlatformScriptParametersUntouched() {
        val original = lxJson.parseToJsonElement("""{"id":"kw_1","songmid":"1","source":"kw","raw":null}""").jsonObject
        assertSame(original, original.toScriptMusicInfo())
    }
}
