package com.dd3boh.outertune.source.kugou

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class KugouRequestTest {
    private val session = KugouSession(
        guid = "test-installation", dfid = "test-dfid", userId = "test-user", token = "test-token",
    )
    private val api = KugouApi { session }

    @Test fun `sms uses verified gateway and preserves signed body without account credentials`() {
        // 只构造请求，不进行网络发送，也不使用真实手机号。
        val body = buildJsonObject { put("businessid", 5); put("mobile", "test-mobile"); put("plat", 3) }.toString()
        val request = api.buildRequest(
            "/v7/send_mobile_code", emptyMap(), body, post = true,
            router = "login.user.kugou.com", includeAccount = false,
        )
        assertEquals("https", request.url.scheme)
        assertEquals("gateway.kugou.com", request.url.host)
        assertEquals("/v7/send_mobile_code", request.url.encodedPath)
        assertEquals("login.user.kugou.com", request.header("x-router"))
        assertEquals("POST", request.method)
        assertEquals(body, Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
        assertNull(request.url.queryParameter("mobile"))
        assertNull(request.url.queryParameter("token"))
        assertNull(request.url.queryParameter("userid"))
        assertNull(request.header("Authorization"))
        assertEquals(session.dfid, request.header("dfid"))
        assertEquals(session.mid, request.header("mid"))
        val params = request.url.queryParameterNames.filter { it != "signature" }.associateWith { request.url.queryParameter(it)!! }
        assertEquals(KugouCrypto.signature(params, body), request.url.queryParameter("signature"))
    }

    @Test fun `renewal also uses gateway without changing encrypted login payload`() {
        val payload = KugouLoginProtocol.renewBody(session, 1_794_009_600_123L, "test-device", "test-renewal-key").toString()
        val request = api.buildRequest(
            "/v5/login_by_token", emptyMap(), payload, post = true,
            router = "login.user.kugou.com", sessionOverride = session,
        )
        assertEquals("https", request.url.scheme)
        assertEquals("gateway.kugou.com", request.url.host)
        assertEquals("login.user.kugou.com", request.header("x-router"))
        assertEquals("POST", request.method)
        assertEquals(payload, Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        assertFalse(payload.contains(session.token))
        assertEquals(session.userId, request.url.queryParameter("userid"))
    }

    @Test fun `qr retains its distinct valid https host and web signature`() {
        val request = api.buildRequest(
            "/v2/qrcode", mapOf("appid" to "1001", "srcappid" to "2919"), "", post = false,
            host = "login-user.kugou.com", web = true, includeAccount = false,
        )
        assertEquals("https", request.url.scheme)
        assertEquals("login-user.kugou.com", request.url.host)
        assertEquals("GET", request.method)
        assertNull(request.header("x-router"))
        assertNull(request.url.queryParameter("token"))
        val params = request.url.queryParameterNames.filter { it != "signature" }.associateWith { request.url.queryParameter(it)!! }
        assertEquals(KugouCrypto.signature(params, web = true), request.url.queryParameter("signature"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dotted login service cannot regress to direct tls access`() {
        api.buildRequest("/v7/send_mobile_code", emptyMap(), "{}", post = true, host = "login.user.kugou.com")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `requests cannot use an arbitrary third party host`() {
        api.buildRequest("/v7/send_mobile_code", emptyMap(), "{}", post = true, host = "example.test")
    }

    @Test fun `network failure categories distinguish dns tls timeout and connectivity`() {
        assertTrue(api.connectionFailure(UnknownHostException()).message!!.contains("域名解析"))
        assertTrue(api.connectionFailure(SSLHandshakeException("test")).message!!.contains("安全连接"))
        assertTrue(api.connectionFailure(SSLPeerUnverifiedException("test")).message!!.contains("安全连接"))
        assertTrue(api.connectionFailure(SocketTimeoutException()).message!!.contains("超时"))
        assertTrue(api.connectionFailure(InterruptedIOException()).message!!.contains("超时"))
        assertTrue(api.connectionFailure(ConnectException()).message!!.contains("无法连接"))
    }

    @Test fun `network diagnostics never retain original secrets urls or causes`() {
        val privateMessage = "test-mobile test-token https://example.test/?token=test-token"
        val errors = listOf(
            UnknownHostException(privateMessage), SSLHandshakeException(privateMessage),
            SocketTimeoutException(privateMessage), ConnectException(privateMessage), IOException(privateMessage),
        )
        errors.forEach { original ->
            val safe = api.connectionFailure(original)
            assertNull(safe.cause)
            assertFalse(safe.toString().contains("test-mobile"))
            assertFalse(safe.toString().contains("test-token"))
            assertFalse(safe.toString().contains("https://"))
        }
    }
}
