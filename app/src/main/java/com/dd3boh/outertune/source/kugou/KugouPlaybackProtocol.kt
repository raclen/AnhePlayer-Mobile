package com.dd3boh.outertune.source.kugou

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.net.URI
import java.util.Locale

/** 仅包含应用生成的脱敏说明，不能包装底层异常的 URL 或响应原文。 */
internal class KugouPlaybackException(message: String) : IOException(message)

internal object KugouPlaybackProtocol {
    fun query(info: JsonObject, session: KugouSession): Map<String, String> {
        val hash = info.text("hash").lowercase(Locale.ROOT)
        return mapOf(
            "album_id" to info.text("album_id").ifBlank { "0" }, "area_code" to "1", "hash" to hash,
            "ssa_flag" to "is_fromtrack", "version" to "11430", "page_id" to "967177915", "quality" to "128",
            "album_audio_id" to info.text("album_audio_id").ifBlank { "0" }, "behavior" to "play", "pid" to "411", "cmd" to "26",
            "pidversion" to "3001", "IsFreePart" to "1", "ppage_id" to "356753938,823673182,967485191", "cdnBackup" to "1",
            "module" to "", "clientver" to "11430", "key" to KugouCrypto.streamKey(hash, session.mid, session.userId),
        )
    }

    fun urls(response: JsonObject): List<String> {
        fun collect(element: JsonElement?): List<String> = when (element) {
            is JsonPrimitive -> if (element.isString) listOf(element.content.trim()) else emptyList()
            is JsonArray -> element.flatMap(::collect)
            else -> emptyList()
        }
        // 官方音频 CDN 可能返回 HTTP。仅媒体地址接受 HTTP(S)，账号请求继续使用验证过的 HTTPS 网关。
        // 保留服务器签名地址原文，不改写协议、不向 CDN 附加账号 token 或授权请求头。
        return (collect(response["url"]) + collect(response["backupUrl"]) + collect(response["backup_url"]))
            .filter { value ->
                val uri = runCatching { URI(value) }.getOrNull()
                uri != null && uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null &&
                    (uri.port == -1 || uri.port in 1..65535)
            }.distinct()
    }

    fun unavailable(response: JsonObject): KugouPlaybackException = KugouPlaybackException(
        if (response.number("status") == 3L) "这首歌暂无可用播放权限"
        else "酷狗没有返回可播放的音频地址，请刷新推荐或在官方客户端确认歌曲权限",
    )

    fun safeFailure(error: Throwable): String? {
        var current: Throwable? = error
        repeat(12) {
            val cause = current ?: return null
            if (cause is KugouPlaybackException || cause is KugouApiException) return cause.message
            current = cause.cause
        }
        return null
    }
}
