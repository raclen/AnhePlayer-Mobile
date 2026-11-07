package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.kugou.KugouCollectionType
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
class KugouCollectionViewModel @Inject constructor(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val type = when (savedStateHandle.get<String>("type")) {
        "playlist" -> KugouCollectionType.PLAYLIST
        "rank" -> KugouCollectionType.RANK
        else -> null
    }
    private val id = savedStateHandle.get<String>("id").orEmpty()
    private val mutableState = MutableStateFlow(KugouCollectionUiState())
    val state = mutableState.asStateFlow()
    private var pageJob: Job? = null
    private var allJob: Job? = null
    private var generation = 0L
    private var allGeneration = 0L

    init {
        viewModelScope.launch { KugouConcept.sessionVersion.collect { refresh() } }
    }

    fun refresh() {
        pageJob?.cancel()
        cancelAll()
        generation++
        mutableState.value = KugouCollectionUiState()
        loadMore()
    }

    fun loadMore() {
        if (type == null || id.isBlank()) {
            mutableState.update { it.copy(error = "合集地址无效，请返回后重新打开", hasMore = false) }
            return
        }
        val snapshot = mutableState.value
        if (snapshot.loading || !snapshot.hasMore) return
        val request = generation
        val session = KugouConcept.sessionVersion.value
        mutableState.update { it.copy(loading = true, error = null) }
        pageJob = viewModelScope.launch {
            try {
                val result = KugouConcept.getCollectionSongs(type, id, snapshot.page + 1)
                if (request == generation && session == KugouConcept.sessionVersion.value) mutableState.update {
                    it.copy(songs = (it.songs + result.songs).distinctBy { song -> song.id }, page = snapshot.page + 1,
                        title = result.title.ifBlank { it.title }, description = result.description.ifBlank { it.description },
                        coverUrl = result.coverUrl ?: it.coverUrl, hasMore = result.hasMore, loading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == generation && session == KugouConcept.sessionVersion.value) mutableState.update {
                    it.copy(loading = false, error = error.message ?: "合集加载失败，请重试")
                }
            }
        }
    }

    fun playAll(onReady: (List<MediaMetadata>) -> Unit) {
        if (type == null || id.isBlank() || mutableState.value.loadingAll) return
        val request = ++allGeneration
        val session = KugouConcept.sessionVersion.value
        mutableState.update { it.copy(loadingAll = true, allError = null) }
        allJob = viewModelScope.launch {
            try {
                // ListQueue 不消费分页；完整读取成功前不能建立名为“全部”的首屏队列。
                val songs = KugouConcept.getAllCollectionSongs(type, id).distinctBy { it.id }
                if (songs.isEmpty()) throw IllegalStateException("合集暂无可播放歌曲")
                if (request == allGeneration && session == KugouConcept.sessionVersion.value) {
                    mutableState.update { it.copy(loadingAll = false) }
                    onReady(songs)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (request == allGeneration && session == KugouConcept.sessionVersion.value) mutableState.update {
                    it.copy(loadingAll = false, allError = error.message ?: "完整合集加载失败，请重试")
                }
            }
        }
    }

    fun cancelAll() {
        allGeneration++
        allJob?.cancel()
        allJob = null
        mutableState.update { it.copy(loadingAll = false) }
    }

    fun cancelLoading() {
        pageJob?.cancel()
        cancelAll()
        generation++
        mutableState.update { it.copy(loading = false) }
    }
}

data class KugouCollectionUiState(
    val title: String = "",
    val description: String = "",
    val coverUrl: String? = null,
    val songs: List<MediaMetadata> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
    val loadingAll: Boolean = false,
    val allError: String? = null,
)
