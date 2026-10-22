package com.dd3boh.outertune.updates

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.AppUpdateLastAttemptKey
import com.dd3boh.outertune.constants.AppUpdateLastSuccessKey
import com.dd3boh.outertune.constants.AppUpdatePromptedVersionKey
import com.dd3boh.outertune.constants.AppUpdateReleaseKey
import com.dd3boh.outertune.constants.AppUpdateViewedVersionKey
import com.dd3boh.outertune.utils.dataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class AppUpdateState(
    val isLoaded: Boolean = false,
    val isChecking: Boolean = false,
    val release: AppRelease? = null,
    val lastSuccessAt: Long = 0,
    val promptedVersion: String = "",
    val viewedVersion: String = "",
) {
    val hasUnreadUpdate: Boolean
        get() = release?.let { isNewerVersion(it.version, viewedVersion) } == true

    val needsPrompt: Boolean
        get() = hasUnreadUpdate && release?.let { isNewerVersion(it.version, promptedVersion) } == true
}

sealed interface AppUpdateCheckResult {
    data object Skipped : AppUpdateCheckResult
    data object UpToDate : AppUpdateCheckResult
    data class Available(val release: AppRelease) : AppUpdateCheckResult
    data class Failed(val message: String) : AppUpdateCheckResult
}

@Singleton
class AppUpdateRepository internal constructor(private val dataStore: DataStore<Preferences>) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.dataStore)

    private val checkMutex = Mutex()
    private val isChecking = MutableStateFlow(false)

    val state = combine(dataStore.data, isChecking) { preferences, checking ->
        AppUpdateState(
            isLoaded = true,
            isChecking = checking,
            release = preferences[AppUpdateReleaseKey]?.let(AppRelease::fromCache)
                ?.takeIf { it.isNewerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()) },
            lastSuccessAt = preferences[AppUpdateLastSuccessKey] ?: 0,
            promptedVersion = preferences[AppUpdatePromptedVersionKey].orEmpty(),
            viewedVersion = preferences[AppUpdateViewedVersionKey].orEmpty(),
        )
    }

    suspend fun checkForUpdate(force: Boolean = false): AppUpdateCheckResult {
        if (!checkMutex.tryLock()) return AppUpdateCheckResult.Skipped
        try {
            val now = System.currentTimeMillis()
            val lastAttempt = dataStore.data.first()[AppUpdateLastAttemptKey] ?: 0
            // 自动检查按尝试时间限频；手动检查不受限制，系统时间回拨时允许重新检查。
            if (!force && lastAttempt > 0 && now >= lastAttempt && now - lastAttempt < CHECK_INTERVAL) {
                return AppUpdateCheckResult.Skipped
            }
            isChecking.value = true
            dataStore.edit { it[AppUpdateLastAttemptKey] = now }
            val release = GitHubReleaseClient.latestRelease(BuildConfig.FLAVOR)
            // 只有校验通过才替换缓存，失败不能把上次成功的更新信息清空。
            dataStore.edit {
                it[AppUpdateReleaseKey] = release.toCache()
                it[AppUpdateLastSuccessKey] = System.currentTimeMillis()
            }
            return if (release.isNewerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())) {
                AppUpdateCheckResult.Available(release)
            } else {
                AppUpdateCheckResult.UpToDate
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("AppUpdate", "检查更新失败，保留上次成功的缓存", error)
            return AppUpdateCheckResult.Failed(
                (error as? UpdateCheckException)?.message ?: "无法检查更新，请检查网络后重试"
            )
        } finally {
            isChecking.value = false
            checkMutex.unlock()
        }
    }

    /** 展示前事务性领取提醒，避免重组、旋转、重启重复弹出同一版本。 */
    suspend fun claimPrompt(version: String): Boolean {
        var claimed = false
        try {
            dataStore.edit {
                val release = it[AppUpdateReleaseKey]?.let(AppRelease::fromCache)
                if (release?.version == version &&
                    release.isNewerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong()) &&
                    isNewerVersion(version, it[AppUpdatePromptedVersionKey].orEmpty()) &&
                    isNewerVersion(version, it[AppUpdateViewedVersionKey].orEmpty())
                ) {
                    it[AppUpdatePromptedVersionKey] = version
                    claimed = true
                }
            }
        } catch (error: IOException) {
            Log.w("AppUpdate", "保存更新提醒标记失败，保留未提醒状态", error)
            return false
        }
        return claimed
    }

    suspend fun markViewed(version: String) {
        try {
            dataStore.edit {
                if (isNewerVersion(version, it[AppUpdateViewedVersionKey].orEmpty())) {
                    it[AppUpdateViewedVersionKey] = version
                }
            }
        } catch (error: IOException) {
            Log.w("AppUpdate", "保存更新已读标记失败，保留未读状态", error)
        }
    }

    private companion object {
        const val CHECK_INTERVAL = 24 * 60 * 60 * 1000L
    }
}
