package com.dd3boh.outertune.radio

import android.content.Context
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
        RadioPlayerManager.initialize(context)
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
                _currentIndex.value = if (foundIndex >= 0) foundIndex else 0
            } else {
                _currentIndex.value = 0
            }
            _isLoading.value = false
        }
    }

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
        refreshStations()
    }

    fun selectStation(index: Int, autoPlay: Boolean = true) {
        val list = _stations.value
        if (index in list.indices) {
            _currentIndex.value = index
            val station = list[index]
            viewModelScope.launch {
                context.dataStore.edit { it[RadioLastStationUuidKey] = station.stationUuid }
            }
            if (autoPlay) {
                RadioPlayerManager.play(station)
            }
        }
    }

    fun nextStation() {
        val list = _stations.value
        if (list.isNotEmpty()) {
            val next = (_currentIndex.value + 1) % list.size
            selectStation(next, autoPlay = true)
        }
    }

    fun previousStation() {
        val list = _stations.value
        if (list.isNotEmpty()) {
            val prev = if (_currentIndex.value - 1 < 0) list.size - 1 else _currentIndex.value - 1
            selectStation(prev, autoPlay = true)
        }
    }

    fun shuffleStation() {
        val list = _stations.value
        if (list.size > 1) {
            val random = list.indices.filter { it != _currentIndex.value }.random()
            selectStation(random, autoPlay = true)
        } else {
            refreshStations(shuffleOrder = true)
        }
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
}
