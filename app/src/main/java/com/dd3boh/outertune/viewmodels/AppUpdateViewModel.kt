package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.updates.AppUpdateRepository
import com.dd3boh.outertune.updates.AppUpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
    private val repository: AppUpdateRepository,
) : ViewModel() {
    val state = repository.state.stateIn(viewModelScope, SharingStarted.Eagerly, AppUpdateState())

    fun checkAutomatically() {
        viewModelScope.launch { repository.checkForUpdate() }
    }

    suspend fun checkManually() = repository.checkForUpdate(force = true)
    suspend fun claimPrompt(version: String) = repository.claimPrompt(version)
    suspend fun markViewed(version: String) = repository.markViewed(version)
}
