package com.dd3boh.outertune.source

import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.kugou.KUGOU_CONCEPT_SOURCE
import com.dd3boh.outertune.source.kugou.KUGOU_CONCEPT_ID_PREFIX

object SourceCapabilities {
    private val lxSources = setOf("kg", "wy", "tx", "kw", "qs")
    fun isConcept(source: String?): Boolean = source == KUGOU_CONCEPT_SOURCE
    fun isLx(source: String?): Boolean = source in lxSources
    fun canDownload(source: String?, isLocal: Boolean = false): Boolean = !isLocal && isLx(source)
    fun canDownload(song: MediaMetadata): Boolean = !song.id.startsWith(KUGOU_CONCEPT_ID_PREFIX) && canDownload(song.source, song.isLocal)
}
