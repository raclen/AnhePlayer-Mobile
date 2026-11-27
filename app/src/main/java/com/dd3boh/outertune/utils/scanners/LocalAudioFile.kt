package com.dd3boh.outertune.utils.scanners

import java.io.File

/** 本地播放使用文件路径；系统索引和旧的格式缓存都不能证明文件仍然存在。 */
internal fun File.readableSizeBytes(): Long? =
    if (isAbsolute && isFile && canRead()) length() else null

internal fun File.scanSizeBytes(): Long? {
    // 与目录遍历保持一致，忽略移入隐藏回收站的文件以及系统的 .trashed-* 条目。
    if (path.contains("${File.separator}.")) return null
    return readableSizeBytes()?.takeIf { it > 0 }
}
