package com.dd3boh.outertune.source.lx

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.LxSourceQualityKey
import com.dd3boh.outertune.constants.LxSourceScriptKey
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object LxSourceRuntime {
    private const val TAG = "LxSourceRuntime"
    private const val DEFAULT_QUALITY = "320k"

    private lateinit var appContext: Context
    private val httpClient = OkHttpClient()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var context: QuickJSContext? = null
    private var loadedScriptHash: String? = null
    private var runtimeKey: String = ""

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun resolve(metadata: MediaMetadata, qualityOverride: String? = null): String = withContext(Dispatchers.IO) {
        check(::appContext.isInitialized) { "洛雪音源运行时尚未初始化" }
        val script = appContext.dataStore[LxSourceScriptKey]
            ?.takeIf(String::isNotBlank)
            ?: appContext.assets.open("script/default-lx-source.js").bufferedReader().use { it.readText() }
        ensureLoaded(script)

        val source = metadata.source ?: error("歌曲缺少洛雪来源信息")
        val musicInfo = metadata.sourceData?.let { lxJson.parseToJsonElement(it).jsonObject }
            ?: error("歌曲缺少洛雪音源参数")
        val requestedQuality = qualityOverride ?: appContext.dataStore.get(LxSourceQualityKey, DEFAULT_QUALITY)
        val quality = selectSupportedQuality(musicInfo, requestedQuality)
        val requestKey = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<JsonObject>()
        pending[requestKey] = deferred
        val payload = buildJsonObject {
            put("requestKey", requestKey)
            put("data", buildJsonObject {
                put("source", source)
                put("action", "musicUrl")
                put("info", buildJsonObject {
                    put("type", quality)
                    put("musicInfo", musicInfo)
                })
            })
        }
        handler?.post {
            context?.getGlobalObject()?.getJSFunction("__lx_native__")
                ?.call(runtimeKey, "request", payload.toString())
        }
        try {
            val result = withTimeout(60_000) { deferred.await() }
            check(result["status"]?.jsonPrimitive?.contentOrNull == "true") {
                result["errorMessage"]?.jsonPrimitive?.contentOrNull ?: "洛雪音源解析失败"
            }
            result["result"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("url")?.jsonPrimitive?.contentOrNull
                ?.also { url ->
                    runCatching { URI(url).scheme?.lowercase() }.getOrNull()?.let { scheme ->
                        Log.i(TAG, "音源播放地址协议: $scheme")
                    }
                    // debug 包打印完整地址，便于排查 CDN 404 / 403 类问题
                    if (BuildConfig.DEBUG) Log.d(TAG, "音源解析地址: $url")
                }
                ?: error("洛雪音源没有返回播放地址")
        } finally {
            pending.remove(requestKey)
        }
    }

    @Synchronized
    private fun ensureLoaded(script: String) {
        val scriptHash = script.sha256()
        if (loadedScriptHash == scriptHash && context != null) return
        destroyRuntime()
        QuickJSLoader.init()
        runtimeKey = UUID.randomUUID().toString()
        thread = HandlerThread("LxSourceRuntime").apply { start() }
        handler = Handler(thread!!.looper)
        val ready = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        handler!!.post {
            try {
                val js = QuickJSContext.create()
                context = js
                js.setConsole(object : QuickJSContext.Console {
                    override fun log(info: String) { Log.d(TAG, info) }
                    override fun info(info: String) { Log.i(TAG, info) }
                    override fun warn(info: String) { Log.w(TAG, info) }
                    override fun error(info: String) { Log.e(TAG, info) }
                })
                createNativeBridge(js)
                js.evaluate(appContext.assets.open("script/user-api-preload.js").bufferedReader().use { it.readText() })
                js.getGlobalObject().getJSFunction("lx_setup").call(
                    runtimeKey, "outertune_lx", "OuterTune 洛雪音源", "", "1.0.0", "", "", script
                )
                js.evaluate(script)
                loadedScriptHash = scriptHash
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                ready.complete(Unit)
            }
        }
        kotlinx.coroutines.runBlocking { ready.await() }
        failure?.let { throw IllegalStateException("加载洛雪音源失败：${it.message}", it) }
    }

    private fun createNativeBridge(js: QuickJSContext) {
        js.getGlobalObject().setProperty("__lx_native_call__") { args ->
            if (args[0] == runtimeKey) handleScriptAction(args[1] as String, args[2] as String)
            null
        }
        js.getGlobalObject().setProperty("__lx_native_call__set_timeout") { args ->
            handler?.postDelayed({ callJs("__set_timeout__", args[0].toString()) }, (args[1] as Number).toLong())
            null
        }
        js.getGlobalObject().setProperty("__lx_native_call__utils_str2b64") { args ->
            Base64.encodeToString((args[0] as String).toByteArray(), Base64.NO_WRAP)
        }
        js.getGlobalObject().setProperty("__lx_native_call__utils_b642buf") { args ->
            Base64.decode(args[0] as String, Base64.NO_WRAP).joinToString(prefix = "[", postfix = "]") { (it.toInt() and 0xff).toString() }
        }
        js.getGlobalObject().setProperty("__lx_native_call__utils_str2md5") { args ->
            val decoded = URLDecoder.decode(args[0] as String, StandardCharsets.UTF_8.name())
            MessageDigest.getInstance("MD5").digest(decoded.toByteArray()).joinToString("") { "%02x".format(it) }
        }
        js.getGlobalObject().setProperty("__lx_native_call__utils_aes_encrypt") { args ->
            aesEncrypt(args[0] as String, args[1] as String, args[2] as String, args[3] as String)
        }
        js.getGlobalObject().setProperty("__lx_native_call__utils_rsa_encrypt") { "" }
    }

    private fun handleScriptAction(action: String, data: String) {
        val json = runCatching { lxJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return
        when (action) {
            "request" -> executeHttpRequest(json)
            "response" -> json["requestKey"]?.jsonPrimitive?.contentOrNull?.let { pending.remove(it)?.complete(json) }
            "init" -> Log.i(TAG, "洛雪音源初始化完成")
            "log" -> Log.d(TAG, data)
        }
    }

    private fun executeHttpRequest(data: JsonObject) {
        val requestKey = data["requestKey"]?.jsonPrimitive?.contentOrNull ?: return
        val url = data["url"]?.jsonPrimitive?.contentOrNull ?: return
        val options = data["options"]?.jsonObject ?: JsonObject(emptyMap())
        val method = options["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        val headers = options["headers"] as? JsonObject
        val bodyData = options["data"]
        val builder = Request.Builder().url(url)
        headers?.forEach { (name, value) -> value.jsonPrimitive.contentOrNull?.let { builder.addHeader(name, it) } }
        val requestBody = when {
            method == "GET" || method == "HEAD" -> null
            bodyData is JsonObject -> FormBody.Builder().apply {
                bodyData.forEach { (key, value) -> add(key, value.jsonPrimitive.contentOrNull.orEmpty()) }
            }.build()
            bodyData != null -> bodyData.toString().toRequestBody("application/json".toMediaTypeOrNull())
            else -> ByteArray(0).toRequestBody(null)
        }
        builder.method(method, requestBody)
        httpClient.newCall(builder.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = respondHttp(requestKey, e.message ?: "网络请求失败", null)
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val body = it.body.string()
                    respondHttp(requestKey, null, buildJsonObject {
                        put("statusCode", it.code)
                        put("statusMessage", it.message)
                        put("headers", buildJsonObject { it.headers.forEach { header -> put(header.first, header.second) } })
                        put("body", runCatching { lxJson.parseToJsonElement(body) }.getOrElse { kotlinx.serialization.json.JsonPrimitive(body) })
                    })
                }
            }
        })
    }

    private fun respondHttp(requestKey: String, error: String?, response: JsonObject?) {
        val payload = buildJsonObject {
            put("requestKey", requestKey)
            if (error == null) put("error", kotlinx.serialization.json.JsonNull) else put("error", error)
            if (response == null) put("response", kotlinx.serialization.json.JsonNull) else put("response", response)
        }
        callJs("response", payload.toString())
    }

    private fun callJs(action: String, data: String) {
        handler?.post { context?.getGlobalObject()?.getJSFunction("__lx_native__")?.call(runtimeKey, action, data) }
    }

    /**
     * 销毁 QuickJS 运行时
     *
     * QuickJS 要求 create/destroy 必须在同一个线程，所以销毁动作必须回到运行时自己的
     * HandlerThread 上执行，否则会抛 "Must be call same thread in QuickJSContext.create"。
     */
    private fun destroyRuntime() {
        val js = context
        val handlerThread = thread
        val runtimeHandler = handler
        context = null
        thread = null
        handler = null
        loadedScriptHash = null

        if (runtimeHandler == null) {
            runCatching { js?.destroy() }
                .onFailure { Log.w(TAG, "销毁洛雪音源运行时失败: ${it.message}") }
            handlerThread?.quitSafely()
            return
        }

        val done = CountDownLatch(1)
        val posted = runtimeHandler.post {
            try {
                runCatching { js?.destroy() }
                    .onFailure { Log.w(TAG, "销毁洛雪音源运行时失败: ${it.message}") }
            } finally {
                handlerThread?.quitSafely()
                done.countDown()
            }
        }
        if (!posted || !done.await(3, TimeUnit.SECONDS)) {
            Log.w(TAG, "销毁洛雪音源运行时超时，强制释放资源")
            handlerThread?.quitSafely()
        }
    }

    private fun selectSupportedQuality(info: JsonObject, requested: String): String {
        val metadata = runCatching { lxJson.decodeFromString<LxMusicInfo>(info.toString()) }.getOrNull()
        val supported = metadata?.meta?.qualitys.orEmpty().map { it.type }
            .plus(metadata?.meta?._qualitys?.keys.orEmpty())
            .filter(String::isNotBlank)
            .distinct()
        return requested.takeIf { it in supported }
            ?: supported.firstOrNull()
            ?: requested
    }

    private fun String.sha256() = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray()).joinToString("") { "%02x".format(it) }

    private fun aesEncrypt(data: String, key: String, iv: String, mode: String): String = runCatching {
        val transformation = if (mode.contains("cbc", true)) "AES/CBC/PKCS7Padding" else "AES/ECB/NoPadding"
        val cipher = Cipher.getInstance(transformation)
        val keySpec = SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AES")
        if (transformation.contains("CBC")) {
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(Base64.decode(iv, Base64.DEFAULT)))
        } else cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        Base64.encodeToString(cipher.doFinal(Base64.decode(data, Base64.DEFAULT)), Base64.NO_WRAP)
    }.getOrDefault("")
}
