/* Copyright (c) 2023 MakcRe, MIT. 协议适配通知见 assets/licenses/kugou-api-MIT.txt。 */
package com.dd3boh.outertune.source.kugou

import com.dd3boh.outertune.utils.AppLogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal val kugouJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
internal fun JsonObject.text(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeUnless { it == "null" || it.isBlank() }
}.orEmpty()
internal fun JsonObject.obj(key: String): JsonObject = this[key] as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.number(vararg keys: String): Long? = text(*keys).toLongOrNull()
internal fun JsonObject.rows(vararg keys: String): List<JsonObject> = keys.firstNotNullOfOrNull { key ->
    (this[key] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }
}.orEmpty()
internal class KugouApiException(
    val code: Long?,
    message: String,
    val authenticationExpired: Boolean = false,
    val ineligible: Boolean = false,
) : IOException(message)

internal class KugouApi(private val session: () -> KugouSession) {
    private val TAG = "KugouApi"
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).followRedirects(false).build()
    // 短信与领取等写请求不能在未知结果后由网络库自动重发。
    private val writeClient = client.newBuilder().retryOnConnectionFailure(false).build()
    @Volatile private var serverOffset = 0L
    @Volatile var hasConfirmedServerTime = false
        private set
    val serverNow: Long get() = System.currentTimeMillis() + serverOffset

    suspend fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        body: JsonElement? = null,
        post: Boolean = body != null,
        host: String = "gateway.kugou.com",
        router: String? = null,
        web: Boolean = false,
        includeAccount: Boolean = true,
        defaults: Boolean = true,
        extraHeaders: Map<String, String> = emptyMap(),
        checkBusiness: Boolean = true,
        sessionOverride: KugouSession? = null,
    ): JsonObject {
        val bytes = raw(path, query, body?.toString().orEmpty(), post, host, router, web, includeAccount, defaults, extraHeaders, sessionOverride)
        val parsed = try { kugouJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }
        catch (_: Exception) { null } ?: run {
            AppLogManager.w(TAG, "酷狗接口返回了无法识别的内容，path=$path")
            throw IOException("酷狗接口返回了无法识别的内容")
        }
        if (checkBusiness) ensureSuccess(parsed)
        return parsed
    }

    internal fun buildRequest(
        path: String, query: Map<String, String>, body: String, post: Boolean,
        host: String = "gateway.kugou.com", router: String? = null, web: Boolean = false,
        includeAccount: Boolean = true, defaults: Boolean = true, extraHeaders: Map<String, String> = emptyMap(),
        sessionOverride: KugouSession? = null,
    ): Request {
        // 带点号的登录服务没有匹配的 TLS 证书，只能作为官方网关的 x-router 使用。
        require(host in setOf("gateway.kugou.com", "userservice.kugou.com", "login-user.kugou.com", "loginserviceretry.kugou.com", "kugouvip.kugou.com", "lyrics.kugou.com"))
        val current = sessionOverride ?: session()
        val time = (serverNow / 1000).toString()
        val params = linkedMapOf<String, String>()
        if (defaults) {
            params.putAll(mapOf("dfid" to current.dfid.ifBlank { "-" }, "mid" to current.mid, "uuid" to "-", "appid" to "3116", "clientver" to "11440", "clienttime" to time))
            if (includeAccount && current.loggedIn) params.putAll(mapOf("token" to current.token, "userid" to current.userId))
        }
        params.putAll(query)
        params["signature"] = KugouCrypto.signature(params, body, web)
        val url = "https://$host$path".toHttpUrl().newBuilder().apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
        val builder = Request.Builder().url(url)
            .header("User-Agent", "Android15-1070-11440-46-0-DiscoveryDRADProtocol-wifi")
            .header("dfid", current.dfid.ifBlank { "-" }).header("mid", current.mid)
            .header("clienttime", params["clienttime"] ?: time)
            .header("kg-rc", "1").header("kg-thash", "5d816a0").header("kg-rec", "1")
            .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
        router?.let { builder.header("x-router", it) }
        extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        if (post) builder.post(body.toRequestBody((if (body.startsWith("{")) "application/json; charset=utf-8" else "text/plain; charset=utf-8").toMediaType()))
        return builder.build()
    }

    suspend fun raw(
        path: String, query: Map<String, String>, body: String, post: Boolean,
        host: String = "gateway.kugou.com", router: String? = null, web: Boolean = false,
        includeAccount: Boolean = true, defaults: Boolean = true, extraHeaders: Map<String, String> = emptyMap(),
        sessionOverride: KugouSession? = null,
    ): ByteArray {
        val request = buildRequest(path, query, body, post, host, router, web, includeAccount, defaults, extraHeaders, sessionOverride)
        val call = (if (post) writeClient else client).newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(connectionFailure(e))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            updateServerTime(response.header("Date"))
                            if (response.header("ssa-code") != null) {
                                AppLogManager.w(TAG, "酷狗返回 ssa-code 安全验证标记（HTTP ${response.code}）")
                                throw KugouApiException(20028, "账号需要安全验证，请前往官方酷狗 APP 完成")
                            }
                            if (!response.isSuccessful) {
                                AppLogManager.w(TAG, "酷狗请求失败：HTTP ${response.code}，path=${path}")
                                throw IOException("酷狗请求失败（HTTP ${response.code}）")
                            }
                            val bytes = response.body?.let { body ->
                                try { body.bytes() } catch (e: IOException) { throw connectionFailure(e) }
                            } ?: throw IOException("酷狗接口没有返回内容")
                            if (continuation.isActive) continuation.resume(bytes)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
    }

    internal fun connectionFailure(error: IOException): IOException {
        // 只按异常类型分类，不保留可能带手机号、token 或完整 URL 的底层消息与 cause。
        val message = when (error) {
            is UnknownHostException -> "酷狗域名解析失败，请检查网络或 DNS 设置"
            is SSLException -> "酷狗安全连接失败，请检查系统时间或网络代理"
            is InterruptedIOException -> "酷狗请求超时，请检查网络后重试"
            is ConnectException -> "无法连接酷狗服务，请检查网络或稍后重试"
            else -> "酷狗连接中断，请检查网络或稍后重试"
        }
        AppLogManager.w(TAG, "酷狗网络连接失败：$message")
        return IOException(message)
    }

    internal fun updateServerTime(date: String?) {
        val remote = date?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull() }
        val offset = remote?.minus(System.currentTimeMillis())
        hasConfirmedServerTime = offset != null && kotlin.math.abs(offset) < TimeUnit.DAYS.toMillis(2)
        if (hasConfirmedServerTime) serverOffset = checkNotNull(offset)
    }

    fun ensureSuccess(body: JsonObject) {
        val status = body.number("status")
        val code = body.number("error_code", "errcode", "errorcode", "errno")
        if (status == 0L || (code != null && code != 0L)) {
            val message = body.text("error_msg", "errmsg", "message").lowercase()
            // 未经确认的数字码不能直接推断登录失效，也不将原始响应中的凭据展示到界面或日志。
            val expired = listOf("token expired", "invalid token", "token无效", "token已过期", "token过期", "登录已失效", "登录状态失效", "登录已过期").any(message::contains)
            val ineligible = listOf("不符合领取", "不满足领取", "未达到领取", "未满足领取", "没有领取资格").any(message::contains)
            val safeMessage = when {
                code == 20028L -> "账号需要安全验证，请前往官方酷狗 APP 完成"
                code == 131001L -> "今日已领取 VIP"
                expired -> "酷狗登录已失效，请重新登录"
                ineligible -> "当前账号暂不符合领取条件，请在官方客户端查看资格"
                else -> "酷狗暂时无法完成请求" + code?.let { "（错误码 $it）" }.orEmpty()
            }
            AppLogManager.w(TAG, "酷狗接口业务错误（错误码 $code）：$safeMessage")
            throw KugouApiException(code, safeMessage, expired, ineligible)
        }
    }
}
