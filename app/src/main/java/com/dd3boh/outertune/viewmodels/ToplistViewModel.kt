package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.source.lx.LxDiscoverClient
import com.dd3boh.outertune.source.lx.LxSearchSource
import com.dd3boh.outertune.source.lx.LxTopListInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ToplistViewModel @Inject constructor() : ViewModel() {
    val sources = LxDiscoverClient.supportedSources
    private val mutableState = MutableStateFlow(ToplistUiState())
    val state = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var generation = 0L

    init {
        load()
    }

    fun selectSource(source: LxSearchSource) {
        if (source !in sources || source == mutableState.value.source) return
        generation++
        loadJob?.cancel()
        mutableState.value = ToplistUiState(source = source)
        load()
    }

    fun refresh() {
        generation++
        loadJob?.cancel()
        load()
    }

    private fun load() {
        val currentSource = mutableState.value.source
        val request = generation
        mutableState.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                LxDiscoverClient.topLists(currentSource)
                    .onSuccess { list ->
                        if (request == generation) {
                            mutableState.update { it.copy(topLists = list, loading = false, error = null) }
                        }
                    }
                    .onFailure { error ->
                        if (request == generation) {
                            mutableState.update {
                                it.copy(loading = false, error = error.message ?: "排行榜加载失败，请检查网络后重试")
                            }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}

data class ToplistUiState(
    val source: LxSearchSource = LxDiscoverClient.supportedSources.first(),
    val topLists: List<LxTopListInfo> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)
