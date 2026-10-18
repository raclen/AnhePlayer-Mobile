package com.dd3boh.outertune.playback.downloadManager

import android.net.Uri
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap


sealed class DownloadEvent {
    data class Progress(val mediaId: String, val bytesRead: Long, val contentLength: Long) : DownloadEvent()
    data class Success(val mediaId: String, val file: Uri) : DownloadEvent()
    data class Failure(val mediaId: String, val error: Throwable) : DownloadEvent()
}

class DownloadHttpException(val responseCode: Int) : IOException("HTTP $responseCode")

class DownloadManagerOt(
    private val local: DownloadDirectoryManagerOt,
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    // 进度事件可能非常多：缓冲区满时丢弃最旧的，保证 Success/Failure 这种终态事件能送达
    private val _events = MutableSharedFlow<DownloadEvent>(
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = _events.asSharedFlow()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap<String, Call>()

    fun enqueue(
        mediaId: String,
        url: String,
        displayName: String? = null,
        abort: Boolean = false,
        fileExtension: String = ".mka",
    ) {

        // if already exists, immediately emit success
        local.getFilePathIfExists(mediaId)?.let {
            _events.tryEmit(DownloadEvent.Success(mediaId, it))
            return
        }

        if (abort) {
            _events.tryEmit(DownloadEvent.Failure(mediaId, Exception("Could not resolve download: $displayName")))
            return
        }

        jobs.remove(mediaId)?.cancel()
        jobs[mediaId] = scope.launch {
            // 部分音源 CDN 会根据 UA 拒绝请求，这里用常见浏览器 UA 避免 403
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", DOWNLOAD_USER_AGENT)
                .header("Accept", "*/*")
                .build()
            try {
                val call = httpClient.newCall(request)
                calls[mediaId] = call
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw DownloadHttpException(resp.code)
                    }
                    val body = resp.body
                    val total = body.contentLength()
                    var downloaded = 0L

                    // wrap the source to track progress
                    val source = body.byteStream()
                    val countingStream = object : InputStream() {
                        private var lastEmitAt = 0L

                        private fun emitProgress() {
                            // 限频，避免大量小读把事件队列刷满
                            val now = System.currentTimeMillis()
                            if (now - lastEmitAt < PROGRESS_EMIT_INTERVAL_MS) return
                            lastEmitAt = now
                            _events.tryEmit(DownloadEvent.Progress(mediaId, downloaded, total))
                        }

                        override fun read(): Int {
                            val byte = source.read()
                            if (byte >= 0) {
                                downloaded++
                                emitProgress()
                            }
                            return byte
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val count = source.read(b, off, len)
                            if (count > 0) {
                                downloaded += count
                                emitProgress()
                            }
                            return count
                        }

                        override fun close() {
                            source.close()
                        }
                    }


                    // save to disk
                    val saved = local.saveFile(
                        mediaId,
                        countingStream,
                        displayName = displayName,
                        fileExtension = fileExtension,
                    )
                    if (saved != null) {
                        _events.tryEmit(DownloadEvent.Success(mediaId, saved))
                    } else {
                        throw IOException("Failed to save file")
                    }
                }
            } catch (e: Throwable) {
                reportException(e)
                _events.tryEmit(DownloadEvent.Failure(mediaId, e))
            } finally {
                calls.remove(mediaId)
                jobs.remove(mediaId)
            }
        }
    }

    fun enqueue(
        mediaId: String,
        data: ByteArray,
        displayName: String? = null,
        fileExtension: String = ".mka",
    ) {
        // if already exists, immediately emit success
        local.getFilePathIfExists(mediaId)?.let {
            _events.tryEmit(DownloadEvent.Success(mediaId, it))
            return
        }

        try {
            val total = data.size.toLong()
            var downloaded = 0L

            // wrap the source to track progress
            val source = data.inputStream()
            val countingStream = object : InputStream() {
                override fun read(): Int {
                    val byte = source.read()
                    if (byte >= 0) {
                        downloaded++
                        _events.tryEmit(DownloadEvent.Progress(mediaId, downloaded, total))
                    }
                    return byte
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val count = source.read(b, off, len)
                    if (count > 0) {
                        downloaded += count
                        _events.tryEmit(DownloadEvent.Progress(mediaId, downloaded, total))
                    }
                    return count
                }

                override fun close() {
                    source.close()
                }
            }

            // save to disk
            val saved = local.saveFile(
                mediaId,
                countingStream,
                displayName = displayName,
                fileExtension = fileExtension,
            )
            if (saved != null) {
                _events.tryEmit(DownloadEvent.Success(mediaId, saved))
            } else {
                throw IOException("Failed to save file")
            }
        } catch (e: Throwable) {
            _events.tryEmit(DownloadEvent.Failure(mediaId, e))
        }
    }

    fun enqueueAll(pairs: List<Pair<String, String>>) {
        pairs.forEach { (id, url) -> enqueue(id, url) }
    }

    fun cancel(mediaId: String) {
        calls.remove(mediaId)?.cancel()
        jobs.remove(mediaId)?.cancel()
    }

    fun cancelAll() {
        (calls.keys + jobs.keys).forEach(::cancel)
    }

    fun getFilePath(mediaId: String): Uri? = local.getFilePathIfExists(mediaId)

    companion object {
        /** 进度事件最小间隔 */
        private const val PROGRESS_EMIT_INTERVAL_MS = 200L

        private const val DOWNLOAD_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
