package com.dd3boh.outertune.utils.scanners

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.TreeDocumentFileOt
import java.io.File

fun documentFileFromUri(context: Context, uris: List<Uri>): List<DocumentFile> {
    return uris.map { customDocFileFromTreeTreeUri(context, it) }.filter { it.isDirectory }
}

fun documentFileFromUri(context: Context, uri: Uri): DocumentFile? {
    return customDocFileFromTreeTreeUri(context, uri)
}

fun stringFromUriList(uris: List<Uri>): String {
    if (uris.isEmpty()) return ""
    return uris.distinctBy { it.toString() }.joinToString("\n")
}

fun uriListFromString(str: String): List<Uri> {
    return str.split("\n").map { it.toUri() }.filter { it.toString().isNotBlank() }.distinctBy { it.toString() }
}

fun fileFromUri(context: Context, uri: Uri): File? {
    // MediaStore 媒体 URI（内置默认目录扫描、下载后端都会用到）
    if (uri.authority == MediaStore.AUTHORITY) return mediaStoreFileFromUri(context, uri)

    if (uri.authority != "com.android.externalstorage.documents") return null

    // SAF 下载文件通常是 tree/document 子 URI，而不是单独的 tree URI。
    // 两种 URI 都可以从 documentId 还原到外部存储中的实际文件路径。
    val documentId = when {
        uri.pathSegments.contains("document") -> DocumentsContract.getDocumentId(uri)
        DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
        DocumentsContract.isDocumentUri(context, uri) -> DocumentsContract.getDocumentId(uri)
        else -> return null
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val treeDocId = documentId
        val rootId: String
        val relativePath: String

        if (treeDocId.contains(":")) {
            val parts = treeDocId.split(":", limit = 2)
            rootId = parts[0]
            relativePath = parts[1]
        } else {
            rootId = treeDocId
            relativePath = ""
        }

        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

        val rootDir = if (rootId.equals("primary", ignoreCase = true)) {
            storageManager.primaryStorageVolume.directory
        } else {
            storageManager.storageVolumes.firstOrNull {
                it.uuid != null && it.uuid.equals(rootId, ignoreCase = true)
            }?.directory
        }

        return rootDir?.let { if (relativePath.isEmpty()) it else File(it, relativePath) }
    } else {
        val parts = documentId.split(":", limit = 2)

        if (parts.size < 2) return null

        val type = parts[0]
        val relativePath = parts[1]

        val rootDir = when (type.lowercase()) {
            "primary" -> Environment.getExternalStorageDirectory()
            else -> {
                // Try to handle secondary storage
                val secondaryStorage = "/storage/$type"
                if (File(secondaryStorage).exists()) {
                    File(secondaryStorage)
                } else {
                    null
                }
            }
        }

        return rootDir?.let { File(it, relativePath) }
    }
}

fun absoluteFilePathFromUri(context: Context, uri: Uri): String? {
    if (uri.authority == MediaStore.AUTHORITY) return mediaStoreFileFromUri(context, uri)?.absolutePath
    val dfUri = documentFileFromUri(context, uri)?.uri
    if (dfUri == null) return null
    return fileFromUri(context, dfUri)?.absolutePath
}

/** 从 MediaStore 媒体 URI 取回真实文件路径 */
private fun mediaStoreFileFromUri(context: Context, uri: Uri): File? = runCatching {
    context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.let(::File) else null
        }
}.getOrNull()

private fun customDocFileFromTreeTreeUri(context: Context, uri: Uri) = TreeDocumentFileOt(
    null, context, DocumentsContract.buildDocumentUriUsingTree(
        uri, DocumentsContract.getTreeDocumentId(uri)
    )
)
