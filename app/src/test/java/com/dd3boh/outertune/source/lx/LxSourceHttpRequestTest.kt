package com.dd3boh.outertune.source.lx

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只验证洛雪脚本 HTTP 请求体，不涉及 UI。 */
class LxSourceHttpRequestTest {
    @Test
    fun objectBodyIsJsonInsteadOfEmptyOrForm() {
        val request = LxSourceRuntime.buildScriptHttpRequest(
            "https://c.wwwweb.top/music/url",
            buildJsonObject {
                put("method", "POST")
                put("body", buildJsonObject {
                    put("source", "kw")
                    put("musicId", "1090336")
                    put("quality", "320k")
                })
            },
        )

        assertEquals("POST", request.method)
        assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
        assertEquals(
            """{"source":"kw","musicId":"1090336","quality":"320k"}""",
            request.bodyUtf8(),
        )
        assertTrue(request.header("User-Agent")!!.contains("Mozilla/5.0"))
    }

    @Test
    fun stringBodyKeepsRawContent() {
        val request = LxSourceRuntime.buildScriptHttpRequest(
            "https://example.com/encrypt",
            buildJsonObject {
                put("method", "post")
                put("headers", buildJsonObject { put("Content-Type", "text/plain") })
                put("body", """abc"def""")
            },
        )

        assertEquals("POST", request.method)
        assertEquals("text/plain; charset=utf-8", request.body!!.contentType().toString())
        assertEquals("""abc"def""", request.bodyUtf8())
    }

    @Test
    fun stringBodyWithoutContentTypeKeepsRawContent() {
        val request = LxSourceRuntime.buildScriptHttpRequest(
            "https://example.com/form-data",
            buildJsonObject {
                put("method", "POST")
                put("body", "a=1&b=2")
            },
        )

        assertEquals("text/plain; charset=utf-8", request.body!!.contentType().toString())
        assertEquals("a=1&b=2", request.bodyUtf8())
    }

    @Test
    fun formDataIsMultipartAndDoesNotKeepInvalidContentTypeHeader() {
        val request = LxSourceRuntime.buildScriptHttpRequest(
            "https://example.com/upload",
            buildJsonObject {
                put("method", "POST")
                put("headers", buildJsonObject { put("Content-Type", "multipart/form-data") })
                put("formData", buildJsonObject {
                    put("source", "kw")
                    put("musicId", "1090336")
                    put("empty", JsonNull)
                })
            },
        )

        assertTrue(request.body!!.contentType()!!.toString().startsWith("multipart/form-data; boundary="))
        assertNull(request.header("Content-Type"))
        val body = request.bodyUtf8()
        assertTrue(body.contains("name=\"source\""))
        assertTrue(body.contains("\r\n\r\nkw\r\n"))
        assertTrue(body.contains("name=\"musicId\""))
        assertTrue(body.contains("\r\n\r\n1090336\r\n"))
        assertTrue(body.contains("name=\"empty\""))
    }

    @Test
    fun formIsUrlEncodedAndBodyObjectDoesNotOverrideIt() {
        val request = LxSourceRuntime.buildScriptHttpRequest(
            "https://example.com/form",
            buildJsonObject {
                put("method", "POST")
                put("form", buildJsonObject {
                    put("source", "kw")
                    put("musicId", "1090336")
                })
                put("body", buildJsonObject { put("ignored", "yes") })
            },
        )

        assertEquals("application/x-www-form-urlencoded", request.body!!.contentType().toString())
        assertEquals("source=kw&musicId=1090336", request.bodyUtf8())
    }

    @Test
    fun legacyDataFieldStillWorksWhenBodyIsAbsent() {
        val request = LxSourceRuntime.buildScriptHttpRequest(
            "https://example.com/data",
            buildJsonObject {
                put("method", "POST")
                put("data", buildJsonObject { put("source", "tx") })
            },
        )

        assertEquals("""{"source":"tx"}""", request.bodyUtf8())
    }

    @Test
    fun explicitBodyWinsOverLegacyData() {
        val request = LxSourceRuntime.buildScriptHttpRequest(
            "https://example.com/both",
            buildJsonObject {
                put("method", "POST")
                put("body", buildJsonObject { put("from", "body") })
                put("data", buildJsonObject { put("from", "data") })
            },
        )

        assertEquals("""{"from":"body"}""", request.bodyUtf8())
    }

    @Test
    fun missingBodyAndNullBodyStayEmptyWithoutThrowing() {
        val missing = LxSourceRuntime.buildScriptHttpRequest(
            "https://example.com/empty",
            buildJsonObject { put("method", "POST") },
        )
        val jsonNull = LxSourceRuntime.buildScriptHttpRequest(
            "https://example.com/null",
            buildJsonObject {
                put("method", "POST")
                put("body", JsonNull)
            },
        )

        assertEquals(0, missing.body!!.contentLength())
        assertEquals("", missing.bodyUtf8())
        assertEquals(0, jsonNull.body!!.contentLength())
        assertEquals("", jsonNull.bodyUtf8())
    }

    @Test
    fun getDropsBodyAndCustomUserAgentIsKept() {
        val request = LxSourceRuntime.buildScriptHttpRequest(
            "https://example.com/get",
            buildJsonObject {
                put("method", "GET")
                put("headers", buildJsonObject { put("User-Agent", "LxScript") })
                put("body", buildJsonObject { put("source", "kw") })
            },
        )

        assertEquals("GET", request.method)
        assertNull(request.body)
        assertEquals("LxScript", request.header("User-Agent"))
        assertEquals(1, request.headers("User-Agent").size)
    }
}

private fun okhttp3.Request.bodyUtf8(): String {
    val body = body ?: return ""
    val buffer = Buffer()
    body.writeTo(buffer)
    return buffer.readUtf8()
}
