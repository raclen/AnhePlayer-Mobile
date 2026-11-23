package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.Playlist
import com.dd3boh.outertune.db.entities.Song
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.lx.LxMusicInfo
import com.dd3boh.outertune.source.lx.LxPlaylistClient
import com.dd3boh.outertune.source.lx.LxPlaylistInfo
import com.dd3boh.outertune.source.lx.LxSearchClient
import com.dd3boh.outertune.source.lx.LxSearchSource
import com.dd3boh.outertune.source.lx.toMediaMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
    private val remoteViewState = MutableStateFlow(LxSearchViewState(source = initialSource))
    private var searchJob: Job? = null
    private var generation = 0
    private val localSongs = combine(
        database.searchSongsAllLocal(query),
        database.searchLocalArtistSongs(query),
        database.searchLocalAlbumSongs(query),
    ) { titleMatches, artistMatches, albumMatches ->
        (titleMatches + artistMatches + albumMatches).distinctBy { it.id }
    }
    val viewState = combine(remoteViewState, localSongs, database.searchPlaylists(query)) { remote, songs, playlists ->
        remote.copy(localSongs = songs, localPlaylists = playlists)
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        LxSearchViewState(source = initialSource, isLoading = true),
    )

    init { loadMore() }

    fun selectSource(source: LxSearchSource) {
        if (remoteViewState.value.source == source) return
        reset(source, remoteViewState.value.type)
    }

    fun selectType(type: OnlineSearchType) {
        if (remoteViewState.value.type == type) return
        reset(remoteViewState.value.source, type)
    }

    private fun reset(source: LxSearchSource, type: OnlineSearchType) {
        generation++
        searchJob?.cancel()
        remoteViewState.value = LxSearchViewState(source = source, type = type)
        loadMore()
    }

    fun loadMore() {
        val previous = remoteViewState.value
        if (previous.isLoading || (!previous.hasMore && previous.page > 0)) return
        if (previous.type == OnlineSearchType.PLAYLISTS && !LxPlaylistClient.supports(previous.source)) return
        val requestGeneration = generation
        val nextPage = previous.page + 1
        remoteViewState.update { it.copy(isLoading = true, error = null) }
        searchJob = viewModelScope.launch {
            val result = when (previous.type) {
                OnlineSearchType.SONGS -> LxSearchClient.searchPage(query, previous.source, nextPage).map { page ->
                    previous.copy(
                        items = (previous.items + page.items.map { it.toMediaMetadata() }).distinctBy { it.id },
                        page = nextPage, hasMore = page.hasMore,
                    )
                }
                OnlineSearchType.PLAYLISTS -> LxPlaylistClient.search(query, previous.source, nextPage).map { page ->
                    previous.copy(
                        playlists = (previous.playlists + page.items).distinctBy { it.id },
                        page = nextPage, hasMore = page.hasMore,
                    )
                }
            }
            // 即使旧来源返回较晚，也不能覆盖用户已经切换的新结果。
            if (!isActive || requestGeneration != generation) return@launch
            result.onSuccess { next ->
                remoteViewState.update { current ->
                    next.copy(
                        items = (current.items + next.items).distinctBy { it.id },
                        isLoading = false, error = null,
                    )
                }
            }.onFailure { remoteViewState.update { state -> state.copy(isLoading = false, error = it.message ?: "搜索失败") } }
        }
    }

    suspend fun preparePlaybackQueue(selectedId: String): List<MediaMetadata> {
        val items = remoteViewState.value.items
        val selectedIndex = items.indexOfFirst { it.id == selectedId }
        if (selectedIndex == -1) return items
        val selected = ensureCover(items[selectedIndex])
        // 补封面的等待期间可能已经加载下一页，不能用旧快照覆盖新增结果。
        remoteViewState.update { state -> state.copy(items = state.items.map { if (it.id == selectedId) selected else it }) }
        return items.toMutableList().apply { this[selectedIndex] = selected }
    }

    private suspend fun ensureCover(item: MediaMetadata): MediaMetadata {
        val musicInfo = item.sourceData?.let { sourceData ->
            runCatching { com.dd3boh.outertune.source.lx.lxJson.decodeFromString<LxMusicInfo>(sourceData) }.getOrNull()
        } ?: return item
        return LxSearchClient.loadCover(musicInfo)?.let { item.copy(thumbnailUrl = it) } ?: item
    }

}

enum class OnlineSearchType(val displayName: String) {
    SONGS("歌曲"), PLAYLISTS("歌单"),
}

data class LxSearchViewState(
    val localSongs: List<Song> = emptyList(),
    val localPlaylists: List<Playlist> = emptyList(),
    val items: List<MediaMetadata> = emptyList(),
    val playlists: List<LxPlaylistInfo> = emptyList(),
    val source: LxSearchSource = LxSearchSource.KUWO,
    val type: OnlineSearchType = OnlineSearchType.SONGS,
    val page: Int = 0,
    val hasMore: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
)
