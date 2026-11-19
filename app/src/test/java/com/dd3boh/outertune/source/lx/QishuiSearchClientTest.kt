package com.dd3boh.outertune.source.lx

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.URI
import java.net.URLDecoder

/** 验证汽水公共搜索协议和脚本参数持久化，不涉及 UI 或外部网络。 */
class QishuiSearchClientTest {
    private val track = """{
        "base_info":{"id":"7146240707408168993","name":" <em>因为</em>爱情&nbsp;&amp;现场 ","duration_ms":202052},
        "related_info":{
            "artist_links":[{"id":"1","name":"周杰伦"},{"id":"2","name":"那英"}],
            "album_link":{"id":"7145673767388186662","name":"现场专辑"}
        },
        "display_info":{"cover_url":{"uri":"test/cover","urls":["https://p3-luna.douyinpic.com/img/"],"template_prefix":"tplv-b829550vbb"}},
        "player_info":{"url":"https://private.example/audio","token":"private-player-value"},
        "future_field":{"nested":[1,null,"不应透传"]}
    }""".trimIndent()

    private fun response(vararg tracks: String): String = """{
        "err_no":0,"data":{"result_groups":[{"id":"tracks","data":[
            ${tracks.joinToString(",") { """{"entity":{"track":$it}}""" }}
        ],"has_more":true,"next_cursor":"20"}]}
    }"""

    private fun token(value: String = "test-token", expires: Long = System.currentTimeMillis() / 1000 + 3600): QishuiHttpResponse =
        QishuiHttpResponse(200, """{"err_no":0,"client_token":"$value","expires_time":$expires}""")

    private fun successfulSearch() = QishuiHttpResponse(200, response(track))

    @Test
    fun mapsPublicCatalogAndCoverWithoutExtraRequests() {
        val song = QishuiSearchClient.parseResponse(response(track)).single()
        val metadata = song.toMediaMetadata()
        assertEquals("qs_7146240707408168993", song.id)
        assertEquals("7146240707408168993", song.songmid)
        assertEquals("qs", metadata.source)
        assertEquals("7146240707408168993", metadata.sourceId)
        assertEquals("因为爱情 &现场", metadata.title)
        assertEquals("周杰伦 / 那英", song.singer)
        assertEquals(202, metadata.duration)
        assertEquals("现场专辑", metadata.album!!.title)
        assertEquals("https://p3-luna.douyinpic.com/img/test/cover~tplv-b829550vbb-resize:960:960.png", metadata.thumbnailUrl)
        assertTrue(song.meta.qualitys.isEmpty())
    }

    @Test
    fun persistsCatalogButNotSdkPlaybackCredentials() {
        val song = QishuiSearchClient.parseResponse(response(track)).single()
        val entity = song.toMediaMetadata().toSongEntity()
        val restored = lxJson.decodeFromString<LxMusicInfo>(entity.sourceData!!)
        val scriptInfo = lxJson.parseToJsonElement(entity.sourceData!!).jsonObject.toScriptMusicInfo()
        assertEquals(song.id, entity.id)
        assertEquals(song.source, entity.source)
        assertEquals(song.meta.songId, entity.sourceId)
        assertEquals(song.raw, restored.raw)
        assertEquals(song.id, restored.toMediaMetadata().id)
        assertEquals(song.songmid, scriptInfo["id"]!!.jsonPrimitive.content)
        assertEquals(song.songmid, scriptInfo["songId"]!!.jsonPrimitive.content)
        assertEquals(song.raw, scriptInfo["raw"])
        assertEquals("202.052", scriptInfo["duration"]!!.jsonPrimitive.content)
        assertEquals("03:22", scriptInfo["interval"]!!.jsonPrimitive.content)
        assertFalse(entity.sourceData!!.contains("player_info"))
        assertFalse(entity.sourceData!!.contains("private-player-value"))
        assertFalse(entity.sourceData!!.contains("future_field"))
        assertFalse(scriptInfo.containsKey("player_info"))
    }

    @Test
    fun preservesExistingRawTrackForRestoredQueues() {
        val original = lxJson.parseToJsonElement("""{
            "id":"qs_7146240707408168993","songmid":"7146240707408168993","source":"qs",
            "raw":{"id":"7146240707408168993","vid":"test-video-id","bit_rates":[{"quality":"higher"}],"future_field":{"value":1}}
        }""").jsonObject
        val scriptInfo = original.toScriptMusicInfo()
        assertEquals("7146240707408168993", scriptInfo["id"]!!.jsonPrimitive.content)
        assertEquals("test-video-id", scriptInfo["vid"]!!.jsonPrimitive.content)
        assertEquals(original["raw"]!!.jsonObject["bit_rates"], scriptInfo["bit_rates"])
        assertEquals(original["raw"]!!.jsonObject["future_field"], scriptInfo["future_field"])
    }

    @Test
    fun keepsLargeNumericIdsExactAndConvertsShortDurationsFromMilliseconds() {
        val song = QishuiSearchClient.parseResponse(response("""{
            "base_info":{"id":7146240707408168993,"name":"歌曲","duration_ms":5000},
            "related_info":{"artist_links":[{"name":"歌手"}]}
        }""")).single()
        assertEquals("7146240707408168993", song.songmid)
        assertTrue(song.raw!!["id"]!!.jsonPrimitive.isString)
        val scriptInfo = lxJson.parseToJsonElement(song.toMediaMetadata().sourceData!!).jsonObject.toScriptMusicInfo()
        assertEquals("7146240707408168993", scriptInfo["id"]!!.jsonPrimitive.content)
        assertTrue(scriptInfo["id"]!!.jsonPrimitive.isString)
        assertEquals("歌手", song.singer)
        assertEquals(5, song.toMediaMetadata().duration)
        assertEquals("00:05", song.interval)
    }

    @Test
    fun limitsAndDeduplicatesResultsEvenWhenServerReturnsTwenty() {
        val tracks = (1..20).map { """{"base_info":{"id":"$it","name":"歌曲 $it"}}""" }
        val songs = QishuiSearchClient.parseResponse(response(tracks[0], *tracks.toTypedArray()))
        assertEquals(15, songs.size)
        assertEquals(15, songs.map { it.id }.distinct().size)
        assertEquals(3, QishuiSearchClient.parseResponse(response(*tracks.toTypedArray()), 3).size)
    }

    @Test
    fun supportsCoverVariantsAndMissingDuration() {
        assertEquals("https://cdn.example/cover.jpg", QishuiSearchClient.coverUrl(JsonPrimitive("https://cdn.example/cover.jpg")))
        assertEquals("https://cdn.example/cover.jpg", QishuiSearchClient.coverUrl(lxJson.parseToJsonElement("""{"uri":"cover.jpg","urls":["https://cdn.example/"]}""")))
        assertEquals("https://cdn.example/cover.jpg", QishuiSearchClient.coverUrl(lxJson.parseToJsonElement("""{"uri":"cover.jpg","urls":["https://cdn.example/cover.jpg"]}""")))
        assertNull(QishuiSearchClient.coverUrl(null))
        val metadata = QishuiSearchClient.parseResponse(response("""{"base_info":{"id":"1","name":"歌曲"}}""")).single().toMediaMetadata()
        assertEquals(-1, metadata.duration)
        assertEquals("未知歌手", metadata.artists.single().name)
    }

    @Test
    fun onlyValidEmptyListsMeanNoMatchingSongs() {
        assertTrue(QishuiSearchClient.parseResponse("""{"err_no":0,"data":{"result_groups":[]}}""").isEmpty())
        assertTrue(QishuiSearchClient.parseResponse(response()).isEmpty())
        listOf(
            "", "null", "[]", "not json", "{}", """{"err_no":0}""",
            """{"err_no":0,"data":{"result_groups":[null]}}""",
            """{"err_no":0,"data":{"result_groups":[{}]}}""",
            """{"err_no":0,"data":{"result_groups":[{"id":"tracks"}]}}""",
            """{"err_no":0,"data":{"result_groups":[{"id":"tracks","data":[{}]}]}}""",
            response("""{"base_info":{"name":"没有 ID"}}"""),
            """{"err_no":403,"message":"不要展示上游正文"}""",
            """{"err_no":0,"data":{"error_code":403}}""",
        ).forEach { body ->
            val error = runCatching { QishuiSearchClient.parseResponse(body) }.exceptionOrNull()
            assertTrue("错误正文应失败，而不是空结果：$body", error is IllegalStateException)
            assertTrue(error!!.message!!.startsWith("汽水"))
            assertFalse(error.message!!.contains("不要展示上游正文"))
        }
    }

    @Test
    fun usesAnonymousTokenAndPublicPostProtocolWithOneDeviceIdentity() = runBlocking {
        val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
        var searchBody: String? = null
        val api = QishuiSearchApi { method, url, headers, body ->
            calls += Triple(method, url, headers)
            if (method == "GET") {
                assertNull(body)
                assertNull(headers["access-token"])
                token()
            } else {
                searchBody = body
                assertEquals("test-token", headers["access-token"])
                successfulSearch()
            }
        }
        assertEquals(1, api.search("  因为爱情  ").getOrThrow().size)
        assertEquals(listOf("GET", "POST"), calls.map { it.first })
        assertEquals(listOf("open.qishui.com", "open.douyin.com"), calls.map { URI(it.second).host })
        assertEquals(listOf("/api/luna/v1/platform/client/token/get/", "/api/luna/v1/platform/search/"), calls.map { URI(it.second).path })
        val identities = calls.map { call ->
            val params = URI(call.second).rawQuery.split("&").associate {
                val (key, value) = it.split("=", limit = 2)
                key to URLDecoder.decode(value, "UTF-8")
            }
            assertEquals(params["device_id"], params["did"])
            assertEquals(params["device_id"], params["iid"])
            assertTrue(params["device_id"]!!.matches(Regex("[1-9][0-9]{18}")))
            assertEquals("qishui-${params["device_id"]}", params["vehicle_id"])
            params["device_id"]
        }
        assertEquals(identities[0], identities[1])
        assertTrue(calls.all { it.third["User-Agent"] == "LunaOpenSDK/1.0.10" && "Cookie" !in it.third && "X-Argus" !in it.third })
        val body = lxJson.parseToJsonElement(searchBody!!).jsonObject
        assertEquals("因为爱情", body["q"]!!.jsonPrimitive.content)
        assertEquals("0", body["cursor"]!!.jsonPrimitive.content)
        assertEquals(body["search_id"], body["from_search_id"])
        assertEquals("track", body["search_type"]!!.jsonPrimitive.content)
    }

    @Test
    fun sharesTokenAcrossConcurrentSearches() = runBlocking {
        var tokenCount = 0
        val tokenStarted = CompletableDeferred<Unit>()
        val releaseToken = CompletableDeferred<Unit>()
        val api = QishuiSearchApi { method, _, _, _ ->
            if (method == "GET") {
                tokenCount++
                tokenStarted.complete(Unit)
                releaseToken.await()
                token()
            } else successfulSearch()
        }
        val searches = (1..3).map { async { api.search("关键词$it").getOrThrow() } }
        tokenStarted.await()
        releaseToken.complete(Unit)
        searches.awaitAll()
        assertEquals(1, tokenCount)
    }

    @Test
    fun oldConcurrentFailuresDoNotClearNewToken() = runBlocking {
        var tokenCount = 0
        val bothOldRequests = CompletableDeferred<Unit>()
        val newRequestSucceeded = CompletableDeferred<Unit>()
        var oldRequests = 0
        val api = QishuiSearchApi { method, _, headers, body ->
            if (method == "GET") token("test-token-${++tokenCount}") else {
                val query = lxJson.parseToJsonElement(body!!).jsonObject["q"]!!.jsonPrimitive.content
                if (headers["access-token"] == "test-token-1") {
                    if (++oldRequests == 2) bothOldRequests.complete(Unit)
                    bothOldRequests.await()
                    if (query == "第二个") newRequestSucceeded.await()
                    QishuiHttpResponse(200, """{"err_no":28001008}""")
                } else {
                    newRequestSucceeded.complete(Unit)
                    successfulSearch()
                }
            }
        }
        listOf("第一个", "第二个").map { async { api.search(it).getOrThrow() } }.awaitAll()
        assertEquals(2, tokenCount)
    }

    @Test
    fun refreshesAtMostOnceAndDoesNotCacheFailure() = runBlocking {
        var tokenCount = 0
        var searchCount = 0
        var failSearch = true
        val api = QishuiSearchApi { method, _, _, _ ->
            if (method == "GET") token("test-token-${++tokenCount}") else {
                searchCount++
                if (failSearch) QishuiHttpResponse(200, """{"err_no":0,"data":{"error_code":28001008}}""") else successfulSearch()
            }
        }
        assertTrue(api.search("歌曲").isFailure)
        assertEquals(2, tokenCount)
        assertEquals(2, searchCount)
        failSearch = false
        assertTrue(api.search("歌曲").isSuccess)
        assertEquals(3, tokenCount)
        assertEquals(3, searchCount)
    }

    @Test
    fun cachesQueriesForFiveMinutesAndTokensForAtMostOneHour() = runBlocking {
        var time = 1_000_000L
        var tokenCount = 0
        var searchCount = 0
        val api = QishuiSearchApi(now = { time }) { method, _, _, _ ->
            if (method == "GET") {
                tokenCount++
                token()
            } else {
                searchCount++
                successfulSearch()
            }
        }
        api.search("歌曲").getOrThrow()
        api.search(" 歌曲 ").getOrThrow()
        assertEquals(1, searchCount)
        time += 300_000
        api.search("歌曲").getOrThrow()
        assertEquals(2, searchCount)
        assertEquals(1, tokenCount)
        time += 3_600_000
        api.search("新关键词").getOrThrow()
        assertEquals(2, tokenCount)
        repeat(21) { api.search("缓存$it").getOrThrow() }
        val before = searchCount
        api.search("缓存0").getOrThrow()
        assertEquals(before + 1, searchCount)
    }

    @Test
    fun honorsServerExpiryAndFallbackTtl() = runBlocking {
        var time = 1_000_000L
        var tokenCount = 0
        val api = QishuiSearchApi(now = { time }) { method, _, _, _ ->
            if (method == "GET") {
                tokenCount++
                if (tokenCount == 1) token(expires = (time + 60_000) / 1000)
                else QishuiHttpResponse(200, """{"err_no":0,"client_token":"test-token"}""")
            } else successfulSearch()
        }
        api.search("一").getOrThrow()
        time += 30_000
        api.search("二").getOrThrow()
        assertEquals(2, tokenCount)
        time += 300_000
        api.search("三").getOrThrow()
        assertEquals(3, tokenCount)
    }

    @Test
    fun rejectsExpiredTokensAndDoesNotCacheNearlyExpiredTokens() = runBlocking {
        val time = 1_000_000L
        val expired = QishuiSearchApi(now = { time }) { _, _, _, _ -> token(expires = time / 1000) }
        val error = expired.search("歌曲").exceptionOrNull()!!
        assertTrue(error.message!!.contains("已过期令牌"))
        var tokenCount = 0
        val nearlyExpired = QishuiSearchApi(now = { time }) { method, _, _, _ ->
            if (method == "GET") {
                tokenCount++
                token(expires = (time + 20_000) / 1000)
            } else successfulSearch()
        }
        nearlyExpired.search("一").getOrThrow()
        nearlyExpired.search("二").getOrThrow()
        assertEquals(2, tokenCount)
    }

    @Test
    fun sanitizesTransportAndHttpErrors() = runBlocking {
        val api = QishuiSearchApi { _, _, _, _ -> throw IOException("access-token=private-token https://private.example/") }
        val error = api.search("歌曲").exceptionOrNull()!!
        assertTrue(error.message!!.startsWith("汽水"))
        assertFalse(error.message!!.contains("private-token"))
        assertNull(error.cause)
        val httpApi = QishuiSearchApi { _, _, _, _ -> QishuiHttpResponse(503, "private upstream body") }
        assertTrue(httpApi.search("歌曲").exceptionOrNull()!!.message!!.contains("HTTP 503"))
    }

    @Test
    fun missingTokenIsAnErrorAndCanRetry() = runBlocking {
        var tokenCount = 0
        val api = QishuiSearchApi { method, _, _, _ ->
            if (method == "GET" && ++tokenCount == 1) QishuiHttpResponse(200, """{"err_no":0}""")
            else if (method == "GET") token() else successfulSearch()
        }
        assertTrue(api.search("歌曲").isFailure)
        assertTrue(api.search("歌曲").isSuccess)
    }

    @Test
    fun cancellationPropagatesAndDoesNotPoisonTokenInitialization() = runBlocking {
        var cancelled = true
        val api = QishuiSearchApi { method, _, _, _ ->
            if (cancelled) throw CancellationException("取消搜索")
            else if (method == "GET") token() else successfulSearch()
        }
        try {
            api.search("歌曲")
            fail("取消不能包装成失败结果")
        } catch (_: CancellationException) {
            // 切换平台时取消请求仍由调用协程处理。
        }
        cancelled = false
        assertTrue(api.search("歌曲").isSuccess)
    }

    @Test
    fun leavesExistingPlatformScriptParametersUntouched() {
        val original = lxJson.parseToJsonElement("""{"id":"kw_1","songmid":"1","source":"kw","raw":null}""").jsonObject
        assertSame(original, original.toScriptMusicInfo())
    }
}
