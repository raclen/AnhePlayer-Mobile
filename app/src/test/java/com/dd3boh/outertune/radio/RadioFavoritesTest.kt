package com.dd3boh.outertune.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 收藏快照持久化与旧 UUID 兼容测试，不涉及 UI。 */
class RadioFavoritesTest {
    @Test
    fun legacyFavoritesRemainVisibleBeforeTheirMetadataIsFetched() {
        val uuids = (1..120).map { "legacy-$it" }.toSet()
        val restored = RadioFavorites.restore(uuids, null)
        assertEquals(120, restored.size)
        assertEquals(uuids, restored.map { it.stationUuid }.toSet())
        assertTrue(restored.all { it.playUrl.isBlank() })
    }

    @Test
    fun snapshotsRestoreNameAndPlaybackParametersWithoutRegionFilters() {
        val stations = listOf(
            RadioStation("cn", "中国电台", "https://example.com/cn", countryCode = "CN", language = "chinese"),
            RadioStation("us", "海外电台", "https://example.com/us", countryCode = "US", language = "english"),
        )
        val uuids = stations.map { it.stationUuid }.toSet()
        val encoded = RadioFavorites.updateCache(uuids, null, stations)
        assertEquals(stations.toSet(), RadioFavorites.restore(uuids, encoded).toSet())
    }

    @Test
    fun partialLookupKeepsExistingSnapshotsAndMissingFavorites() {
        val uuids = setOf("cached", "updated", "missing")
        val cached = RadioStation("cached", "缓存电台", "https://example.com/cached")
        val fresh = RadioStation("updated", "更新电台", "https://example.com/new")
        val old = RadioFavorites.updateCache(uuids, null, listOf(cached))
        val result = RadioFavorites.restore(uuids, RadioFavorites.updateCache(uuids, old, listOf(fresh)))
        assertEquals(uuids, result.map { it.stationUuid }.toSet())
        assertEquals(cached, result.single { it.stationUuid == "cached" })
        assertEquals(fresh, result.single { it.stationUuid == "updated" })
        assertTrue(result.single { it.stationUuid == "missing" }.playUrl.isBlank())
    }

    @Test
    fun refreshDoesNotReAddFavoritesRemovedWhileTheRequestWasRunning() {
        val station = RadioStation("removed", "已取消", "https://example.com/live")
        val cache = RadioFavorites.updateCache(setOf("removed"), null, listOf(station))
        val updated = RadioFavorites.updateCache(emptySet(), cache, listOf(station))
        assertTrue(RadioFavorites.restore(emptySet(), updated).isEmpty())
        assertFalse(updated.contains("removed"))
    }

    @Test
    fun latestSnapshotReplacesOldStreamAndKeepsStoppedStationsVisible() {
        val old = RadioStation("one", "电台", "https://example.com/old")
        val fresh = old.copy(url = "https://example.com/new", lastCheckOk = 0)
        val cache = RadioFavorites.updateCache(setOf("one"), null, listOf(old))
        val updated = RadioFavorites.updateCache(setOf("one"), cache, listOf(fresh))
        assertEquals(listOf(fresh), RadioFavorites.restore(setOf("one"), updated))
    }

    @Test
    fun removedPlayingStationIsKeptOnlyUntilSwitchingAway() {
        val removed = RadioStation("removed", "当前台", "https://example.com/current")
        val next = RadioStation("next", "下一台", "https://example.com/next")
        val favorites = listOf(next)
        assertEquals(listOf(removed, next), RadioFavorites.playbackQueue(favorites, removed))
        assertEquals(listOf(next), RadioFavorites.playbackQueue(favorites, next))
    }

    @Test
    fun unavailableFavoritesStayVisibleButAreExcludedFromPlaybackQueue() {
        val unavailable = RadioStation("unknown", "待更新")
        val playable = RadioStation("playable", "可播放", "https://example.com/live")
        assertEquals(listOf(playable), RadioFavorites.playbackQueue(listOf(unavailable, playable), null))
        assertTrue(RadioFavorites.playbackQueue(emptyList(), null).isEmpty())
    }

    @Test
    fun damagedCacheDoesNotLoseFavoriteIds() {
        val restored = RadioFavorites.restore(setOf("one"), "broken-json")
        assertEquals("one", restored.single().stationUuid)
        assertTrue(restored.single().playUrl.isBlank())
        val station = RadioStation("one", "恢复电台", "https://example.com/live")
        val updated = RadioFavorites.updateCache(setOf("one"), "broken-json", listOf(station))
        assertEquals(listOf(station), RadioFavorites.restore(setOf("one"), updated))
    }
}
