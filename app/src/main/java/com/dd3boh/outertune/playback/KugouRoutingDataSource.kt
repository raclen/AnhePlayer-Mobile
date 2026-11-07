package com.dd3boh.outertune.playback

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.dd3boh.outertune.source.kugou.KUGOU_CONCEPT_ID_PREFIX

/** 概念版仅使用在线缓冲，不读取/写入永久播放缓存，也不将缓存作为离线下载。 */
internal class KugouRoutingDataSource(
    private val cached: DataSource,
    private val online: DataSource,
) : DataSource {
    private var active: DataSource? = null
    override fun addTransferListener(transferListener: TransferListener) {
        cached.addTransferListener(transferListener)
        online.addTransferListener(transferListener)
    }
    override fun open(dataSpec: DataSpec): Long {
        val selected = if (dataSpec.key?.startsWith(KUGOU_CONCEPT_ID_PREFIX) == true) online else cached
        active = selected
        return selected.open(dataSpec)
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(active).read(buffer, offset, length)
    override fun getUri(): Uri? = active?.uri
    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders.orEmpty()
    override fun close() { try { active?.close() } finally { active = null } }
}
