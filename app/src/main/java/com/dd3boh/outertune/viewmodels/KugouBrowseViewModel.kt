package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.source.kugou.KugouCollection
import com.dd3boh.outertune.source.kugou.KugouCollectionPage
import com.dd3boh.outertune.source.kugou.KugouConcept
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class KugouBrowseViewModel @Inject constructor(savedStateHandle: SavedStateHandle) : ViewModel() {
    val kind = savedStateHandle.get<String>("kind").orEmpty()
    private val mutableState = MutableStateFlow(KugouBrowseUiState())
    val state = mutableState.asStateFlow()
    private var job: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch { KugouConcept.sessionVersion.collect { refresh() } }
    }

    fun refresh() {
        cancelLoading()
        mutableState.value = KugouBrowseUiState()
        loadMore()
    }

    fun cancelLoading() {
        generation++
        job?.cancel()
        mutableState.update { it.copy(loading = false) }
    }

    fun loadMore() {
        val snapshot = mutableState.value
        if (snapshot.loading || !snapshot.hasMore) return
        if (kind != "playlists" && kind != "ranks") {
            mutableState.update { it.copy(error = "浏览地址无效，请返回后重新打开", hasMore = false) }
            return
        }
        val request = generation
        val session = KugouConcept.sessionVersion.value
        mutableState.update { it.copy(loading = true, error = null) }
        job = viewModelScope.launch {
            try {
                val result = if (kind == "ranks") KugouCollectionPage(KugouConcept.getRanks(), false)
                    else KugouConcept.getPlaylists(snapshot.page + 1)
                if (request == generation && session == KugouConcept.sessionVersion.value) mutableState.update {
                    it.copy(collections = (it.collections + result.collections).distinctBy { collection -> collection.id },
                        page = snapshot.page + 1, hasMore = result.hasMore, loading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == generation && session == KugouConcept.sessionVersion.value) mutableState.update {
                    it.copy(loading = false, error = error.message ?: "列表加载失败，请重试")
                }
            }
        }
    }
}

data class KugouBrowseUiState(
    val collections: List<KugouCollection> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
)
