/*
 * Copyright (C) 2025 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.utils.scanners

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.dd3boh.outertune.constants.scannerWhitelistExts
import com.dd3boh.outertune.utils.reportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一个「含音乐的文件夹」
 *
 * @param uri 文件夹的 SAF document URI，也是隐藏状态的唯一标识
 * @param path 用于显示的路径（能解析出真实路径时是绝对路径，否则是 URI 字符串）
 * @param directSongCount 直接存放在该文件夹里的音乐数量
 * @param songCount 该文件夹及所有子文件夹里的音乐数量（≥ directSongCount）
 */
data class MusicFolderInfo(
    val uri: Uri,
    val path: String,
    val directSongCount: Int,
    val songCount: Int,
)

/**
 * 扫描「扫描路径」下的所有文件夹，统计其中含音乐的文件夹，供用户挑选要隐藏的文件夹
 *
 * 结果与进度以 StateFlow 形式暴露，方便对话框直接订阅。
 */
object MusicFolderDiscovery {
    private const val TAG = "MusicFolderDiscovery"

    /** 已发现的含音乐文件夹，按路径排序 */
    val folders = MutableStateFlow<List<MusicFolderInfo>>(emptyList())

    /** 是否正在扫描 */
    val isScanning = MutableStateFlow(false)

    /** 已经遍历过的文件夹数量（仅用于进度展示） */
    val scannedDirCount = MutableStateFlow(0)

    /** 已统计到的音乐数量（仅用于进度展示） */
    val scannedSongCount = MutableStateFlow(0)

    /** 是否已经完整扫描过一次（重新进入界面时可复用结果） */
    val hasScanned = MutableStateFlow(false)

    /** 上次扫描是否失败 */
    val scanFailed = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    /**
     * 后台开始扫描（重复调用会取消上一次）
     *
     * 扫描不绑定界面生命周期，对话框关掉也会继续扫完并缓存结果。
     */
    fun scanAsync(context: Context, scanPaths: List<Uri>) {
        val appContext = context.applicationContext
        val previous = scanJob
        scanJob = scope.launch {
            // 等上一次真正退出，避免 isScanning 互斥导致本次直接返回
            previous?.cancelAndJoin()
            try {
                scan(appContext, scanPaths)
            } catch (e: CancellationException) {
                // 被新的扫描取代
            } catch (e: Exception) {
                Log.w(TAG, "扫描含音乐文件夹失败: ${e.message}")
            }
        }
    }

    /**
     * 扫描所有 [scanPaths] 下的文件夹
     *
     * 注意：这里刻意**忽略**当前的隐藏列表，把含音乐的文件夹全部列出来，
     * 用户才能看到自己隐藏了什么，并反向取消隐藏。
     */
    suspend fun scan(context: Context, scanPaths: List<Uri>) = withContext(Dispatchers.IO) {
        if (isScanning.value) return@withContext

        Log.i(TAG, "------------ DISCOVERY: 开始扫描含音乐的文件夹 ------------")
        isScanning.value = true
        scanFailed.value = false
        scannedDirCount.value = 0
        scannedSongCount.value = 0

        val found = ArrayList<MusicFolderInfo>()
        // 同一个目录只统计一次：扫描路径父子重叠、或目录里有符号链接环时不会重复/无限递归
        val visited = HashSet<String>()
        var visitedDirs = 0
        var foundSongs = 0

        try {
            for (scanPath in scanPaths) {
                currentCoroutineContext().ensureActive()

                val root = documentFileFromUri(context, scanPath)
                if (root == null || !root.isDirectory) {
                    Log.w(TAG, "跳过无法访问的扫描路径: $scanPath")
                    continue
                }

                walk(
                    context = context,
                    dir = root,
                    sink = found,
                    visited = visited,
                    depth = 0,
                    onDirVisited = {
                        visitedDirs++
                        if (visitedDirs % 10 == 0) scannedDirCount.value = visitedDirs
                    },
                    onSongFound = {
                        foundSongs++
                        if (foundSongs % 50 == 0) scannedSongCount.value = foundSongs
                    },
                )
                scannedDirCount.value = visitedDirs
                scannedSongCount.value = foundSongs
            }

            folders.value = found
                .distinctBy { it.uri.toString() }
                .sortedBy { it.path.lowercase() }
            hasScanned.value = true
            Log.i(TAG, "------------ DISCOVERY: 完成，共 ${folders.value.size} 个含音乐文件夹 ------------")
        } catch (e: CancellationException) {
            // 取消（关闭对话框或重新扫描）时丢弃半成品，下次打开重新扫
            hasScanned.value = false
            Log.i(TAG, "------------ DISCOVERY: 已取消 ------------")
            throw e
        } catch (e: Throwable) {
            // SAF 实现千奇百怪，任何异常都不能让应用崩在用户脸上
            hasScanned.value = false
            scanFailed.value = true
            reportException(e)
            throw e
        } finally {
            isScanning.value = false
        }
    }

    /** 清空缓存，下次打开对话框时重新扫描 */
    fun invalidate() {
        cancel()
        hasScanned.value = false
    }

    /** 取消当前扫描（保留已得到的列表不覆盖） */
    fun cancel() {
        scanJob?.cancel()
        scanJob = null
        folders.value = emptyList()
        scanFailed.value = false
        scannedDirCount.value = 0
        scannedSongCount.value = 0
    }

    /**
     * 递归遍历文件夹，把含音乐（含子文件夹内的音乐）的文件夹收集到 [sink]
     *
     * @return 该文件夹（含子文件夹）内的音乐数量
     */
    private suspend fun walk(
        context: Context,
        dir: DocumentFile,
        sink: MutableList<MusicFolderInfo>,
        visited: MutableSet<String>,
        depth: Int,
        onDirVisited: () -> Unit,
        onSongFound: () -> Unit,
    ): Int {
        currentCoroutineContext().ensureActive()

        // 防环 + 防重复统计
        val key = dir.uri.toString()
        if (!visited.add(key)) return 0
        // 深度上限，避免异常目录结构把栈打爆
        if (depth > MAX_DEPTH) {
            Log.w(TAG, "目录层数过深，跳过：$key")
            return 0
        }
        onDirVisited()

        val children = try {
            dir.listFiles()
        } catch (e: Throwable) {
            Log.w(TAG, "无法读取文件夹: $key: ${e.message}")
            return 0
        }

        // 与扫描器保持一致：含 .nomedia 的文件夹本来就进不了媒体库，这里也跳过
        if (children.any { it.name == ".nomedia" }) return 0

        var direct = 0
        var total = 0
        for (child in children) {
            val name = child.name ?: continue
            if (name.startsWith(".")) continue

            if (isDirectoryFast(child)) {
                total += walk(context, child, sink, visited, depth + 1, onDirVisited, onSongFound)
            } else if (isAudioDocumentFile(child)) {
                direct++
                total++
                onSongFound()
            }
        }

        // 只要该文件夹（含子文件夹）里有音乐就列出来，
        // 这样用户可以直接隐藏一整棵目录树（例如 Android/data）
        if (total > 0) {
            sink.add(
                MusicFolderInfo(
                    uri = dir.uri,
                    path = displayPath(context, dir),
                    directSongCount = direct,
                    songCount = total,
                )
            )
        }
        return total
    }

    /**
     * 尽量不额外查询：由 listFiles() 构造出来的 DocumentFile 已经缓存了 mime，
     * 直接用它判断是否为文件夹，避免每个条目都发一次 content 查询
     */
    private fun isDirectoryFast(file: DocumentFile): Boolean {
        val mime = file.type
        return when (mime) {
            DocumentsContract.Document.MIME_TYPE_DIR -> true
            null -> file.isDirectory
            else -> false
        }
    }

    private fun displayPath(context: Context, dir: DocumentFile): String =
        absoluteFilePathFromUri(context, dir.uri) ?: dir.uri.toString()

    /** 目录递归深度上限 */
    private const val MAX_DEPTH = 32
}

/**
 * 是否为扫描器认可的音频文件（与 LocalMediaScanner.getScanFiles 的判定规则一致）
 */
fun isAudioDocumentFile(file: DocumentFile): Boolean {
    val mime = file.type ?: return false
    if (!mime.startsWith("audio") && file.name?.substringAfterLast('.') !in scannerWhitelistExts) {
        return false
    }
    // m3u 播放列表不是歌曲
    if (mime == "audio/x-mpegurl") return false
    return true
}
