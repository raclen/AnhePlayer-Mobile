package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.source.lx.LxDiscoverClient
import com.dd3boh.outertune.source.lx.LxPlaylistInfo
import com.dd3boh.outertune.source.lx.LxRecommendTag
import com.dd3boh.outertune.source.lx.LxRecommendTagsResult
import com.dd3boh.outertune.source.lx.LxSearchSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

val DEFAULT_RECOMMEND_TAG = LxRecommendTag(id = "", name = "全部")

@HiltViewModel
class RecommendSheetsViewModel @Inject constructor() : ViewModel() {
    val sources = LxDiscoverClient.supportedSources
    private val mutableState = MutableStateFlow(RecommendSheetsUiState())
    val state = mutableState.asStateFlow()
    private var tagsJob: Job? = null
    private var sheetsJob: Job? = null
    private var sourceGeneration = 0L
    private var listGeneration = 0L

    init {
        loadTags()
        loadPage(1, isRefresh = true)
    }

    fun selectSource(source: LxSearchSource) {
        if (source !in sources || source == mutableState.value.source) return
        sourceGeneration++
        listGeneration++
        tagsJob?.cancel()
        sheetsJob?.cancel()
        mutableState.value = RecommendSheetsUiState(source = source)
        loadTags()
        loadPage(1, isRefresh = true)
    }

    fun selectTag(tag: LxRecommendTag) {
        val current = mutableState.value
        if (tag == current.selectedTag) return
        listGeneration++
        sheetsJob?.cancel()
        mutableState.update {
            it.copy(
                selectedTag = tag,
                playlists = emptyList(),
                page = 0,
                hasMore = true,
                loading = false,
                error = null,
            )
        }
        loadPage(1, isRefresh = true)
    }

    fun refresh() {
        listGeneration++
        sheetsJob?.cancel()
        if (mutableState.value.tags == null) {
            loadTags()
        }
        loadPage(1, isRefresh = true)
    }

    fun loadMore() {
        val current = mutableState.value
        if (current.loading || !current.hasMore) return
        loadPage(current.page + 1, isRefresh = false)
    }

    private fun loadTags() {
        val currentSource = mutableState.value.source
        val request = sourceGeneration
        mutableState.update { it.copy(tagsLoading = true) }
        tagsJob = viewModelScope.launch {
            try {
                LxDiscoverClient.recommendTags(currentSource)
                    .onSuccess { result ->
                        if (request == sourceGeneration) {
                            mutableState.update { it.copy(tags = result, tagsLoading = false) }
                        }
                    }
                    .onFailure {
                        if (request == sourceGeneration) {
                            mutableState.update { it.copy(tagsLoading = false) }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    private fun loadPage(targetPage: Int, isRefresh: Boolean) {
        val snapshot = mutableState.value
        if (!isRefresh && (snapshot.loading || !snapshot.hasMore)) return
        val currentSource = snapshot.source
        val currentTag = snapshot.selectedTag
        val request = listGeneration
        mutableState.update { it.copy(loading = true, error = null) }
        sheetsJob = viewModelScope.launch {
            try {
                LxDiscoverClient.recommendPlaylists(
                    source = currentSource,
                    tagId = currentTag.id,
                    tagName = currentTag.name,
                    page = targetPage,
                )
                    .onSuccess { result ->
                        if (request == listGeneration) {
                            mutableState.update { current ->
                                val base = if (isRefresh) emptyList() else current.playlists
                                current.copy(
                                    playlists = (base + result.items).distinctBy { it.id },
                                    page = targetPage,
                                    hasMore = result.hasMore,
                                    loading = false,
                                    error = null,
                                )
                            }
                        }
                    }
                    .onFailure { error ->
                        if (request == listGeneration) {
                            mutableState.update {
                                it.copy(loading = false, error = error.message ?: "歌单加载失败，请重试")
                            }
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            }
        }
    }
}

data class RecommendSheetsUiState(
    val source: LxSearchSource = LxDiscoverClient.supportedSources.first(),
    val tags: LxRecommendTagsResult? = null,
    val selectedTag: LxRecommendTag = DEFAULT_RECOMMEND_TAG,
    val tagsLoading: Boolean = false,
    val playlists: List<LxPlaylistInfo> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = true,
    val loading: Boolean = false,
    val error: String? = null,
)
