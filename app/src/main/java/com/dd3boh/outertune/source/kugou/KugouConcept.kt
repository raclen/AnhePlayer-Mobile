package com.dd3boh.outertune.source.kugou

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import com.dd3boh.outertune.models.MediaMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object KugouConcept {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accountMutex = Mutex()
    private val deviceMutex = Mutex()
    private val claimMutex = Mutex()
    private val renewalMutex = Mutex()
    private val restored = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(KugouAccountState())
    private val version = MutableStateFlow(0L)
    val accountState: StateFlow<KugouAccountState> = mutableState.asStateFlow()
    val sessionVersion: StateFlow<Long> = version.asStateFlow()
    @Volatile private var session = KugouSession()
    private lateinit var context: Context
    private lateinit var store: KugouSessionStore
    private val api = KugouApi { session }
    private val preferences get() = context.getSharedPreferences("kugou_concept_state", Context.MODE_PRIVATE)
    private val qrVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()

    @Synchronized fun initialize(context: Context) {
        if (::store.isInitialized) return
        this.context = context.applicationContext
        store = KugouSessionStore(this.context)
        mutableState.update { it.copy(status = KugouAccountStatus.RESTORING) }
        scope.launch {
            try {
                accountMutex.withLock {
                    session = store.read()
                    store.write(session)
                    version.update { it + 1 }
                    publishSession()
                }
                restored.complete(Unit)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                mutableState.update { it.copy(status = KugouAccountStatus.SIGNED_OUT, error = "酷狗账号恢复失败，请重新登录") }
                restored.completeExceptionally(IOException("酷狗账号恢复失败"))
                return@launch
            }
            if (session.loggedIn) optional { refreshAccount() }
            onForeground()
        }
    }
    private fun requireInitialized() { check(::store.isInitialized) { "酷狗账号尚未初始化" } }
    suspend fun awaitRestored() {
        requireInitialized()
        restored.await()
    }
    private suspend fun requireAccount() {
        awaitRestored()
        check(session.loggedIn && mutableState.value.status != KugouAccountStatus.EXPIRED) { "请前往设置登录酷狗账号" }
        renewSessionIfNeeded()
        check(session.loggedIn && mutableState.value.status != KugouAccountStatus.EXPIRED) { "请前往设置登录酷狗账号" }
    }
    private suspend fun <T> optional(action: suspend () -> T): T? = try { action() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    private fun publishSession() {
        mutableState.update { it.copy(status = if (session.loggedIn) KugouAccountStatus.SIGNED_IN else KugouAccountStatus.SIGNED_OUT,
            userId = session.userId.takeIf { session.loggedIn }, nickname = session.nickname,
            autoClaimVip = preferences.getBoolean("auto_claim", true), error = null) }
    }
    private suspend fun save(next: KugouSession) = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        store.write(next)
        session = next
        version.update { it + 1 }
        publishSession()
    }
    private fun checkVersion(expected: Long) { check(version.value == expected) { "酷狗账号已变化，请重试" } }

    private suspend fun ensureDevice() {
        awaitRestored()
        deviceMutex.withLock {
            if (session.dfid.isNotBlank()) return@withLock
            val expected = version.value
            val memory = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            val space = StatFs(context.filesDir.absolutePath).availableBytes
            val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val device = buildJsonObject {
                put("availableRamSize", memory.availMem); put("availableRomSize", space); put("availableSDSize", space)
                put("basebandVer", ""); put("batteryLevel", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceAtLeast(0))
                put("batteryStatus", battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS))
                put("brand", Build.BRAND); put("manufacturer", Build.MANUFACTURER); put("device", Build.DEVICE)
                put("buildSerial", "unknown"); put("imei", session.guid); put("imsi", ""); put("uuid", session.guid)
                // 不采集硬件标识，也不捏造传感器数值；未采集项目按空值传输。
            }
            val key = KugouCrypto.randomKey(6)
            val encrypted = KugouCrypto.encryptDevice(device.toString(), key)
            val p = KugouCrypto.rsa(buildJsonObject { put("aes", key); put("uid", session.userId); put("token", session.token) }.toString())
            val bytes = api.raw("/risk/v2/r_register_dev", mapOf("part" to "1", "platid" to "1", "p" to p), encrypted, true, "userservice.kugou.com", includeAccount = false)
            val decoded = runCatching { kugouJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
                ?: (kugouJson.parseToJsonElement(KugouCrypto.decryptDevice(bytes, key)) as? JsonObject)
                ?: throw IOException("酷狗设备注册失败")
            api.ensureSuccess(decoded)
            val dfid = decoded.obj("data").text("dfid")
            check(dfid.isNotBlank()) { "酷狗没有返回设备注册信息，请稍后重试" }
            accountMutex.withLock { checkVersion(expected); save(session.copy(dfid = dfid)) }
        }
    }

    suspend fun createQrLogin(): KugouQrLogin {
        ensureDevice()
        val expected = version.value
        val body = api.request("/v2/qrcode", host = "login-user.kugou.com", web = true, includeAccount = false,
            query = mapOf("appid" to "1001", "type" to "1", "plat" to "4", "srcappid" to "2919", "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&"))
        checkVersion(expected)
        val key = body.obj("data").text("qrcode")
        check(key.isNotBlank()) { "酷狗未返回登录二维码" }
        qrVersions[key] = expected
        // 二维码仅由本地绘制，授权 key 不交给图片生成网站。
        return KugouQrLogin(key, "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&qrcode=" + java.net.URLEncoder.encode(key, "UTF-8"))
    }
    suspend fun checkQrLogin(key: String): KugouQrStatus {
        val expected = qrVersions[key] ?: throw IOException("二维码已失效，请刷新")
        checkVersion(expected)
        val body = api.request("/v2/get_userinfo_qrcode", host = "login-user.kugou.com", web = true, includeAccount = false,
            query = mapOf("plat" to "4", "appid" to "3116", "srcappid" to "2919", "qrcode" to key))
        checkVersion(expected)
        val data = body.obj("data")
        val status = data.number("status")?.toInt() ?: 1
        if (status == 4) {
            completeLogin(data, expected)
            qrVersions.clear()
        } else if (status == 0) qrVersions.remove(key)
        return KugouQrStatus(status, when (status) { 0 -> "二维码已过期"; 2 -> "已扫码，请在酷狗确认授权"; 4 -> "登录成功"; else -> "请用酷狗扫描二维码" })
    }
    suspend fun requestSms(mobile: String) {
        require(mobile.matches(Regex("1\\d{10}"))) { "请输入正确的手机号" }
        ensureDevice()
        api.request("/v7/send_mobile_code", host = "login.user.kugou.com", post = true, includeAccount = false,
            body = buildJsonObject { put("businessid", 5); put("mobile", mobile); put("plat", 3) })
    }
    suspend fun loginWithSms(mobile: String, code: String, userId: String? = null) {
        require(mobile.matches(Regex("1\\d{10}")) && code.matches(Regex("\\d{4,8}"))) { "请检查手机号和验证码" }
        ensureDevice()
        val expected = version.value
        val time = api.serverNow
        val key = KugouCrypto.randomKey(16)
        val encrypted = KugouCrypto.encryptLogin(buildJsonObject { put("mobile", mobile); put("code", code) }.toString(), key)
        val t1 = KugouCrypto.encryptHex("|$time", "5e4ef500e9597fe004bd09a46d8add98", "04bd09a46d8add98")
        val t2 = KugouCrypto.encryptHex("${session.guid}|0f607264fc6318a92b9e13c65db7cd3c||${Build.DEVICE}|$time", "fd14b35e3f81af3817a20ae7adae7020", "17a20ae7adae7020")
        val body = buildJsonObject {
            put("plat", 1); put("support_multi", 1); put("t1", t1); put("t2", t2); put("clienttime_ms", time)
            put("mobile", mobile.take(2) + "*****" + mobile.takeLast(1)); put("key", KugouCrypto.parameterKey(time.toString()))
            put("pk", KugouCrypto.rsa(buildJsonObject { put("clienttime_ms", time); put("key", key) }.toString(), raw = true).uppercase())
            put("params", encrypted); put("dfid", session.dfid); put("dev", Build.DEVICE); put("gitversion", "5f0b7c4")
            userId?.let { put("userid", it) }
        }
        val result = api.request("/v7/login_by_verifycode", host = "loginserviceretry.kugou.com", body = body, includeAccount = false,
            extraHeaders = mapOf("support-calm" to "1", "User-Agent" to "Android16-1070-11440-130-0-LOGIN-wifi"), checkBusiness = false)
        val choices = result.obj("data").rows("info_list").mapNotNull {
            it.text("userid", "kugouid").takeIf(String::isNotBlank)?.let { id -> KugouLoginAccount(id, it.text("nickname", "nick_name").ifBlank { id }) }
        }
        if (choices.isNotEmpty() && userId == null) throw KugouAccountSelectionException(choices)
        api.ensureSuccess(result)
        completeLogin(KugouLoginProtocol.unpack(result.obj("data"), key), expected)
    }
    private suspend fun completeLogin(data: JsonObject, expected: Long) {
        accountMutex.withLock {
            checkVersion(expected)
            val userId = data.text("userid", "kugouid")
            val token = data.text("token")
            check(userId.isNotBlank() && userId != "0" && token.isNotBlank()) { "酷狗登录响应缺少有效账号信息" }
            save(session.copy(userId = userId, token = token, t1 = data.text("t1"), nickname = data.text("nickname", "nick_name"), vipType = data.text("vip_type"), vipToken = data.text("vip_token"), renewedAt = api.serverNow))
            mutableState.update { it.copy(claimStatus = KugouClaimStatus.IDLE, claimMessage = "", vipDescription = "") }
        }
        onForeground()
        scope.launch { optional { refreshAccount() } }
    }
    private suspend fun renewSessionIfNeeded(force: Boolean = false, expectedVersion: Long? = null) = renewalMutex.withLock {
        ensureDevice()
        if (expectedVersion != null && version.value != expectedVersion) return@withLock
        val current = session
        if (!current.loggedIn || mutableState.value.status == KugouAccountStatus.EXPIRED) return@withLock
        val now = api.serverNow
        if (!force && current.renewedAt > 0 && now - current.renewedAt in 0 until TimeUnit.HOURS.toMillis(20)) return@withLock
        val attemptKey = "renew_attempt_${current.userId}"
        if (!force && now - preferences.getLong(attemptKey, 0) in 0 until TimeUnit.MINUTES.toMillis(30)) return@withLock
        val expected = version.value
        preferences.edit().putLong(attemptKey, now).apply()
        try {
            val key = KugouCrypto.randomKey(16)
            val response = api.request("/v5/login_by_token", host = "login.user.kugou.com",
                body = KugouLoginProtocol.renewBody(current, now, Build.DEVICE, key), sessionOverride = current)
            checkVersion(expected)
            val data = KugouLoginProtocol.unpack(response.obj("data"), key)
            val userId = data.text("userid", "kugouid").ifBlank { current.userId }
            val token = data.text("token")
            check(userId == current.userId && token.isNotBlank()) { "酷狗续期响应未确认原账号，请重新登录" }
            accountMutex.withLock {
                checkVersion(expected)
                save(current.copy(token = token, t1 = data.text("t1").ifBlank { current.t1 },
                    vipType = data.text("vip_type").ifBlank { current.vipType }, vipToken = data.text("vip_token").ifBlank { current.vipToken }, renewedAt = api.serverNow))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            accountMutex.withLock {
                checkVersion(expected)
                if (e is KugouApiException && e.authenticationExpired) markExpired()
                else mutableState.update { it.copy(error = "账号安全续期暂未完成，可稍后重试或重新登录") }
            }
            throw e
        }
    }
    private fun markExpired() {
        mutableState.update { it.copy(status = KugouAccountStatus.EXPIRED, claimStatus = KugouClaimStatus.NEED_LOGIN, error = "酷狗登录已失效，请重新登录") }
        KugouVipClaimWorker.cancel(context)
    }
    suspend fun refreshAccount() {
        requireAccount()
        ensureDevice()
        val expected = version.value
        try {
            val now = api.serverNow / 1000
            val body = buildJsonObject {
                put("visit_time", now); put("usertype", 1); put("userid", session.userId)
                put("p", KugouCrypto.rsa(buildJsonObject { put("token", session.token); put("clienttime", now) }.toString(), raw = true).uppercase())
            }
            val profile = api.request("/v3/get_my_info", query = mapOf("plat" to "1"), body = body, router = "usercenter.kugou.com").obj("data")
            checkVersion(expected)
            val name = profile.text("nickname", "nick_name", "username").ifBlank { session.nickname }
            accountMutex.withLock {
                checkVersion(expected)
                store.write(session.copy(nickname = name))
                session = session.copy(nickname = name)
                mutableState.update { it.copy(status = KugouAccountStatus.SIGNED_IN, nickname = name, error = null) }
            }
            try {
                val vip = api.request("/v1/get_union_vip", host = "kugouvip.kugou.com", query = mapOf("busi_type" to "concept")).obj("data")
                checkVersion(expected)
                val entries = vip.rows("busi_vip", "vip_list")
                val active = entries.filter { it.number("is_vip") == 1L }
                val text = if (active.isEmpty()) "暂无已确认的 VIP 权益" else active.joinToString("；") {
                    val kind = it.text("product_name", "vip_name", "product_type").ifBlank { "VIP" }
                    val end = it.text("end_time", "expire_time", "vip_end_time")
                    kind + end.takeIf(String::isNotBlank)?.let { "（到期：$it）" }.orEmpty()
                }
                accountMutex.withLock { checkVersion(expected); mutableState.update { it.copy(vipDescription = text) } }
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                accountMutex.withLock { checkVersion(expected); mutableState.update { it.copy(vipDescription = "VIP 状态暂时无法确认") } }
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            accountMutex.withLock {
                checkVersion(expected)
                if (e is KugouApiException && e.authenticationExpired) markExpired()
                else mutableState.update { it.copy(error = "账号状态暂时无法验证，请检查网络或稍后重试") }
            }
            throw e
        }
    }
    suspend fun logout() {
        awaitRestored()
        accountMutex.withLock {
            save(session.copy(userId = "0", token = "", t1 = "", nickname = "", vipType = "0", vipToken = "", renewedAt = 0))
            mutableState.value = KugouAccountState(autoClaimVip = preferences.getBoolean("auto_claim", true))
            qrVersions.clear()
            KugouVipClaimWorker.cancel(context)
        }
    }
    suspend fun setAutoClaimVip(enabled: Boolean) {
        requireInitialized()
        withContext(Dispatchers.IO) { check(preferences.edit().putBoolean("auto_claim", enabled).commit()) { "自动领取设置保存失败" } }
        mutableState.update { it.copy(autoClaimVip = enabled) }
        if (enabled) onForeground() else KugouVipClaimWorker.cancel(context)
    }
    fun onForeground() {
        if (!::store.isInitialized || !session.loggedIn || mutableState.value.status == KugouAccountStatus.EXPIRED || !mutableState.value.autoClaimVip) return
        KugouVipClaimWorker.schedule(context)
        scope.launch { optional { claimDailyVip() } }
    }
    suspend fun claimDailyVip(manual: Boolean = false): KugouClaimStatus = claimMutex.withLock {
        awaitRestored()
        if (!session.loggedIn || mutableState.value.status == KugouAccountStatus.EXPIRED) {
            mutableState.update { it.copy(claimStatus = KugouClaimStatus.NEED_LOGIN, claimMessage = "请先登录酷狗账号") }
            return@withLock KugouClaimStatus.NEED_LOGIN
        }
        if (!manual && !mutableState.value.autoClaimVip) return@withLock KugouClaimStatus.IDLE
        val checkingUser = session.userId
        try { requireAccount(); ensureDevice() }
        catch (e: CancellationException) { throw e } catch (_: Exception) {
            return@withLock accountMutex.withLock {
                if (session.userId != checkingUser) return@withLock KugouClaimStatus.IDLE
                val status = if (mutableState.value.status == KugouAccountStatus.EXPIRED) KugouClaimStatus.NEED_LOGIN else KugouClaimStatus.ERROR
                mutableState.update { it.copy(claimStatus = status, claimMessage = if (status == KugouClaimStatus.NEED_LOGIN) "请重新登录酷狗账号" else "账号或网络状态尚未确认，请稍后重试", lastClaimCheck = api.serverNow) }
                status
            }
        }
        val (expected, current) = accountMutex.withLock { version.value to session }
        val day = claimDay()
        val key = "claim_${current.userId}_$day"
        if (preferences.getBoolean(key, false)) {
            accountMutex.withLock { checkVersion(expected); mutableState.update { it.copy(claimStatus = KugouClaimStatus.ALREADY_CLAIMED, claimMessage = "今日已领取 VIP", lastClaimCheck = api.serverNow) } }
            return@withLock KugouClaimStatus.ALREADY_CLAIMED
        }
        val last = preferences.getLong("attempt_${current.userId}", 0)
        if (!manual && api.serverNow - last in 0 until TimeUnit.MINUTES.toMillis(15)) return@withLock mutableState.value.claimStatus
        accountMutex.withLock {
            checkVersion(expected)
            mutableState.update { it.copy(claimStatus = KugouClaimStatus.CHECKING, claimMessage = "正在检查今日领取状态", lastClaimCheck = api.serverNow) }
        }
        preferences.edit().putLong("attempt_${current.userId}", api.serverNow).apply()
        try {
            val record = api.request("/youth/v1/activity/get_month_vip_record", query = mapOf("latest_limit" to "100"), sessionOverride = current)
            checkVersion(expected)
            check(api.hasConfirmedServerTime) { "未能确认官方当前日期，请检查设备时钟后重试" }
            check(day == claimDay()) { "领取日期已变化，请重新检查" }
            val already = KugouParser.containsClaimDate(record, day)
            val pending = preferences.getBoolean("pending_$key", false)
            if (pending && !already) {
                accountMutex.withLock { checkVersion(expected); mutableState.update { it.copy(claimStatus = KugouClaimStatus.ERROR, claimMessage = "上次领取结果待服务端确认，暂不重复提交") } }
                return@withLock KugouClaimStatus.ERROR
            }
            if (!already) {
                accountMutex.withLock {
                    checkVersion(expected)
                    check(manual || mutableState.value.autoClaimVip) { "自动领取已关闭" }
                    check(day == claimDay()) { "领取日期已变化，请重新检查" }
                    check(preferences.edit().putBoolean("pending_$key", true).commit()) { "领取状态保存失败，未提交领取" }
                }
                // 有写入副作用的领取请求不做自动重放；超时后只复核服务端记录。
                val response = api.request("/youth/v1/recharge/receive_vip_listen_song", query = mapOf("source_id" to "90139", "receive_day" to day), post = true, sessionOverride = current)
                checkVersion(expected)
                check(response.number("status") == 1L) { "领取结果未确认，请稍后检查" }
            }
            val status = if (already) KugouClaimStatus.ALREADY_CLAIMED else KugouClaimStatus.CLAIMED
            accountMutex.withLock {
                checkVersion(expected)
                check(preferences.edit().putBoolean(key, true).remove("pending_$key").commit()) { "领取状态保存失败，请稍后复核" }
                mutableState.update { it.copy(claimStatus = status, claimMessage = if (already) "服务端已确认今日领取" else "今日 VIP 领取成功", lastClaimCheck = api.serverNow) }
            }
            optional { refreshAccount() }
            status
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            accountMutex.withLock {
                if (version.value != expected) return@withLock KugouClaimStatus.IDLE
                val status = when {
                    e is KugouApiException && e.code == 131001L -> {
                        check(preferences.edit().putBoolean(key, true).remove("pending_$key").commit()) { "领取状态保存失败" }
                        KugouClaimStatus.ALREADY_CLAIMED
                    }
                    e is KugouApiException && e.code == 20028L -> KugouClaimStatus.NEED_OFFICIAL_APP
                    e is KugouApiException && e.authenticationExpired -> { markExpired(); KugouClaimStatus.NEED_LOGIN }
                    e is KugouApiException && e.ineligible -> KugouClaimStatus.INELIGIBLE
                    else -> KugouClaimStatus.ERROR
                }
                if (e is KugouApiException && status != KugouClaimStatus.ALREADY_CLAIMED) preferences.edit().remove("pending_$key").commit()
                mutableState.update { it.copy(claimStatus = status, claimMessage = when (status) {
                    KugouClaimStatus.ALREADY_CLAIMED -> "今日已领取 VIP"
                    KugouClaimStatus.NEED_OFFICIAL_APP -> "账号需要验证，请前往官方酷狗 APP 处理"
                    KugouClaimStatus.NEED_LOGIN -> "登录已失效，请重新登录"
                    KugouClaimStatus.INELIGIBLE -> "当前账号暂不符合领取条件，请在官方客户端查看资格"
                    else -> "领取未完成，请检查网络或稍后复核"
                }, lastClaimCheck = api.serverNow) }
                status
            }
        }
    }
    private fun claimDay(): String = Instant.ofEpochMilli(api.serverNow).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().toString()

    suspend fun loadHome(): KugouHomeData = coroutineScope {
        ensureDevice()
        val errors = java.util.concurrent.ConcurrentHashMap<String, String>()
        suspend fun <T> part(name: String, fallback: T, action: suspend () -> T): T = try { action() }
        catch (e: CancellationException) { throw e } catch (e: Exception) { errors[name] = e.message ?: "加载失败"; fallback }
        val radio = async { if (!session.loggedIn) emptyList() else part("radio", emptyList()) { radioSongs() } }
        val daily = async { if (!session.loggedIn) emptyList() else part("daily", emptyList()) {
            val data = api.request("/everyday_song_recommend", query = mapOf("platform" to "ios"), post = true, router = "everydayrec.service.kugou.com").obj("data")
            KugouParser.songs(data.rows("song_list", "songs", "songlist", "list")).take(24)
        } }
        val playlists = async { part("playlists", emptyList()) { getPlaylists().collections.take(6) } }
        val ranks = async { part("ranks", emptyList()) { getRanks().take(3).map { it.copy(songs = getCollectionSongs(KugouCollectionType.RANK, it.id).songs.take(3)) } } }
        val news = async { part("new", emptyList()) {
            val response = api.request("/musicadservice/container/v1/newsong_publish", body = buildJsonObject { put("rank_id", 21608); put("userid", session.userId); put("page", 1); put("pagesize", 6); put("tags", JsonArray(emptyList())) })
            val rows = (response["data"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?: response.obj("data").rows("songs", "songlist", "list", "info")
            KugouParser.songs(rows).take(6)
        } }
        KugouHomeData(radio.await(), daily.await(), playlists.await(), ranks.await(), news.await(), errors.toMap())
    }
    private suspend fun radioSongs(): List<MediaMetadata> {
        requireAccount()
        val time = api.serverNow
        val body = buildJsonObject {
            put("appid", 3116); put("clienttime", time); put("mid", session.mid); put("action", "play")
            put("recommend_source_locked", 0); put("song_pool_id", 0); put("callerid", 0); put("m_type", 1)
            put("platform", "ios"); put("area_code", 1); put("remain_songcnt", 0); put("clientver", 11440)
            put("is_overplay", 0); put("mode", "normal"); put("key", KugouCrypto.parameterKey(time.toString()))
            put("userid", session.userId); put("kguid", session.userId); put("token", session.token)
        }
        val data = api.request("/v2/personal_recommend", body = body, router = "persnfm.service.kugou.com").obj("data")
        return KugouParser.songs(data.rows("song_list", "songs", "songlist", "list"))
    }
    suspend fun searchSongs(query: String, page: Int = 1): KugouSongPage {
        requireAccount(); ensureDevice()
        val response = api.request("/v3/search/song", query = searchQuery(query, page), router = "complexsearch.kugou.com")
        return KugouParser.page(response, page, 15, "lists", "info")
    }
    suspend fun searchPlaylists(query: String, page: Int = 1): KugouCollectionPage {
        requireAccount(); ensureDevice()
        val data = api.request("/v1/search/special", query = searchQuery(query, page), router = "complexsearch.kugou.com").obj("data")
        val rows = data.rows("lists", "info")
        val total = data.number("total")
        return KugouCollectionPage(rows.mapNotNull(KugouParser::playlist), total?.let { page.toLong() * 15 < it } ?: (rows.size >= 15))
    }
    private fun searchQuery(query: String, page: Int): Map<String, String> {
        require(query.isNotBlank() && page > 0) { "请输入搜索关键词" }
        return mapOf("albumhide" to "0", "iscorrection" to "1", "keyword" to query.trim(), "nocollect" to "0", "page" to page.toString(), "pagesize" to "15", "platform" to "AndroidFilter")
    }
    suspend fun getPlaylists(page: Int = 1): KugouCollectionPage {
        ensureDevice()
        val time = (api.serverNow / 1000).toString()
        val body = buildJsonObject {
            put("appid", 3116); put("mid", session.mid); put("clientver", 11440); put("platform", "android")
            put("clienttime", time); put("userid", session.userId); put("module_id", 1); put("page", page); put("pagesize", 15)
            put("key", KugouCrypto.parameterKey(time)); put("special_recommend", buildJsonObject {
                put("withtag", 1); put("withsong", 0); put("sort", 1); put("ugc", 1); put("is_selected", 0)
                put("withrecommend", 1); put("area_code", 1); put("categoryid", 0)
            }); put("req_multi", 1); put("retrun_min", 5); put("return_special_falg", 1)
        }
        val data = api.request("/v2/special_recommend", body = body, router = "specialrec.service.kugou.com").obj("data")
        val rows = data.rows("special_list", "lists")
        return KugouCollectionPage(rows.mapNotNull(KugouParser::playlist), rows.size >= 15)
    }
    suspend fun getRanks(): List<KugouCollection> {
        ensureDevice()
        return api.request("/ocean/v6/rank/list", query = mapOf("plat" to "2", "withsong" to "0", "parentid" to "0")).obj("data").rows("info", "list").mapNotNull(KugouParser::rank)
    }
    suspend fun getCollectionSongs(type: KugouCollectionType, id: String, page: Int = 1): KugouSongPage {
        ensureDevice()
        require(id.isNotBlank() && page > 0)
        return if (type == KugouCollectionType.RANK) {
            val body = buildJsonObject {
                put("show_portrait_mv", 1); put("show_type_total", 1); put("filter_original_remarks", 1); put("area_code", 1)
                put("pagesize", 15); put("rank_cid", 0); put("type", 1); put("page", page); put("rank_id", id)
            }
            KugouParser.page(api.request("/openapi/kmr/v2/rank/audio", body = body, extraHeaders = mapOf("kg-tid" to "369")), page, 15, "songlist", "songs")
        } else {
            val params = mapOf("area_code" to "1", "begin_idx" to ((page - 1) * 15).toString(), "plat" to "1", "type" to "1", "mode" to "1", "personal_switch" to "1", "extend_fields" to "abtags,hot_cmt,popularization", "pagesize" to "15", "global_collection_id" to id)
            KugouParser.page(api.request("/pubsongs/v2/get_other_list_file_nofilt", query = params), page, 15, "songs", "list")
        }
    }
    suspend fun getAllCollectionSongs(type: KugouCollectionType, id: String): List<MediaMetadata> {
        ensureDevice()
        val result = linkedMapOf<String, MediaMetadata>()
        val expected = version.value
        var emptyPages = 0
        for (page in 1..200) {
            val response = getCollectionSongs(type, id, page)
            checkVersion(expected)
            val previous = result.size
            response.songs.forEach { result[it.id] = it }
            emptyPages = if (result.size == previous) emptyPages + 1 else 0
            if (!response.hasMore) return result.values.toList()
            check(emptyPages < 2) { "酷狗分页没有返回新歌曲，未将不完整歌单作为全部播放" }
        }
        throw IOException("歌单超过安全加载上限，请分批播放")
    }
    suspend fun resolveStream(song: MediaMetadata): KugouResolvedStream {
        requireAccount(); ensureDevice()
        require(song.source == KUGOU_CONCEPT_SOURCE) { "不是概念版歌曲" }
        val info = song.sourceData?.let { kugouJson.parseToJsonElement(it) as? JsonObject } ?: throw IOException("歌曲缺少概念版参数")
        val hash = info.text("hash").lowercase()
        require(hash.matches(Regex("[a-f0-9]{32}")) && song.id == KUGOU_CONCEPT_ID_PREFIX + hash) { "概念版歌曲参数不正确" }
        for (attempt in 0..1) {
            val (expected, current) = accountMutex.withLock { version.value to session }
            check(current.loggedIn && mutableState.value.status == KugouAccountStatus.SIGNED_IN) { "请前往设置登录酷狗账号" }
            val query = mapOf("album_id" to info.text("album_id").ifBlank { "0" }, "area_code" to "1", "hash" to hash,
                "ssa_flag" to "is_fromtrack", "version" to "11430", "page_id" to "967177915", "quality" to "128",
                "album_audio_id" to info.text("album_audio_id").ifBlank { "0" }, "behavior" to "play", "pid" to "411", "cmd" to "26",
                "pidversion" to "3001", "IsFreePart" to "1", "ppage_id" to "356753938,823673182,967485191", "cdnBackup" to "1", "module" to "", "clientver" to "11430",
                "key" to KugouCrypto.streamKey(hash, current.mid, current.userId))
            val response = api.request("/v5/url", query = query, router = "trackercdn.kugou.com", checkBusiness = false, sessionOverride = current)
            checkVersion(expected)
            try { api.ensureSuccess(response) } catch (e: KugouApiException) {
                if (e.authenticationExpired && attempt == 0) {
                    renewSessionIfNeeded(force = true, expectedVersion = expected)
                    continue
                }
                if (e.authenticationExpired) accountMutex.withLock { checkVersion(expected); markExpired() }
                throw e
            }
            fun urls(element: JsonElement?): List<String> = when (element) {
                is JsonPrimitive -> listOf(element.content)
                is JsonArray -> element.flatMap(::urls)
                else -> emptyList()
            }
            val candidates = (urls(response["url"]) + urls(response["backupUrl"]) + urls(response["backup_url"])).filter { it.startsWith("https://") }.distinct()
            if (candidates.isEmpty()) throw IOException(if (response.number("status") == 3L) "这首歌暂无可用播放权限" else "酷狗未返回可用播放地址，请检查账号或歌曲权限")
            val expiry = response.number("expire", "expires", "expire_time")
            val remoteExpiry = when {
                expiry == null -> api.serverNow + TimeUnit.MINUTES.toMillis(5)
                expiry > 1_000_000_000_000L -> expiry
                expiry > 1_000_000_000L -> expiry * 1000
                else -> api.serverNow + expiry * 1000
            }
            val expiresAt = System.currentTimeMillis() + (remoteExpiry - api.serverNow).coerceAtLeast(0)
            val preview = response.number("is_free_part", "is_trial")
            val access = when (preview) { 1L -> KugouPlaybackAccess.PREVIEW; 0L -> KugouPlaybackAccess.FULL; else -> KugouPlaybackAccess.UNKNOWN }
            return KugouResolvedStream(candidates, expiresAt, access, sessionVersion = expected)
        }
        throw IOException("酷狗账号续期后仍无法播放，请重新登录")
    }
    suspend fun resolveLyrics(song: MediaMetadata): String? {
        require(song.source == KUGOU_CONCEPT_SOURCE)
        val info = song.sourceData?.let { kugouJson.parseToJsonElement(it) as? JsonObject } ?: return null
        val response = api.request("/v1/search", host = "lyrics.kugou.com", includeAccount = false, defaults = false,
            query = mapOf("album_audio_id" to info.text("album_audio_id").ifBlank { "0" }, "appid" to "3116", "clientver" to "11440", "duration" to (song.duration.coerceAtLeast(0) * 1000L).toString(), "hash" to info.text("hash"), "keyword" to "${song.title} ${song.artists.joinToString { it.name }}", "lrctxt" to "1", "man" to "no"))
        val candidate = response.rows("candidates").firstOrNull() ?: response.obj("data").rows("candidates").firstOrNull() ?: return null
        val body = api.request("/download", host = "lyrics.kugou.com", includeAccount = false, defaults = false,
            query = mapOf("ver" to "1", "client" to "android", "id" to candidate.text("id"), "accesskey" to candidate.text("accesskey"), "fmt" to "lrc", "charset" to "utf8"))
        val content = body.text("content")
        return content.takeIf(String::isNotBlank)?.let { KugouCrypto.decodeBase64(it).toString(Charsets.UTF_8) }
    }
}
