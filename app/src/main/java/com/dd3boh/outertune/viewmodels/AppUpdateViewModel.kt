package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.updates.AppRelease
import com.dd3boh.outertune.updates.AppUpdateCheckResult
import com.dd3boh.outertune.updates.AppUpdateDownloader
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
    private val downloader: AppUpdateDownloader,
) : ViewModel() {
    val state = repository.state.stateIn(viewModelScope, SharingStarted.Eagerly, AppUpdateState())
    val downloadState = downloader.state

    fun checkAutomatically() {
        viewModelScope.launch { repository.checkForUpdate() }
    }

    suspend fun downloadUpdate(release: AppRelease): String? {
        val candidate = if (release.assets.isEmpty()) {
            when (val result = repository.checkForUpdate(force = true)) {
                is AppUpdateCheckResult.Available -> result.release
                is AppUpdateCheckResult.Failed -> return result.message
                AppUpdateCheckResult.UpToDate -> return "当前已是最新版本"
                AppUpdateCheckResult.Skipped -> return "正在检查更新，请稍后重试"
            }
        } else release
        downloader.download(candidate)
        return null
    }

    suspend fun cancelDownload() = downloader.cancel()
    suspend fun prepareInstall() = downloader.prepareInstall()

    suspend fun checkManually() = repository.checkForUpdate(force = true)
    suspend fun claimPrompt(version: String) = repository.claimPrompt(version)
    suspend fun markViewed(version: String) = repository.markViewed(version)
}
