package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.kugou.KugouConcept
import com.dd3boh.outertune.source.kugou.KugouHomeData
import com.dd3boh.outertune.source.kugou.KugouRadioMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class KugouHomeViewModel @Inject constructor() : ViewModel() {
    private val mutableState = MutableStateFlow(KugouHomeUiState())
    val state = mutableState.asStateFlow()
    val accountState = KugouConcept.accountState
    private var loadJob: Job? = null
    private var radioJob: Job? = null
    private var generation = 0L
    private var radioGeneration = 0L

    init {
        viewModelScope.launch {
            KugouConcept.sessionVersion.collect {
                mutableState.value = KugouHomeUiState()
                refresh()
            }
        }
    }

    fun refresh() {
        loadJob?.cancel()
        radioJob?.cancel()
        val request = ++generation
        val radioRequest = ++radioGeneration
        val session = KugouConcept.sessionVersion.value
        val mode = mutableState.value.radioMode
        mutableState.value = mutableState.value.copy(loading = true, error = null, radioLoading = false, pendingRadioMode = null, radioError = null, radioErrorMode = null)
        loadJob = viewModelScope.launch {
            try {
                val data = KugouConcept.loadHome(mode)
                if (request == generation && session == KugouConcept.sessionVersion.value) {
                    val current = mutableState.value
                    // 全页请求不能覆盖期间切换频道得到的新电台列表或错误。
                    val merged = if (radioRequest == radioGeneration) data else data.copy(
                        radioSongs = current.data.radioSongs,
                        errors = data.errors.filterKeys { it !in radioErrorKeys } + current.data.errors.filterKeys { it in radioErrorKeys },
                    )
                    mutableState.value = current.copy(data = merged, loading = false, error = null)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == generation && session == KugouConcept.sessionVersion.value) {
                    mutableState.value = mutableState.value.copy(loading = false, error = error.message ?: "内容加载失败，请重试")
                }
            }
        }
    }

    fun selectRadioMode(mode: KugouRadioMode, onLoaded: (List<MediaMetadata>, KugouRadioMode) -> Unit = { _, _ -> }) {
        if (mode == mutableState.value.radioMode && !mutableState.value.radioLoading) return
        loadRadio(mode, onLoaded)
    }

    fun refreshRadio(onLoaded: (List<MediaMetadata>, KugouRadioMode) -> Unit = { _, _ -> }) {
        val current = mutableState.value
        loadRadio(current.pendingRadioMode ?: current.radioMode, onLoaded)
    }

    private fun loadRadio(mode: KugouRadioMode, onLoaded: (List<MediaMetadata>, KugouRadioMode) -> Unit) {
        radioJob?.cancel()
        val request = ++radioGeneration
        val session = KugouConcept.sessionVersion.value
        mutableState.value = mutableState.value.copy(radioLoading = true, pendingRadioMode = mode, radioError = null)
        radioJob = viewModelScope.launch {
            try {
                val songs = KugouConcept.radioSongs(mode)
                if (songs.isEmpty()) throw IOException("该频道暂时没有推荐歌曲，请稍后重试")
                if (request == radioGeneration && session == KugouConcept.sessionVersion.value) {
                    val current = mutableState.value
                    mutableState.value = current.copy(
                        data = current.data.copy(radioSongs = songs, errors = current.data.errors - radioErrorKeys),
                        radioMode = mode, pendingRadioMode = null, radioLoading = false, radioError = null, radioErrorMode = null,
                    )
                    onLoaded(songs, mode)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == radioGeneration && session == KugouConcept.sessionVersion.value) {
                    // 失败时保留原频道和歌曲，不能把旧推荐伪装成新频道。
                    mutableState.value = mutableState.value.copy(
                        pendingRadioMode = null, radioLoading = false, radioErrorMode = mode,
                        radioError = "${mode.title}加载失败：${error.message ?: "请重试"}",
                    )
                }
            }
        }
    }

    private companion object {
        val radioErrorKeys = setOf("radio", "radioSongs")
    }
}

data class KugouHomeUiState(
    val data: KugouHomeData = KugouHomeData(),
    val loading: Boolean = false,
    val error: String? = null,
    val radioMode: KugouRadioMode = KugouRadioMode.HEART,
    val pendingRadioMode: KugouRadioMode? = null,
    val radioLoading: Boolean = false,
    val radioError: String? = null,
    val radioErrorMode: KugouRadioMode? = null,
)
