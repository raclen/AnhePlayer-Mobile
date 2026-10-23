package com.dd3boh.outertune.playback.downloadManager

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scanDfRecursive
import com.dd3boh.outertune.utils.scanners.documentFileFromUri
import java.io.IOException
import java.io.InputStream

class DownloadDirectoryManagerOt(private var context: Context, private var dir: Uri, extraDirs: List<Uri>) {
    val TAG = DownloadDirectoryManagerOt::class.simpleName.toString()
    var mainDir: DocumentFile? = null
    var allDirs: List<DocumentFile> = mutableListOf()

    /** 未设置自定义目录时写入 Music/AnhePlayer，兼容读取 Music/OuterTune（MediaStore，免授权） */
    private var builtinDir: MediaStoreDownloadDir? = null

    var availableFiles: Set<DocumentFile> = mutableSetOf()

    init {
        doInit(context, dir, extraDirs)
    }

    /** 是否已经具备可写入的下载目录 */
    val isReady: Boolean
        get() = builtinDir != null || mainDir?.isDirectory == true

    /** 当前生效的下载目录描述 */
    val activeDirDescription: String
        get() = builtinDir?.getDisplayPath() ?: dir.toString()

    fun doInit(context: Context, dir: Uri, extraDirs: List<Uri>) {
        Log.i(TAG, "Initializing download manager: $dir")
        this.context = context
        this.dir = dir

        // 空目录 = 使用内置默认目录，不需要任何授权
        if (dir.toString().isBlank()) {
            builtinDir = MediaStoreDownloadDir(context)
            mainDir = null
            allDirs = documentFileFromUri(context, extraDirs).filter { it.isDirectory }
            Log.i(TAG, "使用内置默认下载目录: ${builtinDir?.getDisplayPath()}")
            return
        }
        builtinDir = null

        try {
            mainDir = documentFileFromUri(context, dir)
            if (mainDir == null || !mainDir!!.isDirectory) {
                throw IOException("Invalid directory")
            }

            // TODO: .nomedia for downloads folder (permission denied)
//            if (!mainDir!!.listFiles().any { it.name == ".nomedia" }) {
//                documentFileFromUri(context, dir)?.createFile("audio/mka", ".nomedia")
//            }

            val newAllDirs = mutableListOf<DocumentFile>()
            newAllDirs.add(mainDir!!)
            if (extraDirs.isNotEmpty()) {
                newAllDirs.addAll(
                    documentFileFromUri(context, extraDirs.filterNot { it == dir }).filter { it.isDirectory }
                )
            }
            allDirs = newAllDirs.toList()
            Log.i(TAG, "Download manager initialized successfully. ${allDirs.size}")
        } catch (e: Exception) {
            if (mainDir == null) {
                Log.w(TAG, "Failed to initiate download manager: No directory provided")
            } else if (!mainDir!!.isDirectory) {
                Log.w(TAG, "Failed to initiate download manager: Not a valid directory")
            } else {
                Log.e(TAG, "Failed to initiate download manager: " + e.message)
            }

            mainDir = null
            allDirs = mutableListOf()
//            reportException(e)
//            Toast.makeText(context, "Failed to initiate download manager: " + e.message, Toast.LENGTH_LONG).show()
            // TODO: snackbar for failed uri or not set up?
        }
    }

    fun deleteFile(mediaId: String): Boolean {
        builtinDir?.let { return it.deleteFile(mediaId) }
        val file = isExists(mediaId)
        val deleted = file?.delete() == true
        if (deleted) {
            availableFiles = availableFiles - file
        }
        return deleted
    }

    fun saveFile(
        mediaId: String,
        input: InputStream,
        displayName: String?,
        fileExtension: String = ".mka",
    ): Uri? {
        builtinDir?.let { return it.saveFile(mediaId, input, displayName, fileExtension) }

        val resolver = context.contentResolver
        val directory = mainDir

        if (directory == null || !directory.isDirectory) {
            throw IOException("Invalid directory")
        }

        val extension = fileExtension.removePrefix(".").ifBlank { "mka" }
        val mimeType = when (extension) {
            "flac" -> "audio/flac"
            "mp3" -> "audio/mpeg"
            else -> "audio/mka"
        }
        val fileName = "$displayName [$mediaId].$extension"
        val newFile = directory.createFile(mimeType, fileName)

        newFile?.uri?.let { uri ->
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    input.copyTo(out)
                } ?: throw IOException("Cannot open output stream")
                availableFiles = availableFiles + newFile
                return uri
            } catch (error: Throwable) {
                newFile.delete()
                throw error
            }
        }

        return null
    }

    fun isExists(mediaId: String): DocumentFile? {
        return availableFiles.find { it.downloadedMediaId() == mediaId }
    }

    fun getFilePathIfExists(mediaId: String): Uri? {
        builtinDir?.let { return it.getFilePathIfExists(mediaId) }
        return isExists(mediaId)?.uri
    }

    fun getAvailableFiles() = getAvailableFiles(true)

    fun getAvailableFiles(useCache: Boolean = true): Map<String, Uri> {
        val availableFiles = HashMap<String, Uri>()
        builtinDir?.let { builtin ->
            availableFiles.putAll(builtin.getAvailableFiles())
        }
        val result = ArrayList<DocumentFile>()
        if (builtinDir != null) {
            // 内置目录下额外目录仍然走 SAF
            for (dir in allDirs) {
                scanDfRecursive(dir, result, true)
            }
        } else if (useCache) {
            result.addAll(this.availableFiles.toList())
        } else {
            for (dir in allDirs) {
                scanDfRecursive(dir, result, true)
            }
        }

        for (file in result) {
            val path = file.name ?: continue
            availableFiles[path.substringAfterLast('[').substringBeforeLast(']')] = file.uri
        }
        if (!useCache) {
            this.availableFiles = result.toSet()
        }
        return availableFiles
    }

    fun getMainDlStorageUsage(): Long {
        builtinDir?.let { return it.getUsage() }
        if (mainDir == null) return -1L
        val result = ArrayList<DocumentFile>()
        scanDfRecursive(mainDir!!, result, true)

        return result.filter { it.name != null }.sumOf { it.length() }
    }

    fun getExtraDlStorageUsage(): Long {
        val dirs = allDirs.filter { it != mainDir }
        if (dirs.isEmpty()) return 0
        val result = ArrayList<DocumentFile>()
        for (dir in dirs) {
            scanDfRecursive(dir, result, true)
        }

        return result.filter { it.name != null }.sumOf { it.length() }
    }
}

private fun DocumentFile.downloadedMediaId(): String? = name
    ?.substringAfterLast('[', missingDelimiterValue = "")
    ?.substringBeforeLast(']', missingDelimiterValue = "")
    ?.takeIf { it.isNotBlank() }
