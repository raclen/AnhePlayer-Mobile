package com.dd3boh.outertune.viewmodels

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.source.netease.NeteaseAccountProfile
import com.dd3boh.outertune.source.netease.NeteaseAccountStore
import com.dd3boh.outertune.source.netease.NeteaseApi
import com.dd3boh.outertune.source.netease.NeteaseRadarResult
import com.dd3boh.outertune.source.netease.NeteaseSongItem
import com.dd3boh.outertune.source.netease.QrLoginStatus
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class NeteaseTab(val title: String) {
    RADAR("私人雷达"),
    DAILY("每日推荐"),
    FM("私人漫游")
}

data class QrUiState(
    val isLoading: Boolean = false,
    val unikey: String? = null,
    val qrBitmap: Bitmap? = null,
    val statusText: String = "正在生成登录二维码…",
    val isExpired: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

data class NeteaseRecommendUiState(
    val currentTab: NeteaseTab = NeteaseTab.RADAR,
    val isLoading: Boolean = false,
    val error: String? = null,
    val radarResult: NeteaseRadarResult = NeteaseRadarResult(),
    val dailySongs: List<NeteaseSongItem> = emptyList(),
    val fmSongs: List<NeteaseSongItem> = emptyList(),
    val isLoginDialogOpen: Boolean = false,
    val qrState: QrUiState = QrUiState()
)

@HiltViewModel
class NeteaseRecommendViewModel @Inject constructor(
    application: Application
) : AndroidViewModel(application) {
    private val accountStore = NeteaseAccountStore.getInstance(application)
    val profile: StateFlow<NeteaseAccountProfile?> = accountStore.profile
    val cookie: StateFlow<String> = accountStore.cookie

    private val _uiState = MutableStateFlow(NeteaseRecommendUiState())
    val uiState: StateFlow<NeteaseRecommendUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var qrPollingJob: Job? = null

    init {
        viewModelScope.launch {
            accountStore.cookie.collect { currentCookie ->
                if (currentCookie.isNotBlank()) {
                    loadDataForTab(_uiState.value.currentTab)
                } else {
                    _uiState.update {
                        it.copy(
                            radarResult = NeteaseRadarResult(),
                            dailySongs = emptyList(),
                            fmSongs = emptyList(),
                            error = null
                        )
                    }
                }
            }
        }
    }

    fun switchTab(tab: NeteaseTab) {
        if (_uiState.value.currentTab == tab) return
        _uiState.update { it.copy(currentTab = tab, error = null) }
        loadDataForTab(tab)
    }

    fun refreshCurrentTab() {
        loadDataForTab(_uiState.value.currentTab, force = true)
    }

    private fun loadDataForTab(tab: NeteaseTab, force: Boolean = false) {
        val currentCookie = accountStore.cookie.value
        if (currentCookie.isBlank()) return

        // 已经有数据且非强制刷新时，不重复发起网络请求
        when (tab) {
            NeteaseTab.RADAR -> if (!force && _uiState.value.radarResult.songs.isNotEmpty()) return
            NeteaseTab.DAILY -> if (!force && _uiState.value.dailySongs.isNotEmpty()) return
            NeteaseTab.FM -> if (!force && _uiState.value.fmSongs.isNotEmpty()) return
        }

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                when (tab) {
                    NeteaseTab.RADAR -> {
                        val res = NeteaseApi.getPrivateRadar(currentCookie)
                        if (res.isSuccess) {
                            val radar = res.getOrThrow()
                            _uiState.update { it.copy(radarResult = radar, isLoading = false) }
                        } else {
                            _uiState.update {
                                it.copy(
                                    error = res.exceptionOrNull()?.message ?: "获取私人雷达失败",
                                    isLoading = false
                                )
                            }
                        }
                    }
                    NeteaseTab.DAILY -> {
                        val res = NeteaseApi.getDailyRecommendSongs(currentCookie)
                        if (res.isSuccess) {
                            val songs = res.getOrThrow()
                            _uiState.update { it.copy(dailySongs = songs, isLoading = false) }
                        } else {
                            _uiState.update {
                                it.copy(
                                    error = res.exceptionOrNull()?.message ?: "获取每日推荐失败",
                                    isLoading = false
                                )
                            }
                        }
                    }
                    NeteaseTab.FM -> {
                        val res = NeteaseApi.getPrivateFmSongs(currentCookie)
                        if (res.isSuccess) {
                            val songs = res.getOrThrow()
                            _uiState.update { it.copy(fmSongs = songs, isLoading = false) }
                        } else {
                            _uiState.update {
                                it.copy(
                                    error = res.exceptionOrNull()?.message ?: "获取私人漫游失败",
                                    isLoading = false
                                )
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "网络请求失败", isLoading = false) }
            }
        }
    }

    // ── 登录管理 ──

    fun openLoginDialog() {
        _uiState.update { it.copy(isLoginDialogOpen = true) }
        startQrLogin()
    }

    fun closeLoginDialog() {
        qrPollingJob?.cancel()
        _uiState.update { it.copy(isLoginDialogOpen = false) }
    }

    fun startQrLogin() {
        qrPollingJob?.cancel()
        qrPollingJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    qrState = QrUiState(
                        isLoading = true,
                        statusText = "正在生成登录二维码…"
                    )
                )
            }
            val keyRes = NeteaseApi.createQrKey()
            if (keyRes.isFailure) {
                _uiState.update {
                    it.copy(
                        qrState = it.qrState.copy(
                            isLoading = false,
                            error = keyRes.exceptionOrNull()?.message ?: "生成二维码失败"
                        )
                    )
                }
                return@launch
            }

            val unikey = keyRes.getOrThrow()
            val qrUrl = "https://music.163.com/login?codekey=$unikey"
            val bitmap = withContext(Dispatchers.Default) {
                generateQrBitmap(qrUrl)
            }

            _uiState.update {
                it.copy(
                    qrState = QrUiState(
                        isLoading = false,
                        unikey = unikey,
                        qrBitmap = bitmap,
                        statusText = "请使用网易云音乐 APP 扫一扫"
                    )
                )
            }

            // 开启轮询 (最长等待 3 分钟)
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 180_000) {
                delay(2000)
                val status = NeteaseApi.checkQrLogin(unikey)
                when (status) {
                    is QrLoginStatus.WaitingScan -> {
                        _uiState.update {
                            it.copy(qrState = it.qrState.copy(statusText = "请使用网易云音乐 APP 扫码"))
                        }
                    }
                    is QrLoginStatus.WaitingConfirm -> {
                        _uiState.update {
                            it.copy(qrState = it.qrState.copy(statusText = "已扫码，请在手机上点击确认"))
                        }
                    }
                    is QrLoginStatus.Success -> {
                        _uiState.update {
                            it.copy(
                                qrState = it.qrState.copy(
                                    isSuccess = true,
                                    statusText = "授权成功，正在同步账号…"
                                )
                            )
                        }
                        val saveRes = accountStore.saveLogin(status.cookie)
                        if (saveRes.isSuccess) {
                            delay(600)
                            closeLoginDialog()
                            refreshCurrentTab()
                        } else {
                            _uiState.update {
                                it.copy(
                                    qrState = it.qrState.copy(
                                        error = "获取账号资料失败: ${saveRes.exceptionOrNull()?.message}"
                                    )
                                )
                            }
                        }
                        return@launch
                    }
                    is QrLoginStatus.Expired -> {
                        _uiState.update {
                            it.copy(
                                qrState = it.qrState.copy(
                                    isExpired = true,
                                    statusText = "二维码已过期，请点击刷新"
                                )
                            )
                        }
                        return@launch
                    }
                    is QrLoginStatus.Error -> {
                        // 网络轻微抖动重试
                    }
                }
            }
            _uiState.update {
                it.copy(
                    qrState = it.qrState.copy(
                        isExpired = true,
                        statusText = "扫码超时，请点击刷新"
                    )
                )
            }
        }
    }

    fun loginWithCookie(rawCookie: String, onComplete: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            val res = accountStore.saveLogin(rawCookie)
            if (res.isSuccess) {
                closeLoginDialog()
                refreshCurrentTab()
                onComplete(true, null)
            } else {
                onComplete(false, res.exceptionOrNull()?.message ?: "Cookie 无效或已过期")
            }
        }
    }

    fun logout() {
        accountStore.logout()
        _uiState.update {
            it.copy(
                radarResult = NeteaseRadarResult(),
                dailySongs = emptyList(),
                fmSongs = emptyList(),
                error = null
            )
        }
    }

    private fun generateQrBitmap(content: String, size: Int = 512): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1
        )
        val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (bitMatrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    override fun onCleared() {
        super.onCleared()
        qrPollingJob?.cancel()
        loadJob?.cancel()
    }
}
