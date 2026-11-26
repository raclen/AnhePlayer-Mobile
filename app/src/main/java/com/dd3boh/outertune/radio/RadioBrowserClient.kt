package com.dd3boh.outertune.radio

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object RadioBrowserClient {
    private const val TAG = "RadioBrowserClient"
    private const val DISCOVERY_URL = "https://all.api.radio-browser.info/json/servers"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val defaultServers = listOf(
        "de1.api.radio-browser.info",
        "nl1.api.radio-browser.info",
        "at1.api.radio-browser.info",
        "all.api.radio-browser.info"
    )

    @Volatile
    private var preferredBaseUrl: String = "https://${defaultServers.first()}"

    @Serializable
    private data class ServerItem(val name: String = "", val ip: String? = null)

    suspend fun discoverServers(): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(DISCOVERY_URL)
                .header("User-Agent", "AnhePlayer-Mobile/2.0")
                .header("Accept", "application/json")
                .build()

            val list = json.decodeFromString<List<ServerItem>>(requestBody(request))
            val discovered = list.map { it.name.trim() }.filter { it.isNotBlank() }
            if (discovered.isNotEmpty()) {
                preferredBaseUrl = "https://${discovered.first()}"
                return@withContext discovered
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Server discovery failed, using fallback servers", e)
        }
        defaultServers
    }

    suspend fun fetchStations(
        countryCode: String? = null,
        language: String? = null,
        bitrateKey: String? = null,
        query: String? = null,
        limit: Int = 100,
        order: String = "clickcount", // "clickcount", "votes", "random", "clicktrend"
        offset: Int = 0,
    ): RadioStationPage = requestStations(
        url = { baseUrl -> stationSearchUrl(baseUrl, countryCode, language, bitrateKey, query, limit, order, offset) },
        parse = { body -> parseStationPage(body, offset, limit) },
    )

    suspend fun fetchStationsByUuid(uuids: Set<String>): List<RadioStation> = withContext(Dispatchers.IO) {
        val stations = mutableListOf<RadioStation>()
        // 按 UUID 分批查询，不使用热门列表的 limit，也不应用地区、语言或码率过滤。
        for (batch in uuids.filter { it.isNotBlank() }.sorted().chunked(50)) {
            val ids = batch.toSet()
            stations += requestStations(
                url = { baseUrl -> stationsByUuidUrl(baseUrl, ids) },
                parse = { body -> parseFavoriteStations(body, ids) },
            )
        }
        stations.distinctBy { it.stationUuid }
    }

    internal fun stationsByUuidUrl(baseUrl: String, uuids: Set<String>): HttpUrl =
        "$baseUrl/json/stations/byuuid".toHttpUrl().newBuilder()
            .addQueryParameter("uuids", uuids.sorted().joinToString(","))
            .addQueryParameter("limit", uuids.size.toString())
            .build()

    internal fun parseFavoriteStations(body: String, uuids: Set<String>): List<RadioStation> =
        json.decodeFromString<List<RadioStation>>(body)
            .filter { it.stationUuid.isNotBlank() && it.stationUuid in uuids }
            .distinctBy { it.stationUuid }

    private suspend fun <T> requestStations(
        url: (String) -> HttpUrl,
        parse: (String) -> T,
    ): T = withContext(Dispatchers.IO) {
        val candidates = listOf(preferredBaseUrl) + defaultServers.map { "https://$it" }.filter { it != preferredBaseUrl }

        var lastException: Exception? = null
        for (baseUrl in candidates.distinct()) {
            try {
                val request = Request.Builder()
                    .url(url(baseUrl))
                    .header("User-Agent", "AnhePlayer-Mobile/2.0")
                    .header("Accept", "application/json")
                    .build()

                val page = parse(requestBody(request))
                preferredBaseUrl = baseUrl
                return@withContext page
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Failed requesting stations from $baseUrl", e)
            }
        }

        Log.e(TAG, "All radio-browser mirrors failed", lastException)
        // 网络失败不能冒充空结果，否则分页会被误判为已经到底。
        throw IOException("暂时无法连接电台目录，请稍后重试", lastException)
    }

    internal fun stationSearchUrl(
        baseUrl: String,
        countryCode: String? = null,
        language: String? = null,
        bitrateKey: String? = null,
        query: String? = null,
        limit: Int = 100,
        order: String = "clickcount",
        offset: Int = 0,
    ): HttpUrl {
        require(limit > 0 && offset >= 0)
        val (bitrateMin, bitrateMax) = RadioConstants.getBitrateRange(bitrateKey)
        val urlBuilder = "$baseUrl/json/stations/search".toHttpUrl().newBuilder()
            .addQueryParameter("hidebroken", "true")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("order", order)
        if (order != "random") {
            urlBuilder.addQueryParameter("reverse", "true")
        }
        if (!countryCode.isNullOrBlank()) {
            urlBuilder.addQueryParameter("countrycode", countryCode.trim().uppercase())
        }
        if (!language.isNullOrBlank()) {
            urlBuilder.addQueryParameter("language", language.trim().lowercase())
        }
        if (bitrateMin != null) {
            urlBuilder.addQueryParameter("bitrateMin", bitrateMin.toString())
        }
        if (bitrateMax != null) {
            urlBuilder.addQueryParameter("bitrateMax", bitrateMax.toString())
        }
        if (!query.isNullOrBlank()) {
            urlBuilder.addQueryParameter("name", query.trim())
        }
        return urlBuilder.build()
    }

    internal fun parseStationPage(body: String, offset: Int, limit: Int): RadioStationPage {
        val rawStations = json.decodeFromString<List<RadioStation>>(body)
        return RadioStationPage(
            stations = rawStations
                .filter { it.stationUuid.isNotBlank() && it.lastCheckOk == 1 && it.playUrl.isNotBlank() }
                .distinctBy { it.stationUuid },
            // 偏移量和末页判定必须依据原始条数，不能使用过滤、去重后的数量。
            nextOffset = offset + rawStations.size,
            hasMore = rawStations.size >= limit,
        )
    }

    private suspend fun requestBody(request: Request): String = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException("HTTP error ${it.code}")
                        it.body.string()
                    }
                    if (continuation.isActive) continuation.resume(body)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }
}
