package com.dd3boh.outertune.source.lx

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * 洛雪音源在线导入：校验链接、下载脚本内容并解析显示名称。
 *
 * 只接受指向 `.js` 的 http/https 链接，避免把任意网页内容当成脚本保存。
 */
object LxSourceInstaller {
    private const val MAX_SCRIPT_BYTES = 2L * 1024 * 1024
    private const val FALLBACK_NAME = "洛雪自定义音源"

    private val httpClient = OkHttpClient()

    data class ImportedScript(
        val name: String,
        val script: String,
    )

    suspend fun download(url: String): Result<ImportedScript> = withContext(Dispatchers.IO) {
        runCatching {
            val trimmed = url.trim()
            val uri = runCatching { URI(trimmed) }
                .getOrElse { error("链接格式不正确") }
            val scheme = uri.scheme?.lowercase()
            check(scheme == "http" || scheme == "https") { "只支持 http/https 链接" }
            check(uri.path.orEmpty().endsWith(".js", ignoreCase = true)) { "链接需要指向 .js 音源脚本" }

            val request = Request.Builder().url(trimmed).get().build()
            httpClient.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "下载失败：HTTP ${response.code}" }
                val body = response.body
                val declaredLength = body.contentLength()
                check(declaredLength <= MAX_SCRIPT_BYTES) { "音源脚本超过大小限制" }
                val bytes = body.bytes()
                check(bytes.size.toLong() <= MAX_SCRIPT_BYTES) { "音源脚本超过大小限制" }
                val script = String(bytes, StandardCharsets.UTF_8)
                check(script.isNotBlank()) { "音源脚本内容为空" }
                ImportedScript(name = resolveName(uri), script = script)
            }
        }
    }

    private fun resolveName(uri: URI): String {
        val rawName = uri.path.orEmpty().substringAfterLast('/')
        val decoded = runCatching {
            URLDecoder.decode(rawName, StandardCharsets.UTF_8.name())
        }.getOrDefault(rawName)
        return decoded.takeIf(String::isNotBlank) ?: FALLBACK_NAME
    }
}
