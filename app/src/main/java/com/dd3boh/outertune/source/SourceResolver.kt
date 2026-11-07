package com.dd3boh.outertune.source

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.kugou.KugouConcept
import com.dd3boh.outertune.source.lx.LxSourceRuntime

object SourceResolver {
    suspend fun resolve(song: MediaMetadata): String = when {
        SourceCapabilities.isConcept(song.source) -> KugouConcept.resolveStream(song).urls.first()
        SourceCapabilities.isLx(song.source) -> LxSourceRuntime.resolve(song)
        else -> error("歌曲没有可用的在线来源")
    }
    suspend fun resolveLyric(song: MediaMetadata): String? = when {
        SourceCapabilities.isConcept(song.source) -> KugouConcept.resolveLyrics(song)
        SourceCapabilities.isLx(song.source) -> LxSourceRuntime.resolveLyric(song)
        else -> null
    }
}
