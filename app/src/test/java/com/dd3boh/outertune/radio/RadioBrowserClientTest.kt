package com.dd3boh.outertune.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仅验证联网搜索参数和目录分页协议，不涉及 UI 或外部网络。 */
class RadioBrowserClientTest {
    @Test
    fun sendsNameQueryAndOffsetToDirectoryWithExistingFilters() {
        val url = RadioBrowserClient.stationSearchUrl(
            baseUrl = "https://de1.api.radio-browser.info",
            countryCode = " cn ",
            language = " CHINESE ",
            bitrateKey = "320",
            query = " 中央 & Radio ",
            limit = 80,
            offset = 160,
        )
        assertEquals("/json/stations/search", url.encodedPath)
        assertEquals("中央 & Radio", url.queryParameter("name"))
        assertEquals("CN", url.queryParameter("countrycode"))
        assertEquals("chinese", url.queryParameter("language"))
        assertEquals("320", url.queryParameter("bitrateMin"))
        assertEquals("320", url.queryParameter("bitrateMax"))
        assertEquals("80", url.queryParameter("limit"))
        assertEquals("160", url.queryParameter("offset"))
        assertEquals("clickcount", url.queryParameter("order"))
        assertEquals("true", url.queryParameter("reverse"))
        assertEquals("true", url.queryParameter("hidebroken"))
    }

    @Test
    fun allRegionsAndLanguagesOmitRestrictions() {
        val url = RadioBrowserClient.stationSearchUrl(
            baseUrl = "https://de1.api.radio-browser.info",
            countryCode = "",
            language = " ",
            bitrateKey = "all",
            query = "  ",
            order = "random",
        )
        assertNull(url.queryParameter("countrycode"))
        assertNull(url.queryParameter("language"))
        assertNull(url.queryParameter("bitrateMin"))
        assertNull(url.queryParameter("bitrateMax"))
        assertNull(url.queryParameter("name"))
        assertNull(url.queryParameter("reverse"))
    }

    @Test
    fun filteringAndDeduplicationDoNotTruncatePagination() {
        val valid = station("valid", "https://example.com/live")
        val broken = station("broken", "https://example.com/broken", lastCheckOk = 0)
        val noUrl = station("no-url", "")
        val noUuid = station("", "https://example.com/live")
        val body = (List(76) { valid } + listOf(broken, noUrl, noUuid, valid)).joinToString(",", "[", "]")
        val page = RadioBrowserClient.parseStationPage(body, offset = 80, limit = 80)
        assertEquals(listOf("valid"), page.stations.map { it.stationUuid })
        assertEquals(160, page.nextOffset)
        assertTrue(page.hasMore)
    }

    @Test
    fun completelyFilteredFullPageStillAllowsNextPage() {
        val body = List(80) { station("broken-$it", "", lastCheckOk = 0) }.joinToString(",", "[", "]")
        val page = RadioBrowserClient.parseStationPage(body, offset = 160, limit = 80)
        assertTrue(page.stations.isEmpty())
        assertEquals(240, page.nextOffset)
        assertTrue(page.hasMore)
    }

    @Test
    fun finalAndEmptyPagesStopPagination() {
        val finalPage = RadioBrowserClient.parseStationPage("[${station("last", "https://example.com/live")}]", 160, 80)
        assertEquals(161, finalPage.nextOffset)
        assertFalse(finalPage.hasMore)
        val emptyPage = RadioBrowserClient.parseStationPage("[]", 240, 80)
        assertEquals(240, emptyPage.nextOffset)
        assertTrue(emptyPage.stations.isEmpty())
        assertFalse(emptyPage.hasMore)
    }

    @Test
    fun resolvedStreamUrlIsKeptForPlayback() {
        val page = RadioBrowserClient.parseStationPage(
            """[{"stationuuid":"stream","name":"电台","url":"","url_resolved":"https://example.com/live.m3u8","hls":1}]""",
            0,
            80,
        )
        assertEquals("https://example.com/live.m3u8", page.stations.single().playUrl)
        assertTrue(page.stations.single().isHlsStream)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidResponseIsNotTreatedAsAnEmptyFinalPage() {
        RadioBrowserClient.parseStationPage("not-json", 80, 80)
    }

    @Test
    fun favoritesLookupDoesNotApplyDirectoryFilters() {
        val ids = setOf("one", "two")
        val url = RadioBrowserClient.stationsByUuidUrl("https://de1.api.radio-browser.info", ids)
        assertEquals("/json/stations/byuuid", url.encodedPath)
        assertEquals(ids, url.queryParameter("uuids")!!.split(",").toSet())
        assertEquals("2", url.queryParameter("limit"))
        listOf("name", "countrycode", "language", "bitrateMin", "bitrateMax", "hidebroken", "offset")
            .forEach { assertNull(url.queryParameter(it)) }
    }

    @Test
    fun favoritesKeepUnplayableStationsAndIgnoreUnexpectedIds() {
        val body = listOf(
            station("one", "https://example.com/live", lastCheckOk = 0),
            station("two", ""),
            station("one", "https://example.com/live", lastCheckOk = 0),
            station("unexpected", "https://example.com/other"),
        ).joinToString(",", "[", "]")
        val result = RadioBrowserClient.parseFavoriteStations(body, setOf("one", "two"))
        assertEquals(listOf("one", "two"), result.map { it.stationUuid })
        assertEquals(0, result.first().lastCheckOk)
        assertTrue(result.last().playUrl.isBlank())
    }

    private fun station(uuid: String, url: String, lastCheckOk: Int = 1): String =
        """{"stationuuid":"$uuid","name":"电台","url":"$url","lastcheckok":$lastCheckOk}"""
}
