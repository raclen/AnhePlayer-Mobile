package com.dd3boh.outertune.playback

import android.content.Context
import android.net.Uri
import android.util.Log
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.lyrics.LyricsHelper
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.lx.LxMusicInfo
import com.dd3boh.outertune.source.lx.LxSearchClient
import com.dd3boh.outertune.source.lx.LxSourceRuntime
import com.dd3boh.outertune.source.lx.lxJson
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 将下载歌曲的封面和歌词写入音频文件，失败时不影响已下载文件。 */
@Singleton
class DownloadedMetadataWriter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
    private val lyricsHelper: LyricsHelper,
) {
    private val httpClient = OkHttpClient()

    suspend fun write(uri: Uri, song: MediaMetadata) = withContext(Dispatchers.IO) {
        val cover = fetchCover(song)
        val lyrics = fetchLyrics(song)

        // 关键：必须通过 contentResolver 拿可写 fd。
        // 直接 ParcelFileDescriptor.open(File) 在分区存储下对 SAF/MediaStore 创建的文件会被拒绝，
        // 导致封面与歌词静默丢失。
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val propertyMap = TagLib.getMetadata(pfd.dup().detachFd(), readPictures = false)?.propertyMap
                    ?: HashMap()
                propertyMap["TITLE"] = arrayOf(song.title)
                song.artists.joinToString(" / ") { it.name }
                    .takeIf(String::isNotBlank)
                    ?.let { propertyMap["ARTIST"] = arrayOf(it) }
                song.album?.title
                    ?.takeIf(String::isNotBlank)
                    ?.let { propertyMap["ALBUM"] = arrayOf(it) }
                lyrics?.let { propertyMap["LYRICS"] = arrayOf(it) }
                runCatching { TagLib.savePropertyMap(pfd.dup().detachFd(), propertyMap) }
                    .onFailure { Log.w(TAG, "保存文本元数据失败", it) }
                cover?.let {
                    runCatching { TagLib.savePictures(pfd.dup().detachFd(), arrayOf(it)) }
                        .onFailure { error -> Log.w(TAG, "保存封面失败", error) }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "写入下载歌曲元数据失败: $uri", error)
        }
    }

    private suspend fun fetchCover(song: MediaMetadata): Picture? {
        val musicInfo = song.sourceData
            ?.let { runCatching { lxJson.decodeFromString<LxMusicInfo>(it) }.getOrNull() }
        val coverUrls = buildList {
            // 洛雪音源提供的封面优先
            if (song.source != null) {
                runCatching { LxSourceRuntime.resolvePic(song) }.getOrNull()?.let(::add)
            }
            song.thumbnailUrl?.let(::add)
            musicInfo?.let { runCatching { LxSearchClient.loadCover(it) }.getOrNull() }?.let(::add)
        }.distinct()
        for (coverUrl in coverUrls) {
            fetchCover(coverUrl)?.let { return it }
        }
        return null
    }

    private fun fetchCover(coverUrl: String): Picture? {
        val request = Request.Builder()
            .url(coverUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/*,*/*")
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body.bytes()
                if (bytes.isEmpty()) return@use null
                Picture(
                    data = bytes,
                    description = "Front Cover",
                    pictureType = "Front Cover",
                    mimeType = response.header("Content-Type")
                        ?.substringBefore(';')
                        ?.takeIf { it.startsWith("image/") }
                        ?: guessImageMimeType(bytes),
                )
            }
        }.getOrNull()
    }

    private suspend fun fetchLyrics(song: MediaMetadata): String? {
        fun String?.validLyrics() = this?.takeUnless {
            it.isBlank() || it == LyricsEntity.LYRICS_NOT_FOUND
        }

        // 洛雪音源优先（用户要求歌词只用洛雪那套）
        if (song.source != null) {
            runCatching { LxSourceRuntime.resolveLyric(song) }
                .getOrNull()
                .validLyrics()
                ?.let { return it }
        }

        database.lyrics(song.id).first()?.lyrics.validLyrics()?.let { return it }

        // 洛雪没结果时再用 LyricsHelper（已去除酷狗源）兜底
        val fetched = runCatching { lyricsHelper.getLyrics(song) }.getOrNull()
        if (fetched == null) {
            return database.lyrics(song.id).first()?.lyrics.validLyrics()
        }

        // LyricsHelper 异步写入远程结果，等待 Room 通知，避免首次下载漏写歌词。
        return withTimeoutOrNull(2_000) {
            database.lyrics(song.id)
                .map { it?.lyrics.validLyrics() }
                .filterNotNull()
                .first()
        }
    }

    private companion object {
        const val TAG = "DownloadedMetadata"
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun guessImageMimeType(bytes: ByteArray): String = when {
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "image/png"
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
            else -> "image/jpeg"
        }
    }
}
