package com.dd3boh.outertune.radio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

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

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val list = json.decodeFromString<List<ServerItem>>(body)
                    val discovered = list.map { it.name.trim() }.filter { it.isNotBlank() }
                    if (discovered.isNotEmpty()) {
                        preferredBaseUrl = "https://${discovered.first()}"
                        return@withContext discovered
                    }
                }
            }
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
    ): List<RadioStation> = withContext(Dispatchers.IO) {
        val (bitrateMin, bitrateMax) = RadioConstants.getBitrateRange(bitrateKey)
        val candidates = listOf(preferredBaseUrl) + defaultServers.map { "https://$it" }.filter { it != preferredBaseUrl }

        var lastException: Exception? = null
        for (baseUrl in candidates.distinct()) {
            try {
                val urlBuilder = "$baseUrl/json/stations/search".toHttpUrlOrNull()?.newBuilder() ?: continue
                urlBuilder.addQueryParameter("hidebroken", "true")
                urlBuilder.addQueryParameter("limit", limit.toString())
                urlBuilder.addQueryParameter("order", order)
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

                val targetUrl = urlBuilder.build()
                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "AnhePlayer-Mobile/2.0")
                    .header("Accept", "application/json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("HTTP error ${response.code}")
                    }
                    val body = response.body?.string().orEmpty()
                    val stations = json.decodeFromString<List<RadioStation>>(body)
                        .filter { it.lastCheckOk == 1 && it.playUrl.isNotBlank() }

                    preferredBaseUrl = baseUrl
                    return@withContext stations
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Failed requesting stations from $baseUrl", e)
            }
        }

        Log.e(TAG, "All radio-browser mirrors failed", lastException)
        emptyList()
    }
}
