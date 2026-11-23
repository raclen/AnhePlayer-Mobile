package com.dd3boh.outertune.ui.utils

import androidx.navigation.NavController
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.kugou.KUGOU_CONCEPT_SOURCE
import com.dd3boh.outertune.utils.urlEncode

val NavController.canNavigateUp: Boolean
    get() = currentBackStackEntry?.destination?.parent?.route != null

fun NavController.backToMain() {
    do { navigateUp() } while (canNavigateUp)
}

fun songSearchRoute(query: String, source: String? = null): String {
    val keyword = query.urlEncode()
    return if (source == KUGOU_CONCEPT_SOURCE) {
        "kugou/search?query=$keyword"
    } else {
        "search/$keyword" + (source?.takeIf { it.isNotBlank() }?.let { "?source=${it.urlEncode()}" } ?: "")
    }
}

fun onlinePlaylistRoute(playlist: com.dd3boh.outertune.source.lx.LxPlaylistInfo): String =
    "online_playlist/${playlist.source.sourceId}/${playlist.id.urlEncode()}?title=${playlist.name.urlEncode()}"

fun MediaMetadata.artistBrowseRoute(artist: MediaMetadata.Artist): String? {
    // 在线歌手没有可供本地详情页查询的 ID；入库生成的 ID 也不是平台歌手 ID。
    return if (isLocal && !artist.id.isNullOrBlank()) {
        "artist/${artist.id.urlEncode()}"
    } else {
        artist.name.takeIf { it.isNotBlank() }?.let { songSearchRoute(it, source) }
    }
}

fun MediaMetadata.albumBrowseRoute(): String? {
    val album = album ?: return null
    return if (isLocal && album.id.isNotBlank()) {
        "album/${album.id.urlEncode()}"
    } else {
        album.title.takeIf { it.isNotBlank() }?.let { songSearchRoute(it, source) }
    }
}
