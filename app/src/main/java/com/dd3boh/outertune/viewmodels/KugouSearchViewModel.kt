package com.dd3boh.outertune.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.PauseSearchHistoryKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.SearchHistory
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.models.toMediaMetadata
import com.dd3boh.outertune.source.kugou.KugouCollection
import com.dd3boh.outertune.source.kugou.KugouConcept
import com.dd3boh.outertune.source.kugou.KugouSearchType
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class KugouSearchViewModel @Inject constructor(
    private val database: MusicDatabase,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mutableState = MutableStateFlow(KugouSearchUiState())
    val state = mutableState.asStateFlow()
    private val historyQuery = MutableStateFlow("")
    val history = historyQuery.flatMapLatest { database.searchHistory(it.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var debounceJob: Job? = null
    private var songJob: Job? = null
    private var playlistJob: Job? = null
    private var localJob: Job? = null
    private var generation = 0L

    init {
        viewModelScope.launch {
            KugouConcept.sessionVersion.collect { resetQuery(mutableState.value.query, immediate = true) }
        }
    }

    fun updateQuery(query: String) = resetQuery(query, immediate = false)

    fun submit(query: String = mutableState.value.query) {
        resetQuery(query, immediate = true)
        val value = query.trim()
        if (value.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (context.dataStore[PauseSearchHistoryKey] != true) database.insert(SearchHistory(query = value))
        }
    }

    fun deleteHistory(item: SearchHistory) {
        viewModelScope.launch(Dispatchers.IO) { database.delete(item) }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { database.clearSearchHistory() }
    }

    private fun resetQuery(query: String, immediate: Boolean) {
        debounceJob?.cancel()
        songJob?.cancel()
        playlistJob?.cancel()
        localJob?.cancel()
        val request = ++generation
        mutableState.value = KugouSearchUiState(query = query, type = mutableState.value.type)
        historyQuery.value = query
        val keyword = query.trim()
        if (keyword.isBlank()) return
        localJob = viewModelScope.launch {
            combine(
                database.searchSongsAllLocal(keyword, 30),
                database.searchLocalArtistSongs(keyword, 30),
            ) { titleMatches, artistMatches ->
                (titleMatches + artistMatches).distinctBy { it.id }.take(30).map { it.toMediaMetadata() }
            }.collect { songs ->
                if (request == generation) mutableState.update { it.copy(localSongs = songs) }
            }
        }
        debounceJob = viewModelScope.launch {
            if (!immediate) delay(350)
            if (request == generation) loadMore()
        }
    }

    fun selectType(type: KugouSearchType) {
        if (mutableState.value.type == type) return
        debounceJob?.cancel()
        songJob?.cancel()
        playlistJob?.cancel()
        mutableState.update { it.copy(type = type, songsLoading = false, playlistsLoading = false) }
        val page = if (type == KugouSearchType.SONG) mutableState.value.songPage else mutableState.value.playlistPage
        if (page == 0) loadMore()
    }

    fun cancelLoading() {
        debounceJob?.cancel()
        songJob?.cancel()
        playlistJob?.cancel()
        mutableState.update { it.copy(songsLoading = false, playlistsLoading = false) }
    }

    fun loadMore() {
        val snapshot = mutableState.value
        val query = snapshot.query.trim()
        if (query.isEmpty()) return
        val request = generation
        val session = KugouConcept.sessionVersion.value
        when (snapshot.type) {
            KugouSearchType.SONG -> {
                if (snapshot.songsLoading || !snapshot.songsHasMore) return
                mutableState.update { it.copy(songsLoading = true, songsError = null) }
                songJob = viewModelScope.launch {
                    try {
                        val page = KugouConcept.searchSongs(query, snapshot.songPage + 1)
                        currentCoroutineContext().ensureActive()
                        if (request == generation && session == KugouConcept.sessionVersion.value) mutableState.update {
                            it.copy(songs = (it.songs + page.songs).distinctBy { song -> song.id }, songPage = snapshot.songPage + 1,
                                songsHasMore = page.hasMore, songsLoading = false)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (request == generation && session == KugouConcept.sessionVersion.value) mutableState.update {
                            it.copy(songsLoading = false, songsError = error.message ?: "歌曲搜索失败，请重试")
                        }
                    }
                }
            }
            KugouSearchType.PLAYLIST -> {
                if (snapshot.playlistsLoading || !snapshot.playlistsHasMore) return
                mutableState.update { it.copy(playlistsLoading = true, playlistsError = null) }
                playlistJob = viewModelScope.launch {
                    try {
                        val page = KugouConcept.searchPlaylists(query, snapshot.playlistPage + 1)
                        currentCoroutineContext().ensureActive()
                        if (request == generation && session == KugouConcept.sessionVersion.value) mutableState.update {
                            it.copy(playlists = (it.playlists + page.collections).distinctBy { collection -> collection.id },
                                playlistPage = snapshot.playlistPage + 1, playlistsHasMore = page.hasMore, playlistsLoading = false)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        if (request == generation && session == KugouConcept.sessionVersion.value) mutableState.update {
                            it.copy(playlistsLoading = false, playlistsError = error.message ?: "歌单搜索失败，请重试")
                        }
                    }
                }
            }
        }
    }
}

data class KugouSearchUiState(
    val query: String = "",
    val type: KugouSearchType = KugouSearchType.SONG,
    val localSongs: List<MediaMetadata> = emptyList(),
    val songs: List<MediaMetadata> = emptyList(),
    val playlists: List<KugouCollection> = emptyList(),
    val songPage: Int = 0,
    val playlistPage: Int = 0,
    val songsHasMore: Boolean = true,
    val playlistsHasMore: Boolean = true,
    val songsLoading: Boolean = false,
    val playlistsLoading: Boolean = false,
    val songsError: String? = null,
    val playlistsError: String? = null,
)
