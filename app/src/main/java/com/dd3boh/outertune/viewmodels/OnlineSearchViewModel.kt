package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.lx.LxMusicInfo
import com.dd3boh.outertune.source.lx.LxSearchClient
import com.dd3boh.outertune.source.lx.LxSearchSource
import com.dd3boh.outertune.source.lx.toMediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnlineSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    database: MusicDatabase,
) : ViewModel() {
    // Navigation 已解码参数，再次 URLDecoder 会破坏名称中的 + 和 %。
    val query = savedStateHandle.get<String>("query").orEmpty()
    private val initialSource = LxSearchSource.entries.firstOrNull {
        it.sourceId == savedStateHandle.get<String>("source")
    } ?: LxSearchSource.KUWO
    private val remoteViewState = MutableStateFlow(
        LxSearchViewState(source = initialSource, isLoading = true)
    )
    private var searchJob: Job? = null
    private val localSongs = combine(
        database.searchSongsAllLocal(query, LOCAL_RESULT_LIMIT),
        database.searchLocalArtistSongs(query, LOCAL_RESULT_LIMIT),
        database.searchLocalAlbumSongs(query, LOCAL_RESULT_LIMIT),
    ) { titleMatches, artistMatches, albumMatches ->
        (titleMatches + artistMatches + albumMatches)
            .distinctBy { it.id }
            .take(LOCAL_RESULT_LIMIT)
    }
    val viewState = combine(remoteViewState, localSongs) { remoteState, localSongs ->
        remoteState.copy(localSongs = localSongs)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        LxSearchViewState(source = initialSource, isLoading = true),
    )

    init {
        selectSource(initialSource)
    }

    fun selectSource(source: LxSearchSource) {
        if (remoteViewState.value.source == source && searchJob?.isActive == true) return
        searchJob?.cancel()
        remoteViewState.value = LxSearchViewState(source = source, isLoading = true)
        searchJob = viewModelScope.launch {
            LxSearchClient.search(query, source)
                .onSuccess { songs ->
                    remoteViewState.value = LxSearchViewState(
                        source = source,
                        items = songs.map { it.toMediaMetadata() },
                    )
                }
                .onFailure { error ->
                    remoteViewState.value = LxSearchViewState(
                        source = source,
                        error = error.message ?: "搜索失败",
                    )
                }
        }
    }

    suspend fun preparePlaybackQueue(selectedId: String): List<MediaMetadata> {
        val items = remoteViewState.value.items
        val selectedIndex = items.indexOfFirst { it.id == selectedId }
        if (selectedIndex == -1) return items

        val preparedItems = items.toMutableList()
        preparedItems[selectedIndex] = ensureCover(items[selectedIndex])
        remoteViewState.update { it.copy(items = preparedItems) }
        return preparedItems
    }

    private suspend fun ensureCover(item: MediaMetadata): MediaMetadata {
        val musicInfo = item.sourceData?.let { sourceData ->
            runCatching { com.dd3boh.outertune.source.lx.lxJson.decodeFromString<LxMusicInfo>(sourceData) }.getOrNull()
        } ?: return item
        return LxSearchClient.loadCover(musicInfo)
            ?.let { item.copy(thumbnailUrl = it) }
            ?: item
    }

    private companion object {
        const val LOCAL_RESULT_LIMIT = 15
    }
}

data class LxSearchViewState(
    val localSongs: List<Song> = emptyList(),
    val items: List<MediaMetadata> = emptyList(),
    val source: LxSearchSource = LxSearchSource.KUWO,
    val isLoading: Boolean = false,
    val error: String? = null,
)
