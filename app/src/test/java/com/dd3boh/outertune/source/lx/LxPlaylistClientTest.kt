package com.dd3boh.outertune.source.lx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** 只验证公开歌单协议、分页和洛雪媒体来源；不访问外网或构造 UI。 */
class LxPlaylistClientTest {
    private data class Call(val url: String, val parameters: Map<String, String>, val referer: String)
    private val sources = listOf(LxSearchSource.KUWO, LxSearchSource.NETEASE, LxSearchSource.KUGOU, LxSearchSource.QQ)

    private fun ok(body: String) = LxPlaylistHttpResponse(200, body)
    private fun playlist(source: LxSearchSource, id: String = "123") = when (source) {
        LxSearchSource.KUWO -> """{"playlistid":"$id","name":"<em>王杰</em>&nbsp;&amp;经典","nickname":"歌单主人","songnum":"31","pic":"http://img1.kwcdn.kuwo.cn/cover.jpg"}"""
        LxSearchSource.NETEASE -> """{"id":"$id","name":"<em>王杰</em>&nbsp;&amp;经典","creator":{"nickname":"歌单主人"},"trackCount":31,"coverImgUrl":"http://cdn.example/cover.jpg"}"""
        LxSearchSource.KUGOU -> """{"specialid":"$id","specialname":"<em>王杰</em>&nbsp;&amp;经典","nickname":"歌单主人","songcount":31,"imgurl":"http://cdn.example/{size}/cover.jpg"}"""
        LxSearchSource.QQ -> """{"dissid":"$id","dissname":"<em>王杰</em>&nbsp;&amp;经典","creator":{"name":"歌单主人"},"song_count":31,"imgurl":"//cdn.example/cover.jpg"}"""
        else -> error("没有汽水歌单协议")
    }

    private fun searchBody(source: LxSearchSource, items: List<String> = listOf(playlist(source)), total: Long = 31): String {
        val rows = items.joinToString(",")
        return when (source) {
            LxSearchSource.KUWO -> """{"TOTAL":"$total","abslist":[$rows]}"""
            LxSearchSource.NETEASE -> """{"code":200,"result":{"playlistCount":$total,"playlists":[$rows]}}"""
            LxSearchSource.KUGOU -> """{"status":1,"errcode":0,"data":{"total":$total,"info":[$rows]}}"""
            LxSearchSource.QQ -> """{"code":0,"data":{"sum":$total,"list":[$rows]}}"""
            else -> error("没有汽水歌单协议")
        }
    }

    private fun kuwoSong(id: Int = 1) = """{
        "id":"$id","name":"歌曲 $id","artist":"歌手","album":"专辑","albumid":"22","duration":"180",
        "albumpic":"http://img1.kwcdn.kuwo.cn/cover.jpg","N_MINFO":"bitrate:320,size:123;bitrate:2000,size:456"
    }"""
    private fun kuwoSongs(items: List<String> = listOf(kuwoSong()), total: Int = 31) =
        """{"result":"ok","total":$total,"musiclist":[${items.joinToString(",") }]}"""
    private fun neteaseSong(id: Int, modern: Boolean = true): String = if (modern) {
        """{"id":$id,"name":"歌曲 $id","ar":[{"name":"歌手"}],"al":{"id":22,"name":"专辑","picUrl":"http://cdn.example/cover.jpg"},"dt":180000}"""
    } else {
        """{"id":$id,"name":"歌曲 $id","artists":[{"name":"歌手"}],"album":{"id":22,"name":"专辑","picUrl":"http://cdn.example/cover.jpg"},"duration":180000}"""
    }
    private fun neteasePlaylist(count: Int = 31, tracks: List<String> = (1..minOf(count, 10)).map { neteaseSong(it) }): String =
        """{"code":200,"playlist":{"id":"123","trackCount":$count,"trackIds":[${(1..count).joinToString(",") { """{"id":$it}""" }}],"tracks":[${tracks.joinToString(",") }]}}"""

    @Test
    fun onlyAdvertisesVerifiedPublicPlatforms() {
        sources.forEach { assertTrue(LxPlaylistClient.supports(it)) }
        assertFalse(LxPlaylistClient.supports(LxSearchSource.QISHUI))
    }

    @Test
    fun mapsAllPlaylistSearchProtocolsAndSendsActualPagingParameters() = runBlocking {
        sources.forEach { source ->
            val calls = mutableListOf<Call>()
            val api = LxPlaylistApi { url, params, referer ->
                calls += Call(url, params, referer)
                ok(searchBody(source))
            }
            val page = api.search("  王杰  ", source, 2).getOrThrow()
            val item = page.items.single()
            assertEquals("123", item.id)
            assertEquals("王杰 &经典", item.name)
            assertEquals(source, item.source)
            assertEquals("歌单主人", item.creator)
            assertEquals(31, item.songCount)
            assertTrue(item.thumbnailUrl!!.startsWith("https://"))
            assertFalse(item.thumbnailUrl!!.contains("kwcdn"))
            assertFalse(item.thumbnailUrl!!.contains("{size}"))
            assertTrue(page.hasMore)
            val call = calls.single()
            when (source) {
                LxSearchSource.KUWO -> {
                    assertEquals("https://search.kuwo.cn/r.s", call.url)
                    assertEquals("王杰", call.parameters["all"])
                    assertEquals("playlist", call.parameters["ft"])
                    assertEquals("new2012", call.parameters["itemset"])
                    assertEquals("1", call.parameters["mobi"])
                    assertEquals("1", call.parameters["pn"])
                    assertEquals("15", call.parameters["rn"])
                }
                LxSearchSource.NETEASE -> {
                    assertEquals("王杰", call.parameters["s"])
                    assertEquals("1000", call.parameters["type"])
                    assertEquals("15", call.parameters["offset"])
                    assertEquals("15", call.parameters["limit"])
                }
                LxSearchSource.KUGOU -> {
                    assertTrue(call.url.endsWith("/search/special"))
                    assertEquals("王杰", call.parameters["keyword"])
                    assertEquals("2", call.parameters["page"])
                    assertEquals("15", call.parameters["pagesize"])
                }
                LxSearchSource.QQ -> {
                    assertTrue(call.url.endsWith("/client_music_search_songlist"))
                    assertEquals("王杰", call.parameters["query"])
                    assertEquals("1", call.parameters["page_no"])
                    assertEquals("15", call.parameters["num_per_page"])
                }
                else -> fail("不应访问汽水")
            }
            assertFalse(call.parameters.any { (key, value) -> key.contains("token", ignoreCase = true) && value.isNotBlank() })
        }
    }

    @Test
    fun capsPagesAndUsesRawPageSpanRatherThanDeduplicatedSize() = runBlocking {
        val rows = listOf(playlist(LxSearchSource.KUWO)) + List(14) { "null" }
        val api = LxPlaylistApi { _, _, _ -> ok(searchBody(LxSearchSource.KUWO, rows, 45)) }
        val page = api.search("关键词", LxSearchSource.KUWO).getOrThrow()
        assertEquals(1, page.items.size)
        assertTrue(page.hasMore)
        val oversized = LxPlaylistApi { _, _, _ ->
            ok(searchBody(LxSearchSource.KUWO, (1..20).map { playlist(LxSearchSource.KUWO, "$it") }, 31))
        }
        assertEquals(15, oversized.search("关键词", LxSearchSource.KUWO).getOrThrow().items.size)
        val qq = LxPlaylistApi { _, _, _ ->
            ok(searchBody(LxSearchSource.QQ, (1..13).map { playlist(LxSearchSource.QQ, "$it") }, 2109))
        }
        assertTrue(qq.search("关键词", LxSearchSource.QQ).getOrThrow().hasMore)
    }

    @Test
    fun handlesLastAndEmptyPagesWithoutInventingMoreResults() = runBlocking {
        sources.forEach { source ->
            val api = LxPlaylistApi { _, _, _ -> ok(searchBody(source, total = 15)) }
            assertFalse(api.search("关键词", source).getOrThrow().hasMore)
            val empty = LxPlaylistApi { _, _, _ -> ok(searchBody(source, emptyList(), 0)) }
            assertEquals(LxPage<LxPlaylistInfo>(emptyList(), false), empty.search("关键词", source).getOrThrow())
        }
        val pageTwo = LxPlaylistApi { _, _, _ -> ok(searchBody(LxSearchSource.KUGOU, emptyList(), 15)) }
        assertFalse(pageTwo.search("关键词", LxSearchSource.KUGOU, 2).getOrThrow().hasMore)
    }

    @Test
    fun preservesLargePlaylistIdsAndRejectsUnsafeCovers() = runBlocking {
        val item = """{"id":7146240707408168993,"name":"歌单 &#x1F3B5;","trackCount":-1,"coverImgUrl":"javascript:private-value"}"""
        val api = LxPlaylistApi { _, _, _ -> ok(searchBody(LxSearchSource.NETEASE, listOf(item))) }
        val result = api.search("关键词", LxSearchSource.NETEASE).getOrThrow().items.single()
        assertEquals("7146240707408168993", result.id)
        assertEquals("歌单 " + String(Character.toChars(0x1F3B5)), result.name)
        assertNull(result.songCount)
        assertNull(result.thumbnailUrl)
        assertEquals("", result.creator)
    }

    @Test
    fun reusesKuwoLowercaseDetailParserAndKeepsLxQualityAndMediaRoute() = runBlocking {
        val calls = mutableListOf<Call>()
        val api = LxPlaylistApi { url, params, referer ->
            calls += Call(url, params, referer)
            ok(kuwoSongs())
        }
        val page = api.songs("123", LxSearchSource.KUWO, 2).getOrThrow()
        val song = page.items.single()
        assertMediaRoute(song, "kw", "1", "kw_1")
        assertEquals("歌曲 1", song.name)
        assertEquals("歌手", song.singer)
        assertEquals("03:00", song.interval)
        assertEquals("22", song.meta.albumId)
        assertEquals(listOf("320k", "flac"), song.meta.qualitys.map { it.type })
        assertEquals("456", song.meta._qualitys["flac"]!!.size)
        assertEquals("https://img1.kuwo.cn/cover.jpg", song.meta.picUrl)
        assertTrue(page.hasMore)
        val call = calls.single()
        assertEquals("https://nplserver.kuwo.cn/pl.svc", call.url)
        assertEquals("getlistinfo", call.parameters["op"])
        assertEquals("123", call.parameters["pid"])
        assertEquals("1", call.parameters["pn"])
        assertEquals("15", call.parameters["rn"])
    }

    @Test
    fun mapsKugouFilenameAndRetainsHashRatherThanConceptAccountRoute() = runBlocking {
        val hash = "1E54C9C01C9DFC5C68F743368F353711"
        val api = LxPlaylistApi { url, params, _ ->
            assertTrue(url.endsWith("/special/song"))
            assertEquals("2", params["page"])
            assertEquals("15", params["pagesize"])
            ok("""{"status":1,"errcode":0,"data":{"total":362,"info":[{
                "hash":"$hash","filename":"歌手 - 歌曲 - 现场","duration":180,"album_id":"22","remark":"专辑",
                "trans_param":{"union_cover":"http://cdn.example/{size}/cover.jpg"}
            }]}}""")
        }
        val song = api.songs("123", LxSearchSource.KUGOU, 2).getOrThrow().items.single()
        assertMediaRoute(song, "kg", hash, "kg_$hash")
        assertEquals(hash, song.hash)
        assertEquals("歌曲 - 现场", song.name)
        assertEquals("歌手", song.singer)
        assertEquals("专辑", song.meta.albumName)
        assertEquals("https://cdn.example/480/cover.jpg", song.meta.picUrl)
        assertFalse(song.toMediaMetadata().sourceData!!.contains("kg_concept"))
        assertFalse(song.id.startsWith("kgc_"))
    }

    @Test
    fun usesVerifiedQqSongBeginAndSongNumAndKeepsSongMid() = runBlocking {
        val api = LxPlaylistApi { url, params, referer ->
            assertTrue(url.endsWith("/fcg_ucc_getcdinfo_byids_cp.fcg"))
            assertEquals("https://y.qq.com/", referer)
            assertEquals("123", params["disstid"])
            assertEquals("15", params["song_begin"])
            assertEquals("15", params["song_num"])
            ok("""{"code":0,"cdlist":[{"disstid":"123","songnum":175,"songlist":[{
                "songmid":"qq-mid","songname":"歌曲","singer":[{"name":"歌手"}],"interval":180,
                "albumid":22,"albummid":"album-mid","albumname":"专辑"
            }]}]}""")
        }
        val page = api.songs("123", LxSearchSource.QQ, 2).getOrThrow()
        assertTrue(page.hasMore)
        val song = page.items.single()
        assertMediaRoute(song, "tx", "qq-mid", "tx_qq-mid")
        assertEquals("qq-mid", song.mid)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R500x500M000album-mid.jpg", song.meta.picUrl)
    }

    @Test
    fun slicesNeteaseTrackIdsAndFetchesOnlyOneMissingMetadataBatchPerPage() = runBlocking {
        val calls = mutableListOf<Call>()
        val api = LxPlaylistApi { url, params, referer ->
            calls += Call(url, params, referer)
            if (url.endsWith("/playlist/detail")) ok(neteasePlaylist()) else {
                assertEquals("https://music.163.com/api/song/detail/", url)
                val ids = params["ids"]!!.removeSurrounding("[", "]").split(',').map(String::toInt)
                assertTrue(ids.size <= 15)
                ok("""{"code":200,"songs":[${ids.reversed().joinToString(",") { neteaseSong(it, false) }}]}""")
            }
        }
        val first = api.songs("123", LxSearchSource.NETEASE).getOrThrow()
        assertEquals((1..15).map(Int::toString), first.items.map { it.songmid })
        assertTrue(first.hasMore)
        assertEquals("[11,12,13,14,15]", calls[1].parameters["ids"])
        assertMediaRoute(first.items.first(), "wy", "1", "wy_1")
        assertEquals("1", first.items.first().musicId)
        val second = api.songs("123", LxSearchSource.NETEASE, 2).getOrThrow()
        assertEquals((16..30).map(Int::toString), second.items.map { it.songmid })
        assertTrue(second.hasMore)
        val last = api.songs("123", LxSearchSource.NETEASE, 3).getOrThrow()
        assertEquals("31", last.items.single().songmid)
        assertFalse(last.hasMore)
        val after = api.songs("123", LxSearchSource.NETEASE, 4).getOrThrow()
        assertTrue(after.items.isEmpty())
        assertFalse(after.hasMore)
        assertEquals(7, calls.size)
        assertEquals(3, calls.count { it.url.endsWith("/song/detail/") })
        assertTrue(calls.all { it.referer == "https://music.163.com/" })
    }

    @Test
    fun rejectsIncompleteNeteaseMetadataAndDoesNotCacheOrMarkPageComplete() = runBlocking {
        var incomplete = true
        var calls = 0
        val api = LxPlaylistApi { url, _, _ ->
            calls++
            if (url.endsWith("/playlist/detail")) ok(neteasePlaylist(2, emptyList()))
            else ok("""{"code":200,"songs":[${(1..if (incomplete) 1 else 2).joinToString(",") { neteaseSong(it) }}]}""")
        }
        val error = api.songs("123", LxSearchSource.NETEASE).exceptionOrNull()!!
        assertTrue(error.message!!.contains("不完整"))
        assertNull(error.cause)
        incomplete = false
        val page = api.songs("123", LxSearchSource.NETEASE).getOrThrow()
        assertEquals(2, page.items.size)
        assertFalse(page.hasMore)
        assertEquals(4, calls)
        assertEquals(page, api.songs("123", LxSearchSource.NETEASE).getOrThrow())
        assertEquals(4, calls)
        val missingIds = LxPlaylistApi { _, _, _ -> ok("""{"code":200,"playlist":{"id":"123","trackCount":31,"tracks":[]}}""") }
        assertTrue(missingIds.songs("123", LxSearchSource.NETEASE).isFailure)
    }

    @Test
    fun validEmptyNeteasePlaylistDoesNotFetchAnySongDetails() = runBlocking {
        var calls = 0
        val api = LxPlaylistApi { url, _, _ ->
            calls++
            assertTrue(url.endsWith("/playlist/detail"))
            ok(neteasePlaylist(0))
        }
        assertEquals(LxPage<LxMusicInfo>(emptyList(), false), api.songs("123", LxSearchSource.NETEASE).getOrThrow())
        assertEquals(1, calls)
    }

    @Test
    fun rejectsProtocolAndHttpFailuresWithChineseErrorsWithoutUpstreamData() = runBlocking {
        val invalidBodies = listOf(
            "", "null", "[]", "not json", "{}", """{"TOTAL":0,"abslist":{}}""",
            """{"TOTAL":1,"abslist":[]}""", """{"TOTAL":1,"abslist":[{}]}""",
            """{"result":"fail","message":"https://private.example/?token=secret"}""",
        )
        invalidBodies.forEach { body ->
            val api = LxPlaylistApi { _, _, _ -> ok(body) }
            assertSafeFailure(api.search("关键词", LxSearchSource.KUWO).exceptionOrNull())
        }
        listOf(403, 500).forEach { status ->
            val api = LxPlaylistApi { _, _, _ -> LxPlaylistHttpResponse(status, "private-token") }
            assertSafeFailure(api.search("关键词", LxSearchSource.KUWO).exceptionOrNull())
        }
        val network = LxPlaylistApi { _, _, _ -> throw IOException("https://private.example/?token=secret") }
        assertSafeFailure(network.search("关键词", LxSearchSource.KUWO).exceptionOrNull())
        listOf(
            LxSearchSource.NETEASE to """{"code":403,"message":"private-token"}""",
            LxSearchSource.KUGOU to """{"status":1,"errcode":100,"error":"private-token"}""",
            LxSearchSource.QQ to """{"code":100,"message":"private-token"}""",
        ).forEach { (source, body) ->
            val api = LxPlaylistApi { _, _, _ -> ok(body) }
            assertSafeFailure(api.search("关键词", source).exceptionOrNull())
        }
    }

    @Test
    fun rejectsInvalidInputAndUnsupportedSourcesBeforeNetwork() = runBlocking {
        val api = LxPlaylistApi { _, _, _ -> error("无效输入不应发送请求") }
        assertTrue(api.search("  ", LxSearchSource.KUWO).isFailure)
        assertTrue(api.search("关键词", LxSearchSource.KUWO, 0).isFailure)
        assertTrue(api.search("关键词", LxSearchSource.QISHUI).isFailure)
        assertTrue(api.songs("123", LxSearchSource.QISHUI).isFailure)
        listOf("", "kw_123", "-1", "0", "https://private.example", "1&token=secret").forEach {
            assertTrue(api.songs(it, LxSearchSource.KUWO).isFailure)
        }
        val calls = mutableListOf<Call>()
        val largePage = LxPlaylistApi { url, params, referer ->
            calls += Call(url, params, referer)
            ok(searchBody(LxSearchSource.NETEASE, emptyList(), 0))
        }
        assertFalse(largePage.search("关键词", LxSearchSource.NETEASE, Int.MAX_VALUE).getOrThrow().hasMore)
        assertEquals("32212254690", calls.single().parameters["offset"])
    }

    @Test
    fun preservesCoroutineCancellationInsteadOfReturningFailure() = runBlocking {
        val cancellation = CancellationException("已取消")
        val api = LxPlaylistApi { _, _, _ -> throw cancellation }
        try {
            api.search("关键词", LxSearchSource.KUWO)
            fail("取消必须继续传播")
        } catch (actual: CancellationException) {
            // 协程栈恢复可能复制异常；取消类型和原始原因必须保留，不要求对象身份。
            assertEquals(cancellation.message, actual.message)
            assertTrue(actual === cancellation || actual.cause === cancellation)
        }
    }

    @Test
    fun cachesPagesForFiveMinutesWithSourceAndOperationIsolation() = runBlocking {
        var now = 1000L
        var calls = 0
        val api = LxPlaylistApi(now = { now }) { url, _, _ ->
            calls++
            ok(when {
                url.endsWith("/pl.svc") -> kuwoSongs()
                url.contains("music.163.com") -> searchBody(LxSearchSource.NETEASE)
                else -> searchBody(LxSearchSource.KUWO)
            })
        }
        val first = api.search(" 123 ", LxSearchSource.KUWO).getOrThrow()
        assertEquals(first, api.search("123", LxSearchSource.KUWO).getOrThrow())
        assertEquals(1, calls)
        api.search("123", LxSearchSource.KUWO, 2).getOrThrow()
        api.search("123", LxSearchSource.NETEASE).getOrThrow()
        api.songs("123", LxSearchSource.KUWO).getOrThrow()
        assertEquals(4, calls)
        now += 299_999
        api.search("123", LxSearchSource.KUWO).getOrThrow()
        assertEquals(4, calls)
        now++
        api.search("123", LxSearchSource.KUWO).getOrThrow()
        assertEquals(5, calls)
    }

    @Test
    fun boundsCombinedMetadataCacheToTwentyEntries() = runBlocking {
        var calls = 0
        val api = LxPlaylistApi { _, _, _ ->
            calls++
            ok(searchBody(LxSearchSource.KUWO))
        }
        (1..21).forEach { api.search("关键词 $it", LxSearchSource.KUWO).getOrThrow() }
        api.search("关键词 21", LxSearchSource.KUWO).getOrThrow()
        assertEquals(21, calls)
        api.search("关键词 1", LxSearchSource.KUWO).getOrThrow()
        assertEquals(22, calls)
    }

    @Test
    fun fallsBackToQqMusicuWhenLegacyPlaylistEndpointBlocksPrivacy() = runBlocking {
        var postCalls = 0
        val api = LxPlaylistApi(
            postRequest = { url, body, referer ->
                postCalls++
                assertEquals("https://u.y.qq.com/cgi-bin/musicu.fcg", url)
                assertEquals("https://y.qq.com/", referer)
                assertTrue(body.contains("uniform_get_Dissinfo"))
                ok("""{"code":0,"req_0":{"code":0,"data":{"dirinfo":{"songnum":30},"songlist":[{
                    "mid":"qq-mid-2","title":"歌曲二","singer":[{"name":"歌手二"}],
                    "album":{"id":88,"mid":"alb-2","name":"专辑二"},"interval":180
                }]}}}""")
            },
            request = { _, _, _ ->
                ok("""{"code":0,"subcode":4000,"msg":"check privacy error!"}""")
            },
        )
        val page = api.songs("7707261125", LxSearchSource.QQ, 1).getOrThrow()
        assertEquals(1, postCalls)
        assertTrue(page.hasMore)
        val song = page.items.single()
        assertMediaRoute(song, "tx", "qq-mid-2", "tx_qq-mid-2")
        assertEquals("歌曲二", song.name)
        assertEquals("歌手二", song.singer)
        assertEquals("专辑二", song.meta.albumName)
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R500x500M000alb-2.jpg", song.meta.picUrl)
    }

    @Test
    fun parsesKugouAuthorsArrayWhenSingerNameIsMissing() = runBlocking {
        val hash = "2E54C9C01C9DFC5C68F743368F353722"
        val api = LxPlaylistApi { _, _, _ ->
            ok("""{"status":1,"errcode":0,"data":{"total":1,"info":[{
                "hash":"$hash","songname":"歌曲名","authors":[{"author_name":"作者甲"},{"author_name":"作者乙"}],
                "duration":180,"album_id":"33","remark":"专辑名"
            }]}}""")
        }
        val song = api.songs("123", LxSearchSource.KUGOU, 1).getOrThrow().items.single()
        assertMediaRoute(song, "kg", hash, "kg_$hash")
        assertEquals("作者甲 / 作者乙", song.singer)
    }

    private fun assertMediaRoute(song: LxMusicInfo, source: String, sourceId: String, id: String) {
        val metadata = song.toMediaMetadata()
        assertEquals(id, song.id)
        assertEquals(id, metadata.id)
        assertEquals(sourceId, song.songmid)
        assertEquals(sourceId, song.meta.songId)
        assertEquals(source, metadata.source)
        assertEquals(sourceId, metadata.sourceId)
        assertEquals(180, metadata.duration)
        val restored = lxJson.decodeFromString<LxMusicInfo>(metadata.sourceData!!)
        assertEquals(song, restored)
        assertEquals(source, restored.source)
        assertNull(restored.raw)
    }

    private fun assertSafeFailure(error: Throwable?) {
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message!!.contains("歌单"))
        assertFalse(error.message!!.contains("private"))
        assertFalse(error.message!!.contains("token"))
        assertFalse(error.message!!.contains("http"))
        assertNull(error.cause)
    }
}
