package com.dd3boh.outertune.lyrics

import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dd3boh.outertune.source.SourceCapabilities

internal object LyricsCachePolicy {
    fun usable(source: String?, lyrics: String?): String? = lyrics?.takeUnless {
        // 概念版旧版把歌词协议成功误判为失败，不能让该失败标记永久挡住修复后的请求。
        SourceCapabilities.isConcept(source) && it == LYRICS_NOT_FOUND
    }
}
