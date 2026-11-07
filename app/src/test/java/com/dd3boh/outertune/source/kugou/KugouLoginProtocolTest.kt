package com.dd3boh.outertune.source.kugou

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class KugouLoginProtocolTest {
    @Test fun `renewal preserves user device and encrypted parameters`() {
        val session = KugouSession(guid = "install-guid", dfid = "test-dfid", userId = "9007199254740993", token = "test-token", t1 = "previous-t1")
        val key = "test-renewal-key"
        val time = 1_794_009_600_123L
        val body = KugouLoginProtocol.renewBody(session, time, "test-device", key)
        assertEquals(session.userId, body.text("userid"))
        assertEquals(session.dfid, body.text("dfid"))
        assertEquals("test-device", body.text("dev"))
        assertEquals(time, body.number("clienttime_ms"))
        assertEquals("{}", KugouCrypto.decryptLogin(body.text("params"), key))
        assertEquals(256, body.text("pk").length)
        assertEquals(body.text("pk").uppercase(), body.text("pk"))
        assertFalse(body.toString().contains(session.token))
        assertFalse(body.toString().contains("previous-t1"))
    }

    @Test fun `login decrypts both structured and plain token responses`() {
        val key = "test-login-key"
        val structured = buildJsonObject {
            put("userid", "123")
            put("secu_params", KugouCrypto.encryptLogin("{\"token\":\"fresh-token\",\"vip_token\":\"fresh-vip\"}", key))
        }
        val result = KugouLoginProtocol.unpack(structured, key)
        assertEquals("123", result.text("userid"))
        assertEquals("fresh-token", result.text("token"))
        assertEquals("fresh-vip", result.text("vip_token"))
        val plain = buildJsonObject { put("secu_params", KugouCrypto.encryptLogin("plain-token", key)) }
        assertEquals("plain-token", KugouLoginProtocol.unpack(plain, key).text("token"))
    }

    @Test fun `legacy session remains readable without renewal timestamp`() {
        val session = kugouJson.decodeFromString<KugouSession>("{\"userId\":\"123\",\"token\":\"test-token\"}")
        assertTrue(session.loggedIn)
        assertEquals(0L, session.renewedAt)
    }

    @Test fun `unknown error codes do not log out or expose response secrets`() {
        val error = failure("{\"status\":0,\"error_code\":20010,\"error_msg\":\"debug token=test-secret https://example.test/?token=test-secret\"}")
        assertFalse(error.authenticationExpired)
        assertFalse(error.message!!.contains("test-secret"))
        assertFalse(error.message!!.contains("https://"))
    }

    @Test fun `explicit auth eligibility and safety errors remain distinct`() {
        assertTrue(failure("{\"status\":0,\"error_msg\":\"登录已失效\"}").authenticationExpired)
        assertTrue(failure("{\"status\":0,\"error_msg\":\"当前账号不符合领取条件\"}").ineligible)
        val risk = failure("{\"status\":0,\"error_code\":20028}")
        assertFalse(risk.authenticationExpired)
        assertEquals(20028L, risk.code)
    }

    @Test fun `claim clock rejects missing or unreasonable server dates`() {
        val api = KugouApi { KugouSession() }
        val format = java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME
        api.updateServerTime(format.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)))
        assertTrue(api.hasConfirmedServerTime)
        api.updateServerTime(null)
        assertFalse(api.hasConfirmedServerTime)
        api.updateServerTime("invalid-date")
        assertFalse(api.hasConfirmedServerTime)
        api.updateServerTime(format.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusDays(3)))
        assertFalse(api.hasConfirmedServerTime)
    }

    private fun failure(text: String): KugouApiException {
        val api = KugouApi { KugouSession() }
        try { api.ensureSuccess(kugouJson.parseToJsonElement(text) as JsonObject) }
        catch (e: KugouApiException) { return e }
        throw AssertionError("响应必须被识别为业务失败")
    }
}
