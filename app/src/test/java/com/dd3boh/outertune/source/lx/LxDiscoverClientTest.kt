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

/** 验证免登录排行榜与热门歌单公开协议，确认全部走洛雪来源而非概念版账号接口。 */
class LxDiscoverClientTest {
    private data class Call(val url: String, val parameters: Map<String, String>, val referer: String)

    private fun ok(body: String) = LxPlaylistHttpResponse(200, body)

    @Test
    fun advertisesPcAlignedPublicDiscoveryPlatforms() {
        assertEquals(listOf(LxSearchSource.KUGOU, LxSearchSource.NETEASE, LxSearchSource.QQ), LxDiscoverClient.supportedSources)
        assertTrue(LxDiscoverClient.supports(LxSearchSource.KUGOU))
        assertTrue(LxDiscoverClient.supports(LxSearchSource.NETEASE))
        assertTrue(LxDiscoverClient.supports(LxSearchSource.QQ))
        assertFalse(LxDiscoverClient.supports(LxSearchSource.KUWO))
        assertFalse(LxDiscoverClient.supports(LxSearchSource.QISHUI))
    }

    @Test
    fun parsesTopListsAcrossAllSupportedPlatformsWithoutAccountParams() = runBlocking {
        val calls = mutableListOf<Call>()
        val api = LxDiscoverApi { url, params, referer ->
            calls += Call(url, params, referer)
            when {
                url.contains("mobiles.kugou.com") -> ok(
                    """{"data":{"info":[{"rankid":8888,"rankname":"酷狗TOP500","intro":"每日更新","update_frequency":"每天","imgurl":"http://imge.kugou.com/mcommon/{size}/cover.png"}]}}""",
                )
                url.contains("music.163.com") -> ok(
                    """{"code":200,"list":[{"id":19723756,"name":"飙升榜","description":"热度飙升","updateFrequency":"每天更新","coverImgUrl":"http://p1.music.126.net/cover.jpg","trackCount":100,"tracks":[{"first":"歌1","second":"人1"}]}]}""",
                )
                else -> ok(
                    """{"code":0,"data":{"topList":[{"id":4,"topTitle":"流行指数榜","picUrl":"http://y.gtimg.cn/top.jpg","songList":[{"songname":"歌A","singername":"人A"}]}]}}""",
                )
            }
        }

        val kg = api.topLists(LxSearchSource.KUGOU).getOrThrow().single()
        assertEquals("8888", kg.id)
        assertEquals("酷狗TOP500", kg.name)
        assertEquals("https://imge.kugou.com/mcommon/480/cover.png", kg.thumbnailUrl)

        val wy = api.topLists(LxSearchSource.NETEASE).getOrThrow().single()
        assertEquals("19723756", wy.id)
        assertEquals("飙升榜", wy.name)
        assertEquals(100, wy.songCount)
        assertEquals(listOf("歌1 - 人1"), wy.previewSongs)

        val tx = api.topLists(LxSearchSource.QQ).getOrThrow().single()
        assertEquals("4", tx.id)
        assertEquals("流行指数榜", tx.name)
        assertEquals(listOf("歌A - 人A"), tx.previewSongs)

        assertTrue(calls.all { it.url.startsWith("https://") })
        assertTrue(calls.all { call -> call.parameters.none { (k, v) -> k.contains("token", ignoreCase = true) && v.isNotBlank() } })
    }

    @Test
    fun loadsTopListSongsAsLxSourcesRatherThanConceptAccountRoute() = runBlocking {
        val hash = "3E54C9C01C9DFC5C68F743368F353733"
        val api = LxDiscoverApi(
            playlistSongs = { id, source, page ->
                assertEquals("19723756", id)
                assertEquals(LxSearchSource.NETEASE, source)
                assertEquals(2, page)
                Result.success(
                    LxPage(
                        listOf(
                            LxMusicInfo(
                                id = "wy_100", songmid = "100", name = "网易榜歌", singer = "歌手",
                                source = "wy", meta = LxMusicMeta(songId = "100"),
                            ),
                        ),
                        hasMore = true,
                    ),
                )
            },
        ) { url, params, _ ->
            when {
                url.endsWith("/rank/song") -> {
                    assertEquals("8888", params["rankid"])
                    ok("""{"data":{"total":50,"info":[{"hash":"$hash","songname":"酷狗榜歌","authors":[{"author_name":"酷狗歌手"}],"duration":200}]}}""")
                }
                else -> {
                    assertEquals("4", params["topid"])
                    ok("""{"code":0,"total_song_num":100,"songlist":[{"data":{"songmid":"qq-top","songname":"QQ榜歌","singer":[{"name":"QQ歌手"}],"interval":210,"albummid":"alb1"}}]}""")
                }
            }
        }

        val kgSong = api.topListSongs("8888", LxSearchSource.KUGOU, 1).getOrThrow().items.single()
        assertEquals("kg", kgSong.source)
        assertEquals("kg_$hash", kgSong.id)
        assertFalse(kgSong.id.startsWith("kgc_"))
        assertFalse(kgSong.toMediaMetadata().sourceData!!.contains("kg_concept"))

        val wyPage = api.topListSongs("19723756", LxSearchSource.NETEASE, 2).getOrThrow()
        assertEquals("wy_100", wyPage.items.single().id)
        assertTrue(wyPage.hasMore)

        val qqPage = api.topListSongs("4", LxSearchSource.QQ, 1).getOrThrow()
        assertEquals("tx_qq-top", qqPage.items.single().id)
        assertTrue(qqPage.hasMore)
    }

    @Test
    fun parsesRecommendTagsAndPlaylistsAcrossSupportedPlatforms() = runBlocking {
        val api = LxDiscoverApi(
            playlistSearch = { keyword, source, page ->
                assertEquals("流行", keyword)
                assertEquals(LxSearchSource.KUGOU, source)
                assertEquals(2, page)
                Result.success(LxPage(listOf(LxPlaylistInfo("99", "酷狗流行", LxSearchSource.KUGOU)), false))
            },
        ) { url, params, _ ->
            when {
                url.endsWith("/tag/list") -> ok(
                    """{"data":{"info":[{"name":"语种","children":[{"special_tag_id":10,"name":"华语","is_hot":1},{"id":11,"name":"欧美","is_hot":0}]}]}}""",
                )
                url.endsWith("/playlist/hottags") -> ok(
                    """{"code":200,"tags":[{"name":"华语"},{"name":"流行"}]}""",
                )
                url.endsWith("/fcg_get_diss_tag_conf.fcg") -> ok(
                    """{"code":0,"data":{"categories":[{"categoryGroupName":"热门","items":[{"categoryId":10000000,"categoryName":"全部"},{"categoryId":6,"categoryName":"华语"}]}]}}""",
                )
                url.endsWith("/playlist/list") -> {
                    assertEquals("流行", params["cat"])
                    assertEquals("15", params["offset"])
                    ok("""{"code":200,"total":30,"more":false,"playlists":[{"id":1234,"name":"网易热单","trackCount":20,"coverImgUrl":"http://p1.music.126.net/a.jpg"}]}""")
                }
                else -> {
                    assertEquals("6", params["categoryId"])
                    assertEquals("0", params["sin"])
                    assertEquals("14", params["ein"])
                    ok("""{"code":0,"data":{"sum":50,"list":[{"dissid":"5678","dissname":"QQ热单","song_count":30,"imgurl":"//y.qq.com/b.jpg"}]}}""")
                }
            }
        }

        val kgTags = api.recommendTags(LxSearchSource.KUGOU).getOrThrow()
        assertEquals(listOf("华语"), kgTags.pinned.map { it.name })
        assertEquals(2, kgTags.groups.single().tags.size)

        val wyTags = api.recommendTags(LxSearchSource.NETEASE).getOrThrow()
        assertEquals(listOf("华语", "流行"), wyTags.pinned.map { it.name })

        val qqTags = api.recommendTags(LxSearchSource.QQ).getOrThrow()
        assertEquals(listOf("华语"), qqTags.pinned.map { it.name })

        val kgSheets = api.recommendPlaylists(LxSearchSource.KUGOU, "10", "流行", 2).getOrThrow()
        assertEquals("99", kgSheets.items.single().id)
        assertFalse(kgSheets.hasMore)

        val wySheets = api.recommendPlaylists(LxSearchSource.NETEASE, "流行", "流行", 2).getOrThrow()
        assertEquals("1234", wySheets.items.single().id)
        assertFalse(wySheets.hasMore)

        val qqSheets = api.recommendPlaylists(LxSearchSource.QQ, "6", "华语", 1).getOrThrow()
        assertEquals("5678", qqSheets.items.single().id)
        assertTrue(qqSheets.hasMore)
    }

    @Test
    fun rejectsUnsupportedInputsAndSanitizesFailuresAndCachesForFiveMinutes() = runBlocking {
        var now = 1_000L
        var calls = 0
        val api = LxDiscoverApi(now = { now }) { _, _, _ ->
            calls++
            ok("""{"code":200,"list":[{"id":1,"name":"榜单"}]}""")
        }
        assertTrue(api.topLists(LxSearchSource.KUWO).isFailure)
        assertTrue(api.topListSongs("bad_id", LxSearchSource.KUGOU, 1).isFailure)
        assertTrue(api.recommendPlaylists(LxSearchSource.QISHUI).isFailure)
        assertEquals(0, calls)

        api.topLists(LxSearchSource.NETEASE).getOrThrow()
        api.topLists(LxSearchSource.NETEASE).getOrThrow()
        assertEquals(1, calls)
        now += 300_000L
        api.topLists(LxSearchSource.NETEASE).getOrThrow()
        assertEquals(2, calls)

        val failing = LxDiscoverApi { _, _, _ -> throw IOException("https://private.example/?token=secret") }
        val error = failing.topLists(LxSearchSource.QQ).exceptionOrNull()!!
        assertFalse(error.message!!.contains("secret"))
        assertFalse(error.message!!.contains("https://"))
        assertNull(error.cause)

        val cancelled = LxDiscoverApi { _, _, _ -> throw CancellationException("已取消") }
        try {
            cancelled.topLists(LxSearchSource.QQ)
            fail("取消必须继续传播")
        } catch (expected: CancellationException) {
            assertEquals("已取消", expected.message)
        }
    }
}
