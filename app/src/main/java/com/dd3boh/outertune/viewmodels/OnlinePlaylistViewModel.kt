package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.lx.LxPlaylistClient
import com.dd3boh.outertune.source.lx.LxSearchSource
import com.dd3boh.outertune.source.lx.toMediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlinePlaylistViewModel @Inject constructor(savedStateHandle: SavedStateHandle) : ViewModel() {
    private val source = LxSearchSource.entries.firstOrNull { it.sourceId == savedStateHandle.get<String>("source") }
    private val playlistId = savedStateHandle.get<String>("id").orEmpty()
    val title = savedStateHandle.get<String>("title").orEmpty().ifBlank { "在线歌单" }
    val sourceName = source?.displayName.orEmpty()
    private val mutableState = MutableStateFlow(OnlinePlaylistState())
    val state = mutableState.asStateFlow()

    init { loadMore() }

    fun loadMore() {
        val previous = mutableState.value
        if (previous.loading || (previous.page > 0 && !previous.hasMore)) return
        val selectedSource = source
        if (selectedSource == null || playlistId.isBlank()) {
            mutableState.update { it.copy(error = "歌单参数无效") }
            return
        }
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            LxPlaylistClient.songs(playlistId, selectedSource, previous.page + 1)
                .onSuccess { page ->
                    mutableState.value = OnlinePlaylistState(
                        songs = (previous.songs + page.items.map { it.toMediaMetadata() }).distinctBy { it.id },
                        page = previous.page + 1, hasMore = page.hasMore,
                    )
                }
                .onFailure { error -> mutableState.update { it.copy(loading = false, error = error.message ?: "歌单加载失败") } }
        }
    }
}

data class OnlinePlaylistState(
    val songs: List<MediaMetadata> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)
