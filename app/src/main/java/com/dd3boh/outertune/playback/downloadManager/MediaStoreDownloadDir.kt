package com.dd3boh.outertune.playback.downloadManager

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * 内置默认下载目录：内部存储 `Music/AnhePlayer`，兼容读取 `Music/OuterTune`
 *
 * Android 10+ 走 MediaStore 写入，不需要任何 SAF 授权，也不需要存储写权限；
 * Android 9 及以下退回直接写文件 + 通知媒体库扫描。
 * 用户如果在设置里选择了自定义目录，则不会使用这个后端。
 */
class MediaStoreDownloadDir(private val context: Context) {
    private val tag = "MediaStoreDownloadDir"
    private val resolver = context.contentResolver

    /** 相对路径（带结尾 /），MediaStore 要求 */
    private val relativePath: String = "${Environment.DIRECTORY_MUSIC}/AnhePlayer/"

    /** 旧品牌目录仅用于兼容读取，不搬迁或自动清理文件 */
    private val legacyRelativePath: String = "${Environment.DIRECTORY_MUSIC}/OuterTune/"

    /** mediaId -> uri 缓存，避免频繁查询 */
    private val cache = HashMap<String, Uri>()

    fun getDisplayPath(): String = "/storage/emulated/0/$relativePath"

    fun saveFile(
        mediaId: String,
        input: InputStream,
        displayName: String?,
        fileExtension: String = ".mka",
    ): Uri? {
        val extension = fileExtension.removePrefix(".").ifBlank { "mka" }
        val mimeType = when (extension) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            else -> "audio/mka"
        }
        val fileName = "${displayName ?: mediaId} [$mediaId].$extension"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(fileName, mimeType, input).also { uri ->
                if (uri != null) cache[mediaId] = uri
            }
        } else {
            saveViaFile(fileName, input).also { uri ->
                if (uri != null) cache[mediaId] = uri
            }
        }
    }

    private fun saveViaMediaStore(fileName: String, mimeType: String, input: InputStream): Uri? {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                input.copyTo(out)
            } ?: throw IOException("Cannot open output stream")
            val done = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    private fun saveViaFile(fileName: String, input: InputStream): Uri? {
        val dir = File(Environment.getExternalStorageDirectory(), relativePath)
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Invalid directory")
        }
        val file = File(dir, fileName)
        file.outputStream().use { out -> input.copyTo(out) }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
        return Uri.fromFile(file)
    }

    fun getFilePathIfExists(mediaId: String): Uri? {
        cache[mediaId]?.let { if (exists(it)) return it }
        return getAvailableFiles()[mediaId]
    }

    fun deleteFile(mediaId: String): Boolean {
        val uri = getFilePathIfExists(mediaId) ?: return false
        cache.remove(mediaId)
        return runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
    }

    /** 已下载文件：mediaId -> uri */
    fun getAvailableFiles(): Map<String, Uri> {
        val result = HashMap<String, Uri>()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        // 先读旧目录再读新目录，相同 mediaId 优先使用新目录文件。
        for (path in listOf(legacyRelativePath, relativePath)) {
            val selection: String
            val args: Array<String>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
                args = arrayOf("$path%")
            } else {
                selection = "${MediaStore.Audio.Media.DATA} LIKE ?"
                args = arrayOf("%/$path%")
            }
            runCatching {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME),
                    selection,
                    args,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0)
                        val name = cursor.getString(1) ?: continue
                        val mediaId = name.substringAfterLast('[', "").substringBeforeLast(']', "")
                        if (mediaId.isBlank()) continue
                        result[mediaId] = android.content.ContentUris.withAppendedId(collection, id)
                    }
                }
            }.onFailure { Log.w(tag, "查询默认下载目录失败: ${it.message}") }
        }
        cache.putAll(result)
        return result
    }

    fun getUsage(): Long {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            args = arrayOf("$relativePath%", "$legacyRelativePath%")
        } else {
            selection = "${MediaStore.Audio.Media.DATA} LIKE ? OR ${MediaStore.Audio.Media.DATA} LIKE ?"
            args = arrayOf("%/$relativePath%", "%/$legacyRelativePath%")
        }
        return runCatching {
            resolver.query(
                collection,
                arrayOf(MediaStore.Audio.Media.SIZE),
                selection,
                args,
                null,
            )?.use { cursor ->
                var total = 0L
                while (cursor.moveToNext()) total += cursor.getLong(0)
                total
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun exists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null)?.use { it.moveToFirst() }
            ?: false
    }.getOrDefault(false)
}
