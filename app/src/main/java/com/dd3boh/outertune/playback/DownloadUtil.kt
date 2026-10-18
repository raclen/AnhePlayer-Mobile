package com.dd3boh.outertune.playback

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.DownloadExtraPathKey
import com.dd3boh.outertune.constants.DownloadPathKey
import com.dd3boh.outertune.constants.DownloadQualityKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.PlaylistSong
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.db.entities.SongEntity
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.playback.downloadManager.DownloadDirectoryManagerOt
import com.dd3boh.outertune.playback.downloadManager.DownloadEvent
import com.dd3boh.outertune.playback.downloadManager.DownloadHttpException
import com.dd3boh.outertune.playback.downloadManager.DownloadManagerOt
import com.dd3boh.outertune.source.lx.LxSourceRuntime
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.dlCoroutine
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.InvalidAudioFileException
import com.dd3boh.outertune.utils.scanners.fileFromUri
import com.dd3boh.outertune.utils.scanners.uriListFromString
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 一次下载失败（带可读原因，用于提示用户） */
data class DownloadFailure(
    val songId: String,
    val title: String,
    val reason: String,
)

/** 直接将洛雪解析后的音频写入用户选择的下载目录，不使用 Media3 下载缓存。 */
@Singleton
class DownloadUtil @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase,
    private val metadataWriter: DownloadedMetadataWriter,
) {
    private val tag = DownloadUtil::class.simpleName.orEmpty()
    val downloads = MutableStateFlow<Map<String, LocalDateTime>>(emptyMap())
    var isProcessingDownloads = MutableStateFlow(false)
    private val pendingDownloadRequests = ConcurrentHashMap<String, PendingDownloadRequest>()

    /** 失败歌曲的歌信息缓存，供「重试」使用（不依赖数据库里一定存在） */
    private val failedDownloadRequests = ConcurrentHashMap<String, MediaMetadata>()

    /** 下载失败提示事件：短时间内的多首失败会被合并成一批 */
    val downloadFailures = MutableSharedFlow<List<DownloadFailure>>(extraBufferCapacity = 8)
    private val failureBuffer = mutableListOf<DownloadFailure>()
    private var failureFlushJob: Job? = null

    var localMgr = DownloadDirectoryManagerOt(
        context,
        context.dataStore.get(DownloadPathKey, "").toUri(),
        uriListFromString(context.dataStore.get(DownloadExtraPathKey, "")),
    )
    val downloadMgr = DownloadManagerOt(localMgr)

    fun getDownload(songId: String): Flow<LocalDateTime?> = downloads.map { it[songId] }

    fun download(songs: List<MediaMetadata>) {
        songs.forEach(::download)
    }

    fun download(song: MediaMetadata) {
        enqueueDownload(song)
    }

    fun download(song: SongEntity) {
        CoroutineScope(dlCoroutine).launch {
            database.song(song.id).first()?.toMediaMetadata()?.let(::enqueueDownload)
        }
    }

    /** 直接下载无需恢复 Media3 任务；未完成任务由应用本次进程管理。 */
    fun resumeDownloadsOnStart() = Unit

    fun delete(song: PlaylistSong) = deleteSong(song.song.id)

    fun delete(song: Song) = deleteSong(song.song.id)

    fun delete(song: SongEntity) = deleteSong(song.id)

    fun delete(song: MediaMetadata) = deleteSong(song.id)

    fun delete(songId: String) = deleteSong(songId)

    fun clearDownloads() {
        downloadMgr.cancelAll()
        localMgr.getAvailableFiles(false).keys.forEach(localMgr::deleteFile)
        downloads.value = emptyMap()
        database.transaction {
            removeAllDownloadedSongs()
        }
    }

    fun cd() {
        localMgr.doInit(
            context,
            context.dataStore.get(DownloadPathKey, "").toUri(),
            uriListFromString(context.dataStore.get(DownloadExtraPathKey, "")),
        )
    }

    /** 根据所选目录重新发现离线文件。 */
    suspend fun scanDownloads() {
        if (isProcessingDownloads.value) return
        isProcessingDownloads.value = true
        try {
            val now = LocalDateTime.now()
            val discovered = mutableMapOf<String, LocalDateTime>()
            val availableFiles = localMgr.getAvailableFiles(false)
            val existingBeforeScan = database.downloadedOrQueuedSongs().first()

            // 目录权限暂时失效或目录尚未初始化时不要清空数据库，否则下载列表会瞬间消失。
            if (localMgr.allDirs.isEmpty()) return
            // 扫描结果为空通常意味着 SAF 临时权限/IO 异常；保留已有记录等待下次扫描。
            if (availableFiles.isEmpty() && existingBeforeScan.isNotEmpty()) {
                val activeDownloads = downloads.value.filterValues { it == STATE_DOWNLOADING }
                downloads.value = activeDownloads + existingBeforeScan
                    .filter { it.song.dateDownload != null }
                    .associate { it.song.id to it.song.dateDownload!! }
                return
            }

            availableFiles.forEach { (mediaId, uri) ->
                try {
                    val file = fileFromUri(context, uri)
                        ?: throw InvalidAudioFileException("无法访问下载文件")
                    database.registerDownloadSong(mediaId, now, file.absolutePath)
                    discovered[mediaId] = now
                } catch (error: InvalidAudioFileException) {
                    reportException(error)
                }
            }

            val activeDownloads = downloads.value
                .filterValues { it == STATE_DOWNLOADING }
            val existing = database.downloadedOrQueuedSongs().first()
            database.transaction {
                existing
                    .filter { it.song.dateDownload != null && it.song.id !in discovered && it.song.id !in activeDownloads }
                    .forEach { removeDownloadSong(it.song.id) }
            }
            downloads.value = activeDownloads + discovered
        } finally {
            isProcessingDownloads.value = false
        }
    }

    suspend fun rescanDownloads() {
        if (isProcessingDownloads.value) return
        isProcessingDownloads.value = true
        try {
            val activeDownloads = downloads.value.filterValues { it == STATE_DOWNLOADING }
            val databaseDownloads = database.downloadedOrQueuedSongs().first()
            val foundIds = if (localMgr.allDirs.isEmpty()) {
                emptySet()
            } else {
                localMgr.getAvailableFiles(false).keys
            }
            if (localMgr.allDirs.isNotEmpty()) {
                val missing = databaseDownloads.filter {
                    it.song.dateDownload != null && it.song.id !in foundIds && downloads.value[it.song.id] != STATE_DOWNLOADING
                }
                database.transaction { missing.forEach { removeDownloadSong(it.song.id) } }
            }
            downloads.value = activeDownloads + databaseDownloads
                .filter { it.song.dateDownload != null && (localMgr.allDirs.isEmpty() || it.song.id in foundIds) }
                .associate { it.song.id to it.song.dateDownload!! }
        } finally {
            isProcessingDownloads.value = false
        }
    }

    private fun enqueueDownload(song: MediaMetadata) {
        if (downloads.value.containsKey(song.id)) return
        // 下载目录没设置时直接报错，不然要等解析完、下完才在保存阶段失败
        if (localMgr.mainDir?.isDirectory != true) {
            failedDownloadRequests[song.id] = song
            reportDownloadFailure(
                mediaId = song.id,
                title = song.title,
                reason = context.getString(R.string.download_error_no_directory),
            )
            return
        }
        if (song.source == null) {
            // 没有可用的音源信息：以前这里是静默 return，用户点下载毫无反应
            failedDownloadRequests[song.id] = song
            reportDownloadFailure(
                mediaId = song.id,
                title = song.title,
                reason = context.getString(R.string.download_error_no_source),
            )
            return
        }

        val quality = context.dataStore.get(DownloadQualityKey, DEFAULT_DOWNLOAD_QUALITY)
        failedDownloadRequests.remove(song.id)
        pendingDownloadRequests[song.id] = PendingDownloadRequest(song, downloadQualityFallbacks(quality))
        downloads.update { it + (song.id to STATE_DOWNLOADING) }
        startDownload(song.id)
    }

    private fun startDownload(mediaId: String) {
        val pending = pendingDownloadRequests[mediaId] ?: return
        CoroutineScope(dlCoroutine).launch {
            try {
                database.transaction { insert(pending.song) }
                val quality = pending.currentQuality
                val streamUrl = LxSourceRuntime.resolve(pending.song, qualityOverride = quality)
                downloadMgr.enqueue(
                    mediaId = mediaId,
                    url = streamUrl,
                    displayName = pending.song.title,
                    fileExtension = downloadFileExtension(quality),
                )
            } catch (error: Throwable) {
                reportException(error)
                if (!retryDownload(mediaId, error)) markDownloadFailed(mediaId, error)
            }
        }
    }

    private fun deleteSong(id: String): Boolean {
        val wasPending = downloads.value[id] == STATE_DOWNLOADING
        pendingDownloadRequests.remove(id)
        failedDownloadRequests.remove(id)
        downloadMgr.cancel(id)
        val deleted = localMgr.deleteFile(id)
        downloads.update { it - id }
        database.transaction { removeDownloadSong(id) }
        return deleted || wasPending
    }

    private suspend fun handleDownloadSuccess(mediaId: String, uri: android.net.Uri) {
        val downloadedAt = LocalDateTime.now()
        val file = fileFromUri(context, uri)
        val song = pendingDownloadRequests[mediaId]?.song
            ?: database.song(mediaId).first()?.toMediaMetadata()
        if (file != null && song != null) {
            runCatching { metadataWriter.write(file, song) }
                .onFailure { reportException(it) }
        }
        pendingDownloadRequests.remove(mediaId)
        database.transaction {
            if (file != null) {
                registerDownloadSong(mediaId, downloadedAt, file.absolutePath)
            } else {
                updateDownloadStatus(mediaId, downloadedAt)
            }
        }
        downloads.update { it + (mediaId to downloadedAt) }
    }

    /**
     * 重新下载一首之前失败的歌曲（供失败提示的「重试」按钮使用）
     */
    fun retryFailedDownload(songId: String) {
        if (downloads.value[songId] == STATE_DOWNLOADING) return
        CoroutineScope(dlCoroutine).launch {
            val metadata = failedDownloadRequests[songId]
                ?: database.song(songId).first()?.toMediaMetadata()
            if (metadata == null) {
                reportDownloadFailure(
                    mediaId = songId,
                    title = songId,
                    reason = context.getString(R.string.download_error_no_song),
                )
            } else {
                enqueueDownload(metadata)
            }
        }
    }

    private fun markDownloadFailed(mediaId: String, error: Throwable?) {
        val pending = pendingDownloadRequests[mediaId]
        val title = pending?.song?.title
        pending?.song?.let { failedDownloadRequests[mediaId] = it }
        pendingDownloadRequests.remove(mediaId)
        downloads.update { it - mediaId }
        database.transaction { updateDownloadStatus(mediaId, null) }

        reportDownloadFailure(
            mediaId = mediaId,
            title = title ?: mediaId,
            reason = describeDownloadError(error),
        )
    }

    /** 把异常翻译成用户能看懂的原因 */
    private fun describeDownloadError(error: Throwable?): String = when {
        error == null -> context.getString(R.string.download_error_unknown)
        error is DownloadHttpException ->
            if (error.responseCode == HTTP_NOT_FOUND) {
                // 已经把所有音质都试过了
                context.getString(R.string.download_error_not_found)
            } else {
                context.getString(R.string.download_error_http, error.responseCode)
            }

        error is TimeoutCancellationException -> context.getString(R.string.download_error_resolve_timeout)
        error is SocketTimeoutException -> context.getString(R.string.download_error_timeout)
        error is UnknownHostException -> context.getString(R.string.download_error_network)
        error is IOException &&
                (error.message?.contains("Invalid directory", true) == true ||
                        error.message?.contains("Failed to initiate download manager", true) == true) ->
            context.getString(R.string.download_error_no_directory)

        error is IOException &&
                error.message?.let { it.contains("save file", true) || it.contains("output stream", true) } == true ->
            context.getString(R.string.download_error_save)

        else -> error.message?.takeIf { it.isNotBlank() }
            ?: error::class.simpleName
            ?: context.getString(R.string.download_error_unknown)
    }

    /** 记录一次下载失败，并在短暂延迟后合并成一条提示事件 */
    private fun reportDownloadFailure(mediaId: String, title: String, reason: String) {
        Log.w(tag, "下载失败：$title ($mediaId) - $reason")
        synchronized(failureBuffer) {
            failureBuffer.removeAll { it.songId == mediaId }
            failureBuffer.add(DownloadFailure(mediaId, title, reason))
        }
        failureFlushJob?.cancel()
        failureFlushJob = CoroutineScope(dlCoroutine).launch {
            delay(FAILURE_FLUSH_DELAY_MS)
            val batch = synchronized(failureBuffer) {
                val snapshot = failureBuffer.toList()
                failureBuffer.clear()
                snapshot
            }
            if (batch.isNotEmpty()) downloadFailures.emit(batch)
        }
    }

    /**
     * 失利降级：HTTP 404（某音质没资源）或音源解析失败时换下一档音质重试
     */
    private fun retryDownload(mediaId: String, error: Throwable): Boolean {
        val pending = pendingDownloadRequests[mediaId] ?: return false
        if (!isRetryableDownloadError(error)) return false
        if (!pending.moveToNextQuality()) return false

        Log.w(tag, "下载失败，改用 ${pending.currentQuality} 重试：$mediaId（${error.message}）")
        startDownload(mediaId)
        return true
    }

    private fun isRetryableDownloadError(error: Throwable): Boolean {
        // 配置类错误重试多少次都没用
        val message = error.message.orEmpty()
        if (message.contains("尚未初始化") || message.contains("缺少洛雪")) return false

        return when (error) {
            is SocketTimeoutException -> false
            is DownloadHttpException -> error.responseCode == HTTP_NOT_FOUND
            is IllegalStateException, is IllegalArgumentException -> true
            is IOException -> false
            else -> false
        }
    }

    init {
        CoroutineScope(dlCoroutine).launch {
            downloadMgr.events.collect { event ->
                when (event) {
                    is DownloadEvent.Progress -> {
                        downloads.update { current ->
                            if (current[event.mediaId] == null) current + (event.mediaId to STATE_DOWNLOADING) else current
                        }
                    }
                    is DownloadEvent.Success -> handleDownloadSuccess(event.mediaId, event.file)
                    is DownloadEvent.Failure -> {
                        if (!retryDownload(event.mediaId, event.error)) {
                            markDownloadFailed(event.mediaId, event.error)
                        }
                    }
                }
            }
        }
        CoroutineScope(dlCoroutine).launch { rescanDownloads() }
    }

    companion object {
        val STATE_DOWNLOADING: LocalDateTime = Instant.ofEpochMilli(1).atZone(ZoneOffset.UTC).toLocalDateTime()
        private const val HTTP_NOT_FOUND = 404

        /** 下载失败提示的合井窗口 */
        private const val FAILURE_FLUSH_DELAY_MS = 800L
    }
}

private const val DEFAULT_DOWNLOAD_QUALITY = "320k"

private class PendingDownloadRequest(
    val song: MediaMetadata,
    private val qualities: List<String>,
) {
    private var qualityIndex = 0

    val currentQuality: String
        get() = qualities[qualityIndex]

    @Synchronized
    fun moveToNextQuality(): Boolean {
        if (qualityIndex >= qualities.lastIndex) return false
        qualityIndex++
        return true
    }
}

private fun downloadQualityFallbacks(quality: String): List<String> = when (quality) {
    "flac24bit" -> listOf("flac24bit", "flac", "320k", "128k")
    "flac" -> listOf("flac", "320k", "128k")
    "320k" -> listOf("320k", "128k")
    else -> listOf("128k")
}

private fun downloadFileExtension(quality: String): String =
    if (quality == "flac" || quality == "flac24bit") ".flac" else ".mp3"
