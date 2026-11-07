package com.dd3boh.outertune.source.kugou

import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.toMediaMetadata
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class KugouRadioProtocolTest {
    // 合成凭据只用于构造协议请求，不发送网络，也不读真实账号。
    private val session = KugouSession(
        guid = "synthetic-radio-installation", dfid = "synthetic-radio-dfid",
        userId = "synthetic-radio-user", token = "synthetic-radio-token",
    )
    private val time = 1_794_009_600_123L
    private val hash = "abcdef0123456789abcdef0123456789"

    private fun body(raw: String) = kugouJson.parseToJsonElement(raw) as JsonObject

    @Test fun `radio modes expose only the three official channels and unique queue ids`() {
        assertEquals(listOf("normal", "small", "peak"), KugouRadioMode.entries.map { it.apiValue })
        assertEquals(listOf("红心电台", "小众电台", "速览电台"), KugouRadioMode.entries.map { it.title })
        assertEquals(listOf("kgc_radio_normal", "kgc_radio_small", "kgc_radio_peak"), KugouRadioMode.entries.map { it.playlistId })
        assertTrue(KugouRadioMode.entries.all { it.subtitle.isNotBlank() })
    }

    @Test fun `all mode bodies preserve the original fields types and signing time`() {
        val expected = body("""{
            "appid":3116,"clienttime":$time,"mid":"${session.mid}","action":"play",
            "recommend_source_locked":0,"song_pool_id":0,"callerid":0,"m_type":1,
            "platform":"ios","area_code":1,"remain_songcnt":0,"clientver":11440,
            "is_overplay":0,"mode":"normal","key":"${KugouCrypto.parameterKey(time.toString())}",
            "userid":"${session.userId}","kguid":"${session.userId}","token":"${session.token}"
        }""")
        KugouRadioMode.entries.forEach { mode ->
            val actual = KugouRadioProtocol.body(session, time, mode)
            assertEquals(JsonObject(expected + ("mode" to JsonPrimitive(mode.apiValue))), actual)
            assertFalse(actual.containsKey("fakem"))
            assertFalse(actual.containsKey("vip_type"))
            assertFalse(actual.containsKey("playlistId"))
        }
    }

    @Test fun `radio signed requests retain one account snapshot and https gateway router`() {
        var active = session
        val api = KugouApi { active }
        KugouRadioMode.entries.forEach { mode ->
            val payload = KugouRadioProtocol.body(session, time, mode).toString()
            active = session.copy(guid = "other-installation", dfid = "other-dfid", userId = "other-user", token = "other-token")
            val request = api.buildRequest(
                KugouRadioProtocol.PATH, emptyMap(), payload, post = true,
                router = KugouRadioProtocol.ROUTER, sessionOverride = session,
            )
            assertEquals("https", request.url.scheme)
            assertEquals("gateway.kugou.com", request.url.host)
            assertEquals("/v2/personal_recommend", request.url.encodedPath)
            assertEquals("persnfm.service.kugou.com", request.header("x-router"))
            assertEquals("POST", request.method)
            assertEquals(payload, Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
            assertEquals(session.userId, request.url.queryParameter("userid"))
            assertEquals(session.token, request.url.queryParameter("token"))
            assertEquals(session.mid, request.url.queryParameter("mid"))
            assertEquals(session.dfid, request.url.queryParameter("dfid"))
            assertEquals(session.mid, request.header("mid"))
            assertEquals(session.dfid, request.header("dfid"))
            assertNull(request.url.queryParameter("mode"))
            val params = request.url.queryParameterNames.filter { it != "signature" }
                .associateWith { request.url.queryParameter(it)!! }
            assertEquals(KugouCrypto.signature(params, payload), request.url.queryParameter("signature"))
        }
    }

    @Test fun `dedicated queue ids require concept source and do not depend on titles`() {
        KugouRadioMode.entries.forEach { mode ->
            assertEquals(mode, KugouRadioMode.fromQueue(mode.playlistId, mode.title, KUGOU_CONCEPT_SOURCE))
            assertEquals(mode, KugouRadioMode.fromQueue(mode.playlistId, null, KUGOU_CONCEPT_SOURCE))
            assertEquals(mode, KugouRadioMode.fromQueue(mode.playlistId, "每日推荐", KUGOU_CONCEPT_SOURCE))
            listOf(null, "", "kg", "radio", "qs", "KG_CONCEPT").forEach { source ->
                assertNull(KugouRadioMode.fromQueue(mode.playlistId, mode.title, source))
                assertNull(KugouRadioMode.fromQueue(null, "红心电台", source))
            }
        }
    }

    @Test fun `only empty id legacy heart queues may match by title`() {
        listOf(null, "").forEach { playlistId ->
            assertEquals(KugouRadioMode.HEART, KugouRadioMode.fromQueue(playlistId, "红心电台", KUGOU_CONCEPT_SOURCE))
            listOf(null, "", "小众电台", "速览电台", "每日推荐", "红心 Radio", "红心电台 ").forEach { title ->
                assertNull(KugouRadioMode.fromQueue(playlistId, title, KUGOU_CONCEPT_SOURCE))
            }
        }
        listOf("kgc_daily", "kgc_daily_recommend", "radio", "unknown", " ", "kgc_radio_unknown").forEach { playlistId ->
            assertNull(KugouRadioMode.fromQueue(playlistId, "红心电台", KUGOU_CONCEPT_SOURCE))
        }
    }

    @Test fun `queue markers never contain account credentials`() {
        KugouRadioMode.entries.forEach { mode ->
            val marker = listOf(mode.playlistId, mode.title, mode.subtitle, KUGOU_CONCEPT_SOURCE).joinToString("|")
            assertFalse(marker.contains(session.userId))
            assertFalse(marker.contains(session.token))
            assertFalse(marker.contains("userid"))
            assertFalse(marker.contains("token"))
            assertEquals(mode, KugouRadioMode.fromQueue(mode.playlistId, mode.title, KUGOU_CONCEPT_SOURCE))
        }
        assertNull(KugouRadioMode.fromQueue(session.token, "红心电台", KUGOU_CONCEPT_SOURCE))
        assertNull(KugouRadioMode.fromQueue(session.userId, "红心电台", KUGOU_CONCEPT_SOURCE))
    }

    @Test fun `radio songs persist only whitelisted media routing fields not credentials or mode`() {
        KugouRadioMode.entries.forEach { mode ->
            val response = body("""{"data":{"song_list":[{
                "hash":"$hash","songname":"电台歌曲","time_length":210,
                "mode":"${mode.apiValue}","playlistId":"${mode.playlistId}",
                "userid":"${session.userId}","token":"${session.token}",
                "relate_goods":[{"hash":"$hash","album_id":"9007199254740993","album_audio_id":"9007199254740995",
                    "token":"${session.token}","info":{"userid":"${session.userId}"}}]
            }]}}""")
            val song = KugouParser.songs(response.obj("data").rows("song_list", "songs", "songlist", "list")).single()
            val expectedInfo = body("""{"version":1,"hash":"$hash","album_id":"9007199254740993","album_audio_id":"9007199254740995","hash_320":"","hash_flac":""}""")
            assertEquals(expectedInfo, body(checkNotNull(song.sourceData)))
            val entity = song.toSongEntity()
            val restored = Song(entity, emptyList()).toMediaMetadata()
            assertEquals("kgc_$hash", restored.id)
            assertEquals(KUGOU_CONCEPT_SOURCE, restored.source)
            assertEquals(hash, restored.sourceId)
            assertEquals(song.sourceData, entity.sourceData)
            assertEquals(song.sourceData, restored.sourceData)
            listOf(song.toString(), entity.toString(), restored.toString()).forEach { stored ->
                assertFalse(stored.contains(session.userId))
                assertFalse(stored.contains(session.token))
                assertFalse(stored.contains(mode.playlistId))
            }
            // 同一首歌可出现在每日推荐；歌曲来源本身不能充当电台队列标记。
            assertNull(KugouRadioMode.fromQueue("kgc_daily", "每日推荐", restored.source))
        }
    }
}
