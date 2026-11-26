package com.dd3boh.outertune.radio

import kotlinx.serialization.json.Json

/** UUID 集合仍是收藏的唯一依据，电台快照只用于补全展示和离线恢复。 */
internal object RadioFavorites {
    private val json = Json { ignoreUnknownKeys = true }

    fun restore(uuids: Set<String>, encoded: String?): List<RadioStation> {
        val cached = decode(encoded).associateBy { it.stationUuid }
        return uuids.filter { it.isNotBlank() }.map { uuid ->
            cached[uuid] ?: RadioStation(
                stationUuid = uuid,
                name = "电台信息待更新",
                lastCheckOk = 0,
            )
        }.sortedWith(compareBy<RadioStation> { it.displayTitle.lowercase() }.thenBy { it.stationUuid })
    }

    fun updateCache(uuids: Set<String>, encoded: String?, updates: List<RadioStation>): String {
        val stations = (decode(encoded) + updates)
            .filter { it.stationUuid.isNotBlank() && it.stationUuid in uuids }
            .associateBy { it.stationUuid }
            .values.toList()
        return json.encodeToString(stations)
    }

    fun playbackQueue(stations: List<RadioStation>, current: RadioStation?): List<RadioStation> {
        val playable = stations.filter { it.playUrl.isNotBlank() }
        return if (current != null && current.playUrl.isNotBlank() && playable.none { it.stationUuid == current.stationUuid }) {
            listOf(current) + playable
        } else playable
    }

    private fun decode(encoded: String?): List<RadioStation> = try {
        if (encoded.isNullOrBlank()) emptyList() else json.decodeFromString<List<RadioStation>>(encoded)
    } catch (_: IllegalArgumentException) {
        emptyList()
    }
}
