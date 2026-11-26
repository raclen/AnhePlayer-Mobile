package com.dd3boh.outertune.radio

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.RadioBitrateKey
import com.dd3boh.outertune.constants.RadioCountryKey
import com.dd3boh.outertune.constants.RadioFavoritesKey
import com.dd3boh.outertune.constants.RadioFavoriteStationsKey
import com.dd3boh.outertune.constants.RadioLanguageKey
import com.dd3boh.outertune.constants.RadioLastStationUuidKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RadioViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _stations = MutableStateFlow<List<RadioStation>>(emptyList())
    val stations: StateFlow<List<RadioStation>> = _stations.asStateFlow()

    private val _playbackStations = MutableStateFlow<List<RadioStation>>(emptyList())
    val playbackStations: StateFlow<List<RadioStation>> = _playbackStations.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var loadJob: Job? = null
    private var stationSearch: StationSearch? = null
    private var nextOffset = 0
    private var currentOrder = "clickcount"
    private var queueSearch: StationSearch? = null
    private var isFavoriteQueue = false
    private var favoriteLoadJob: Job? = null

    private val _isLoadingFavorites = MutableStateFlow(false)
    val isLoadingFavorites: StateFlow<Boolean> = _isLoadingFavorites.asStateFlow()

    private val _favoritesError = MutableStateFlow<String?>(null)
    val favoritesError: StateFlow<String?> = _favoritesError.asStateFlow()

    private data class StationSearch(
        val country: String?,
        val language: String?,
        val bitrate: String,
        val query: String?,
    ) {
        suspend fun fetch(offset: Int = 0, order: String = "clickcount") = RadioBrowserClient.fetchStations(
            countryCode = country,
            language = language,
            bitrateKey = bitrate,
            query = query,
            limit = PAGE_SIZE,
            order = order,
            offset = offset,
        )
    }

    /**
     * 自动跳过计数：某些电台已停播或流地址失效，连续切台失败时跳过它们，
     * 避免用户每点一次「下一曲」就只会看到一条报错。
     */
    private var autoSkipRemaining = 0

    val favorites = context.dataStore.data
        .map { it[RadioFavoritesKey] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val favoriteStations = context.dataStore.data
        .map { RadioFavorites.restore(it[RadioFavoritesKey] ?: emptySet(), it[RadioFavoriteStationsKey]) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val bitratePref = context.dataStore.data
        .map { it[RadioBitrateKey] ?: "all" }
        .distinctUntilChanged()

    val countryPref = context.dataStore.data
        .map { it[RadioCountryKey] ?: "CN" }
        .distinctUntilChanged()

    val languagePref = context.dataStore.data
        .map { it[RadioLanguageKey] ?: "chinese" }
        .distinctUntilChanged()

    init {
        try {
            RadioPlayerManager.initialize(context)
        } catch (e: Exception) {
            Log.e("RadioViewModel", "Failed to initialize RadioPlayerManager", e)
        }
        RadioPlayerManager.currentStation.value?.let { updateStationQueue(listOf(it), 0) }
        RadioPlayerManager.onSkipNext = { nextStation() }
        RadioPlayerManager.onSkipPrevious = { previousStation() }

        viewModelScope.launch {
            favoriteStations.collect { list ->
                if (isFavoriteQueue) {
                    val playing = RadioPlayerManager.currentStation.value
                    // 取消正在播放的收藏不应停播，先保留当前台，下一台再进入剩余收藏。
                    val playable = RadioFavorites.playbackQueue(list, playing)
                    val index = playable.indexOfFirst { it.stationUuid == playing?.stationUuid }.coerceAtLeast(0)
                    updateStationQueue(playable, index)
                }
            }
        }

        // 播放失败且是自动切台造成的，继续往后跳，最多连续跳 MAX_AUTO_SKIP 台
        viewModelScope.launch {
            RadioPlayerManager.playbackState.collect { state ->
                if (state == RadioPlaybackState.ERROR && autoSkipRemaining > 0) {
                    autoSkipRemaining--
                    // 轻微延迟，避免快速连发 prepare 把播放器打乱
                    delay(350)
                    if (RadioPlayerManager.playbackState.value == RadioPlaybackState.ERROR) {
                        skipToNextStation()
                    }
                } else if (state == RadioPlaybackState.PLAYING) {
                    autoSkipRemaining = 0
                }
            }
        }
        viewModelScope.launch {
            // 合并筛选配置，避免初始化时三个监听各自发起一次网络请求。
            combine(bitratePref, countryPref, languagePref) { _, _, _ -> Unit }
                .collect { refreshStations() }
        }
    }

    fun refreshStations(shuffleOrder: Boolean = false) {
        loadStations(shuffleOrder = shuffleOrder)
    }

    private fun loadStations(shuffleOrder: Boolean = false, searchDelayMillis: Long = 0) {
        loadJob?.cancel()
        val query = _searchQuery.value.trim().ifBlank { null }
        _isLoading.value = true
        _isLoadingMore.value = false
        _hasMore.value = false
        _loadError.value = null
        _stations.value = emptyList()
        stationSearch = null
        nextOffset = 0
        // 候选列表独立加载，不清空正在使用的播放队列。

        loadJob = viewModelScope.launch {
            try {
                if (searchDelayMillis > 0) delay(searchDelayMillis)
                val search = StationSearch(
                    country = context.dataStore.get(RadioCountryKey, "CN").ifBlank { null },
                    language = context.dataStore.get(RadioLanguageKey, "chinese").ifBlank { null },
                    bitrate = context.dataStore.get(RadioBitrateKey, "all"),
                    query = query,
                )
                val order = if (shuffleOrder) "random" else "clickcount"
                val page = search.fetch(order = order)
                val lastUuid = context.dataStore.get(RadioLastStationUuidKey, "")
                currentCoroutineContext().ensureActive()

                stationSearch = search
                currentOrder = order
                nextOffset = page.nextOffset
                _hasMore.value = page.hasMore
                _stations.value = page.stations
                if (!isFavoriteQueue && (queueSearch == search || RadioPlayerManager.currentStation.value == null)) {
                    queueSearch = search
                    val playingUuid = RadioPlayerManager.currentStation.value?.stationUuid ?: lastUuid
                    val index = page.stations.indexOfFirst { it.stationUuid == playingUuid }.coerceAtLeast(0)
                    updateStationQueue(page.stations, index)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _loadError.value = "电台加载失败，请检查网络后重试"
            } finally {
                // 被替换的旧请求不得清掉新请求的加载状态。
                if (currentCoroutineContext().isActive) _isLoading.value = false
            }
        }
    }

    fun loadMoreStations() {
        if (_isLoading.value || _isLoadingMore.value || !_hasMore.value) return
        val search = stationSearch ?: return
        // 随机首批没有稳定的分页顺序，继续浏览时从热门目录起点补齐并去重。
        val offset = if (currentOrder == "random") 0 else nextOffset
        _isLoadingMore.value = true
        _loadError.value = null
        loadJob = viewModelScope.launch {
            try {
                val page = search.fetch(offset = offset)
                currentCoroutineContext().ensureActive()
                val result = (_stations.value + page.stations).distinctBy { it.stationUuid }
                _stations.value = result
                nextOffset = page.nextOffset
                currentOrder = "clickcount"
                _hasMore.value = page.hasMore
                if (!isFavoriteQueue && queueSearch == search) {
                    updateStationQueue(result, _currentIndex.value)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _loadError.value = "更多电台加载失败，请检查网络后重试"
            } finally {
                if (currentCoroutineContext().isActive) _isLoadingMore.value = false
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        if (_searchQuery.value == query) return
        _searchQuery.value = query
        loadStations(searchDelayMillis = if (query.isBlank()) 0 else SEARCH_DELAY_MILLIS)
    }

    /**
     * 从候选列表主动选台并播放，清空自动跳过预算。
     * 「上一台/下一台/调频滑动」通过队列选台设 [MAX_AUTO_SKIP]。
     */
    fun selectStation(index: Int, autoPlay: Boolean = true) {
        val list = _stations.value
        if (index !in list.indices) return
        autoSkipRemaining = 0
        isFavoriteQueue = false
        queueSearch = stationSearch
        updateStationQueue(list, index)
        selectQueuedStation(index, autoPlay)
    }

    fun selectFavoriteStation(station: RadioStation) {
        val list = favoriteStations.value.filter { it.playUrl.isNotBlank() }
        val index = list.indexOfFirst { it.stationUuid == station.stationUuid }
        if (index < 0) return
        autoSkipRemaining = 0
        isFavoriteQueue = true
        queueSearch = null
        updateStationQueue(list, index)
        selectQueuedStation(index)
    }

    private fun updateStationQueue(list: List<RadioStation>, index: Int) {
        _playbackStations.value = list
        _currentIndex.value = index.coerceIn(0, list.lastIndex.coerceAtLeast(0))
        RadioPlayerManager.updateStationList(list, _currentIndex.value)
    }

    private fun selectQueuedStation(index: Int, autoPlay: Boolean = true) {
        val list = _playbackStations.value
        if (index !in list.indices) return
        val selected = list[index]
        // 切走后重新建立收藏队列，不让已取消收藏的上一台再次进入循环。
        val queue = if (isFavoriteQueue) RadioFavorites.playbackQueue(favoriteStations.value, selected) else list
        val targetIndex = queue.indexOfFirst { it.stationUuid == selected.stationUuid }.coerceAtLeast(0)
        updateStationQueue(queue, targetIndex)
        val station = queue[targetIndex]
        viewModelScope.launch {
            context.dataStore.edit { it[RadioLastStationUuidKey] = station.stationUuid }
        }
        if (autoPlay) RadioPlayerManager.play(station)
    }

    /** 用户主动切台：允许失败后连续跳过几台 */
    private fun selectStationWithAutoSkip(index: Int) {
        autoSkipRemaining = MAX_AUTO_SKIP
        selectQueuedStation(index)
    }

    fun nextStation() {
        val list = _playbackStations.value
        if (list.isNotEmpty()) {
            selectStationWithAutoSkip((_currentIndex.value + 1) % list.size)
        }
    }

    fun previousStation() {
        val list = _playbackStations.value
        if (list.isNotEmpty()) {
            val prev = if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
            selectStationWithAutoSkip(prev)
        }
    }

    /** 失败后自动切下一台（不重置预算） */
    private fun skipToNextStation() {
        val list = _playbackStations.value
        if (list.isNotEmpty()) {
            selectQueuedStation((_currentIndex.value + 1) % list.size)
        }
    }

    fun retryCurrentStation() {
        autoSkipRemaining = 0
        RadioPlayerManager.retry()
    }

    fun refreshFavoriteStations() {
        favoriteLoadJob?.cancel()
        _isLoadingFavorites.value = true
        _favoritesError.value = null
        favoriteLoadJob = viewModelScope.launch {
            try {
                cacheFavoriteStations(_stations.value + _playbackStations.value + listOfNotNull(RadioPlayerManager.currentStation.value))
                val ids = context.dataStore.get(RadioFavoritesKey, emptySet()).filter { it.isNotBlank() }.sorted()
                val foundIds = mutableSetOf<String>()
                for (batch in ids.chunked(50)) {
                    val result = RadioBrowserClient.fetchStationsByUuid(batch.toSet())
                    currentCoroutineContext().ensureActive()
                    foundIds += result.map { it.stationUuid }
                    cacheFavoriteStations(result)
                }
                val remaining = context.dataStore.get(RadioFavoritesKey, emptySet()).intersect(ids.toSet())
                if (remaining.any { it !in foundIds }) {
                    _favoritesError.value = "部分收藏电台暂时无法获取信息，收藏记录已保留"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _favoritesError.value = "收藏更新失败，已保留缓存信息，请联网后重试"
            } finally {
                if (currentCoroutineContext().isActive) _isLoadingFavorites.value = false
            }
        }
    }

    private suspend fun cacheFavoriteStations(stations: List<RadioStation>) {
        context.dataStore.edit { prefs ->
            val ids = prefs[RadioFavoritesKey] ?: emptySet()
            val known = stations.filter { it.stationUuid in ids }
            if (known.isNotEmpty()) {
                prefs[RadioFavoriteStationsKey] = RadioFavorites.updateCache(ids, prefs[RadioFavoriteStationsKey], known)
            }
        }
    }

    fun toggleFavorite(station: RadioStation) {
        if (station.stationUuid.isBlank()) return
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                // 必须在事务内读取最新集合，避免连续收藏或取消时覆盖另一笔操作。
                val currentIds = prefs[RadioFavoritesKey] ?: emptySet()
                val nextIds = if (station.stationUuid in currentIds) currentIds - station.stationUuid
                else currentIds + station.stationUuid
                prefs[RadioFavoritesKey] = nextIds
                prefs[RadioFavoriteStationsKey] = RadioFavorites.updateCache(
                    nextIds, prefs[RadioFavoriteStationsKey], listOf(station),
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        RadioPlayerManager.onSkipNext = null
        RadioPlayerManager.onSkipPrevious = null
    }

    companion object {
        private const val PAGE_SIZE = 80
        private const val SEARCH_DELAY_MILLIS = 350L

        /** 一次点击最多连续跳过多少台 */
        private const val MAX_AUTO_SKIP = 4
    }
}
