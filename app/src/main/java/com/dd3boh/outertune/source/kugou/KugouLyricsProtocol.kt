package com.dd3boh.outertune.source.kugou

import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal object KugouLyricsProtocol {
    fun searchQuery(hash: String, audioId: String, durationSeconds: Int, keyword: String): Map<String, String> = mapOf(
        "album_audio_id" to audioId.ifBlank { "0" }, "appid" to "3116", "clientver" to "11440",
        "duration" to (durationSeconds.coerceAtLeast(0) * 1000L).toString(), "hash" to hash,
        "keyword" to keyword, "lrctxt" to "1", "man" to "no",
    )

    private fun ensureSuccess(body: JsonObject): Boolean {
        val status = body.number("status")
        val resultCode = body.number("errcode")
        val errorCode = body.number("error_code", "errorcode", "errno")
        // 歌词端点采用 HTTP 风格状态：status=200、errcode=200 是成功，不能套用账号接口的非零错误码规则。
        if (status == 404L || resultCode == 404L || errorCode == 404L) return false
        if (status in setOf(1L, 200L) && resultCode in setOf(null, 0L, 200L) && errorCode in setOf(null, 0L)) return true
        val code = errorCode?.takeIf { it != 0L } ?: resultCode?.takeIf { it != 0L && it != 200L } ?: status
        // 不展示服务端 errmsg，避免带出歌词 accesskey、账号信息或底层地址。
        throw IOException("酷狗歌词请求失败" + code?.let { "（状态码 $it）" }.orEmpty())
    }

    fun candidate(body: JsonObject): JsonObject? {
        if (!ensureSuccess(body)) return null
        val rows = body.rows("candidates").ifEmpty { body.obj("data").rows("candidates") }
        return rows.firstOrNull { it.text("id").isNotBlank() && it.text("accesskey").isNotBlank() }
    }

    fun downloadQuery(candidate: JsonObject): Map<String, String> = mapOf(
        "ver" to "1", "client" to "android", "id" to candidate.text("id"),
        "accesskey" to candidate.text("accesskey"), "fmt" to "lrc", "charset" to "utf8",
    )

    fun decodeContent(body: JsonObject): String? {
        if (!ensureSuccess(body)) return null
        val encoded = body.text("content").ifBlank { body.obj("data").text("content") }
        if (encoded.isBlank()) return null
        val bytes = try {
            KugouCrypto.decodeBase64(encoded)
        } catch (_: IllegalArgumentException) {
            throw IOException("酷狗歌词内容编码无效，请重试")
        }
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix(0xFEFF.toChar().toString())
        } catch (_: CharacterCodingException) {
            throw IOException("酷狗歌词不是有效的 UTF-8 文本，请重试")
        }
        return text.takeIf(String::isNotBlank)
    }
}
