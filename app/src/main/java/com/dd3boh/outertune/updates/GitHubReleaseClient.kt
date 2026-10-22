package com.dd3boh.outertune.updates

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class UpdateCheckException(message: String) : IOException(message)

internal object GitHubReleaseClient {
    private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun latestRelease(flavor: String): AppRelease = withContext(Dispatchers.IO) {
        val response = requestReleases()
        try {
            findLatestRelease(response, flavor)
                ?: throw UpdateCheckException("暂未找到可用的正式 APK，请稍后再试")
        } catch (error: IllegalArgumentException) {
            throw UpdateCheckException("更新信息格式异常，请稍后重试")
        }
    }

    private suspend fun requestReleases(): String = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(APP_RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "AnhePlayer-Mobile")
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) {
                            val message = when (it.code) {
                                403, 429 -> "GitHub 检查更新次数受限，请稍后重试"
                                else -> "检查更新失败（HTTP ${it.code}），请稍后重试"
                            }
                            throw UpdateCheckException(message)
                        }
                        val source = it.body.source()
                        source.request(MAX_RESPONSE_BYTES + 1)
                        if (source.buffer.size > MAX_RESPONSE_BYTES) {
                            throw UpdateCheckException("更新信息过大，请前往 GitHub 查看")
                        }
                        source.readUtf8()
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}
