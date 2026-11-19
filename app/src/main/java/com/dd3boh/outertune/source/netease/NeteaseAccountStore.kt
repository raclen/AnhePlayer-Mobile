package com.dd3boh.outertune.source.netease

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 网易云音乐账号持久化存储与状态管理
 */
class NeteaseAccountStore private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("netease_account_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _cookie = MutableStateFlow(prefs.getString(KEY_COOKIE, "").orEmpty())
    val cookie: StateFlow<String> = _cookie.asStateFlow()

    private val _profile = MutableStateFlow(loadProfileFromPrefs())
    val profile: StateFlow<NeteaseAccountProfile?> = _profile.asStateFlow()

    init {
        // 初始若有 Cookie，在后台静默验证一次最新资料
        if (_cookie.value.isNotBlank()) {
            scope.launch {
                refreshProfile()
            }
        }
    }

    private fun loadProfileFromPrefs(): NeteaseAccountProfile? {
        val uid = prefs.getString(KEY_UID, "") ?: ""
        if (uid.isBlank()) return null
        return NeteaseAccountProfile(
            userId = uid,
            nickname = prefs.getString(KEY_NICKNAME, "") ?: "",
            avatarUrl = prefs.getString(KEY_AVATAR, "") ?: "",
            vipType = prefs.getInt(KEY_VIP, 0),
            signature = prefs.getString(KEY_SIGNATURE, "") ?: ""
        )
    }

    fun isLoggedIn(): Boolean {
        return _cookie.value.isNotBlank() && _profile.value != null
    }

    suspend fun saveLogin(cookieStr: String): Result<NeteaseAccountProfile> {
        val cleanCookie = cookieStr.trim()
        val profileResult = NeteaseApi.getProfile(cleanCookie)
        if (profileResult.isSuccess) {
            val p = profileResult.getOrThrow()
            prefs.edit()
                .putString(KEY_COOKIE, cleanCookie)
                .putString(KEY_UID, p.userId)
                .putString(KEY_NICKNAME, p.nickname)
                .putString(KEY_AVATAR, p.avatarUrl)
                .putInt(KEY_VIP, p.vipType)
                .putString(KEY_SIGNATURE, p.signature)
                .apply()

            _cookie.value = cleanCookie
            _profile.value = p
            return Result.success(p)
        } else {
            return Result.failure(profileResult.exceptionOrNull() ?: Exception("获取用户信息失败"))
        }
    }

    suspend fun refreshProfile(): Result<NeteaseAccountProfile> {
        val currentCookie = _cookie.value
        if (currentCookie.isBlank()) return Result.failure(Exception("未登录"))
        val profileResult = NeteaseApi.getProfile(currentCookie)
        if (profileResult.isSuccess) {
            val p = profileResult.getOrThrow()
            prefs.edit()
                .putString(KEY_UID, p.userId)
                .putString(KEY_NICKNAME, p.nickname)
                .putString(KEY_AVATAR, p.avatarUrl)
                .putInt(KEY_VIP, p.vipType)
                .putString(KEY_SIGNATURE, p.signature)
                .apply()
            _profile.value = p
            return Result.success(p)
        } else {
            // 令牌失效时清除本地资料
            if (profileResult.exceptionOrNull()?.message?.contains("未能识别") == true) {
                logout()
            }
            return Result.failure(profileResult.exceptionOrNull() ?: Exception("刷新失败"))
        }
    }

    fun logout() {
        prefs.edit().clear().apply()
        _cookie.value = ""
        _profile.value = null
    }

    companion object {
        private const val KEY_COOKIE = "cookie"
        private const val KEY_UID = "uid"
        private const val KEY_NICKNAME = "nickname"
        private const val KEY_AVATAR = "avatar"
        private const val KEY_VIP = "vip"
        private const val KEY_SIGNATURE = "signature"

        @Volatile
        private var instance: NeteaseAccountStore? = null

        fun getInstance(context: Context): NeteaseAccountStore {
            return instance ?: synchronized(this) {
                instance ?: NeteaseAccountStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
