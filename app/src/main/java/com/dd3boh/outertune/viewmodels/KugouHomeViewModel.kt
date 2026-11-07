package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.source.kugou.KugouConcept
import com.dd3boh.outertune.source.kugou.KugouHomeData
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class KugouHomeViewModel @Inject constructor() : ViewModel() {
    private val mutableState = MutableStateFlow(KugouHomeUiState())
    val state = mutableState.asStateFlow()
    val accountState = KugouConcept.accountState
    private var loadJob: Job? = null
    private var generation = 0L

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
        val request = ++generation
        val session = KugouConcept.sessionVersion.value
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val data = KugouConcept.loadHome()
                if (request == generation && session == KugouConcept.sessionVersion.value) {
                    mutableState.value = KugouHomeUiState(data = data)
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
}

data class KugouHomeUiState(
    val data: KugouHomeData = KugouHomeData(),
    val loading: Boolean = false,
    val error: String? = null,
)
