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

/**
 * 隐藏文件夹（扫描排除）判定器
 *
 * 隐藏文件夹以 SAF 目录 URI 的形式保存在 [com.dd3boh.outertune.constants.ExcludedScanPathsKey] 中，
 * 每条记录都会预先展开成两种匹配方式：
 *
 * 1. 绝对路径（`/storage/emulated/0/Music/xxx`）：外部存储 provider 可以解析出真实路径，
 *    这样 MediaStore 扫描（DATA 列）与 DocumentFile 扫描都能命中。
 * 2. URI 前缀（`content://.../tree/primary%3AMusic/document/primary%3AMusic%2Fxxx`）：
 *    子文件的 document URI 一定以父目录 URI 为前缀，用于无法解析真实路径的 provider，
 *    以及递归扫描时对目录做剪枝。
 *
 * 这修正了旧实现里拿 `Uri.path`（`/tree/primary:Music/...`）去和文件真实路径比较、
 * 永远匹配不上的问题。
 */
class ExcludedFolderFilter private constructor(
    private val absolutePaths: List<String>,
    private val uriPrefixes: List<String>,
) {
    val isEmpty: Boolean
        get() = absolutePaths.isEmpty() && uriPrefixes.isEmpty()

    /**
     * 判断某个条目是否落在隐藏文件夹（含其子文件夹）内
     *
     * @param filePath 真实文件/文件夹路径，未知时传 null
     * @param fileUri 文件/文件夹的 document URI，未知时传 null
     */
    fun isExcluded(filePath: String?, fileUri: Uri?): Boolean {
        if (filePath != null) {
            for (hidden in absolutePaths) {
                if (isPathUnder(filePath, hidden)) return true
            }
        }
        if (fileUri != null) {
            val uriString = fileUri.toString()
            for (prefix in uriPrefixes) {
                if (uriString.startsWith(prefix)) return true
            }
        }
        return false
    }

    /** 只按 URI 判断（递归剪枝用，不需要解析真实路径） */
    fun isExcludedUri(fileUri: Uri?): Boolean = isExcluded(null, fileUri)

    override fun toString(): String = "ExcludedFolderFilter(paths=$absolutePaths, uris=$uriPrefixes)"

    companion object {
        val EMPTY = ExcludedFolderFilter(emptyList(), emptyList())

        /** 内置默认目录（MediaStore 扫描）使用 `builtin-abs:<绝对路径>` 作为隐藏标识 */
        const val BUILTIN_SCHEME = "builtin-abs"

        fun fromUris(context: Context, uris: List<Uri>): ExcludedFolderFilter {
            if (uris.isEmpty()) return EMPTY

            val paths = ArrayList<String>(uris.size)
            val prefixes = ArrayList<String>(uris.size)
            uris.forEach { uri ->
                // 内置目录（MediaStore 默认目录）以绝对路径保存，没有 SAF 树 URI
                if (uri.scheme == BUILTIN_SCHEME) {
                    uri.schemeSpecificPart?.takeIf(String::isNotBlank)?.let { paths.add(it.trimEnd('/')) }
                    return@forEach
                }
                val prefix = uri.toString()
                if (prefix.isNotBlank()) prefixes.add(prefix)
                // 旧数据或异常 URI 不应让整体扫描崩掉
                runCatching { absoluteFilePathFromUri(context, uri) }
                    .getOrNull()
                    ?.let { paths.add(it.trimEnd('/')) }
            }

            if (paths.isEmpty() && prefixes.isEmpty()) return EMPTY
            return ExcludedFolderFilter(paths, prefixes)
        }

        fun fromPreferenceString(context: Context, str: String): ExcludedFolderFilter =
            fromUris(context, uriListFromString(str))

        /**
         * path 是否为 parent 本身或其子路径（按路径分隔符判断边界，
         * 避免 `/Music2` 被 `/Music` 误伤）
         */
        fun isPathUnder(path: String, parent: String): Boolean {
            if (parent.isEmpty()) return false
            if (!path.startsWith(parent)) return false
            return path.length == parent.length || path[parent.length] == '/'
        }
    }
}
