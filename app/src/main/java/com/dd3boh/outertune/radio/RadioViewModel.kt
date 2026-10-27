package com.dd3boh.outertune.radio

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.constants.RadioBitrateKey
import com.dd3boh.outertune.constants.RadioCountryKey
import com.dd3boh.outertune.constants.RadioFavoritesKey
import com.dd3boh.outertune.constants.RadioLanguageKey
import com.dd3boh.outertune.constants.RadioLastStationUuidKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var loadJob: Job? = null

    /**
     * 自动跳过计数：某些电台已停播或流地址失效，连续切台失败时跳过它们，
     * 避免用户每点一次「下一曲」就只会看到一条报错。
     */
    private var autoSkipRemaining = 0

    val favorites = context.dataStore.data
        .map { it[RadioFavoritesKey] ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

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
        RadioPlayerManager.onSkipNext = { nextStation() }
        RadioPlayerManager.onSkipPrevious = { previousStation() }

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
            // 当筛选配置发生变化时自动刷新电台列表
            bitratePref.collect { refreshStations() }
        }
        viewModelScope.launch {
            countryPref.collect { refreshStations() }
        }
        viewModelScope.launch {
            languagePref.collect { refreshStations() }
        }
    }

    fun refreshStations(shuffleOrder: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            val bitrate = context.dataStore.get(RadioBitrateKey, "all")
            val country = context.dataStore.get(RadioCountryKey, "CN")
            val language = context.dataStore.get(RadioLanguageKey, "chinese")
            val query = _searchQuery.value

            val order = if (shuffleOrder) "random" else "clickcount"
            val result = RadioBrowserClient.fetchStations(
                countryCode = country.ifBlank { null },
                language = language.ifBlank { null },
                bitrateKey = bitrate,
                query = query.ifBlank { null },
                limit = 80,
                order = order,
            )

            _stations.value = result
            if (result.isNotEmpty()) {
                val lastUuid = context.dataStore.get(RadioLastStationUuidKey, "")
                val foundIndex = result.indexOfFirst { it.stationUuid == lastUuid }
                val targetIndex = if (foundIndex >= 0) foundIndex else 0
                _currentIndex.value = targetIndex
                RadioPlayerManager.updateStationList(result, targetIndex)
            } else {
                _currentIndex.value = 0
                RadioPlayerManager.updateStationList(emptyList(), 0)
            }
            _isLoading.value = false
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        refreshStations()
    }

    /**
     * 选台并播放。本方法不碰自动跳过预算，由调用方决定：
     * 「上一台/下一台/随机/调频滑动」设 [MAX_AUTO_SKIP]；从列表主动选台置 0。
     */
    fun selectStation(index: Int, autoPlay: Boolean = true) {
        val list = _stations.value
        if (index in list.indices) {
            _currentIndex.value = index
            val station = list[index]
            RadioPlayerManager.updateStationList(list, index)
            viewModelScope.launch {
                context.dataStore.edit { it[RadioLastStationUuidKey] = station.stationUuid }
            }
            if (autoPlay) {
                RadioPlayerManager.play(station)
            }
        }
    }

    /** 用户主动切台：允许失败后连续跳过几台 */
    private fun selectStationWithAutoSkip(index: Int) {
        autoSkipRemaining = MAX_AUTO_SKIP
        selectStation(index)
    }

    fun nextStation() {
        val list = _stations.value
        if (list.isNotEmpty()) {
            selectStationWithAutoSkip((_currentIndex.value + 1) % list.size)
        }
    }

    fun previousStation() {
        val list = _stations.value
        if (list.isNotEmpty()) {
            val prev = if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
            selectStationWithAutoSkip(prev)
        }
    }

    fun shuffleStation() {
        val list = _stations.value
        if (list.size > 1) {
            val random = list.indices.filter { it != _currentIndex.value }.random()
            selectStationWithAutoSkip(random)
        } else {
            refreshStations(shuffleOrder = true)
        }
    }

    /** 失败后自动切下一台（不重置预算） */
    private fun skipToNextStation() {
        val list = _stations.value
        if (list.isNotEmpty()) {
            selectStation((_currentIndex.value + 1) % list.size)
        }
    }

    fun retryCurrentStation() {
        autoSkipRemaining = 0
        RadioPlayerManager.retry()
    }

    fun toggleFavorite(station: RadioStation) {
        val currentFavs = favorites.value
        val nextFavs = if (currentFavs.contains(station.stationUuid)) {
            currentFavs - station.stationUuid
        } else {
            currentFavs + station.stationUuid
        }
        viewModelScope.launch {
            context.dataStore.edit { it[RadioFavoritesKey] = nextFavs }
        }
    }

    override fun onCleared() {
        super.onCleared()
        RadioPlayerManager.onSkipNext = null
        RadioPlayerManager.onSkipPrevious = null
    }

    companion object {
        /** 一次点击最多连续跳过多少台 */
        private const val MAX_AUTO_SKIP = 4
    }
}
