package com.dd3boh.outertune.radio

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class RadioStation(
    @SerialName("stationuuid") val stationUuid: String = "",
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val homepage: String? = null,
    val favicon: String? = null,
    val tags: String? = null,
    val country: String? = null,
    @SerialName("countrycode") val countryCode: String? = null,
    val language: String? = null,
    val codec: String? = null,
    val bitrate: Int = 0,
    val hls: Int = 0,
    val votes: Int = 0,
    @SerialName("clickcount") val clickCount: Int = 0,
    @SerialName("lastcheckok") val lastCheckOk: Int = 1,
) {
    val playUrl: String
        get() = urlResolved.trim().ifBlank { url.trim() }

    val displayTitle: String
        get() = name.trim().ifBlank { "未命名电台" }

    val displaySubtitle: String
        get() = listOfNotNull(
            country?.trim()?.takeIf { it.isNotBlank() },
            language?.trim()?.takeIf { it.isNotBlank() }
        ).joinToString(" · ").ifBlank { "世界电台" }

    val displayCodecAndBitrate: String
        get() = listOfNotNull(
            codec?.trim()?.takeIf { it.isNotBlank() }?.uppercase(),
            if (bitrate > 0) "${bitrate} kbps" else null
        ).joinToString(" · ").ifBlank { "网络电台" }

    val tagList: List<String>
        get() = tags?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
}
