package com.dd3boh.outertune.viewmodels

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.source.kugou.KugouAccountSelectionException
import com.dd3boh.outertune.source.kugou.KugouConcept
import com.dd3boh.outertune.source.kugou.KugouLoginAccount
import com.dd3boh.outertune.source.kugou.KugouQrLogin
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltViewModel
class KugouAccountViewModel @Inject constructor() : ViewModel() {
    val accountState = KugouConcept.accountState
    private val mutableState = MutableStateFlow(KugouLoginUiState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var cooldownJob: Job? = null
    private var generation = 0L

    fun refresh() = perform { KugouConcept.refreshAccount() }
    fun setAutoClaim(enabled: Boolean) = perform { KugouConcept.setAutoClaimVip(enabled) }
    fun claimVip() = perform { KugouConcept.claimDailyVip(manual = true) }
    fun logout() {
        cancelLogin()
        perform { KugouConcept.logout() }
    }

    fun createQr() {
        cancelLogin()
        val request = generation
        mutableState.update { it.copy(busy = true, message = "正在生成授权二维码…", error = null) }
        operation = viewModelScope.launch {
            try {
                val qr = withTimeoutOrNull(45_000) { KugouConcept.createQrLogin() }
                    ?: throw IllegalStateException("二维码生成超时，请重试")
                if (request != generation) return@launch
                mutableState.update { it.copy(qr = qr, busy = false, polling = true, message = "请使用酷狗官方客户端扫码授权") }
                val deadline = SystemClock.elapsedRealtime() + 180_000
                var failures = 0
                while (request == generation && SystemClock.elapsedRealtime() < deadline) {
                    delay(2_000)
                    try {
                        val status = withTimeoutOrNull(10_000) { KugouConcept.checkQrLogin(qr.key) }
                            ?: throw IllegalStateException("扫码状态查询超时")
                        if (request != generation) return@launch
                        failures = 0
                        when (status.status) {
                            0 -> {
                                mutableState.update { it.copy(qr = null, polling = false, message = "二维码已过期，请重新生成") }
                                return@launch
                            }
                            1 -> mutableState.update { it.copy(message = "等待扫码授权") }
                            2 -> mutableState.update { it.copy(message = "已扫码，请在官方客户端确认授权") }
                            4 -> {
                                mutableState.update { it.copy(qr = null, polling = false, message = "授权成功") }
                                return@launch
                            }
                            else -> throw IllegalStateException(status.message.ifBlank { "扫码状态异常，请重新生成二维码" })
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (request != generation) return@launch
                        failures++
                        if (failures >= 5) throw error
                        mutableState.update { it.copy(message = "状态查询失败，正在重试（$failures/5）") }
                    }
                }
                mutableState.update { it.copy(qr = null, polling = false, message = "扫码已超时，请重新生成") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == generation) mutableState.update {
                    it.copy(qr = null, busy = false, polling = false, error = error.message ?: "扫码失败，请重试")
                }
            }
        }
    }

    fun sendSms(mobile: String) {
        if (!mobile.matches(Regex("1[0-9]{10}"))) {
            mutableState.update { it.copy(error = "请输入有效的 11 位手机号") }
            return
        }
        if (mutableState.value.cooldown > 0 || mutableState.value.busy) return
        // 只有界面上的发送按钮调用此方法，不随输入或页面打开发送短信。
        perform(progressMessage = "正在请求短信验证码…") {
            KugouConcept.requestSms(mobile)
            currentCoroutineContext().ensureActive()
            mutableState.update { it.copy(message = "验证码已发送，请留意短信", cooldown = 60) }
            cooldownJob?.cancel()
            cooldownJob = viewModelScope.launch {
                val deadline = SystemClock.elapsedRealtime() + 60_000
                do {
                    val remaining = ((deadline - SystemClock.elapsedRealtime() + 999) / 1_000).toInt().coerceAtLeast(0)
                    mutableState.update { it.copy(cooldown = remaining) }
                    if (remaining == 0) break
                    delay(1_000)
                } while (true)
            }
        }
    }

    fun loginSms(mobile: String, code: String, userId: String? = null) {
        if (!mobile.matches(Regex("1[0-9]{10}")) || !code.matches(Regex("[0-9]{4,8}"))) {
            mutableState.update { it.copy(error = "请输入 11 位手机号和 4–8 位短信验证码") }
            return
        }
        perform {
            try {
                KugouConcept.loginWithSms(mobile, code, userId)
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(accounts = emptyList(), message = "登录成功") }
            } catch (selection: KugouAccountSelectionException) {
                currentCoroutineContext().ensureActive()
                mutableState.update { it.copy(accounts = selection.accounts, message = "请选择要登录的酷狗账号") }
            }
        }
    }

    fun dismissAccountSelection() = cancelLogin()

    fun cancelLogin() {
        generation++
        operation?.cancel()
        operation = null
        mutableState.update { it.copy(qr = null, polling = false, busy = false, accounts = emptyList(), message = "", error = null) }
    }

    private fun perform(progressMessage: String = "", block: suspend () -> Unit) {
        if (mutableState.value.busy) return
        operation?.cancel()
        val request = ++generation
        mutableState.update { it.copy(busy = true, qr = null, polling = false, message = progressMessage, error = null) }
        operation = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == generation) mutableState.update { it.copy(message = "", error = error.message ?: "操作失败，请重试") }
            } finally {
                if (request == generation) mutableState.update { it.copy(busy = false) }
            }
        }
    }
}

data class KugouLoginUiState(
    val qr: KugouQrLogin? = null,
    val polling: Boolean = false,
    val busy: Boolean = false,
    val cooldown: Int = 0,
    val accounts: List<KugouLoginAccount> = emptyList(),
    val message: String = "",
    val error: String? = null,
)
