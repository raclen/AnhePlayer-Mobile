package com.dd3boh.outertune.updates

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.constants.AppUpdateLastSuccessKey
import com.dd3boh.outertune.constants.AppUpdatePromptedVersionKey
import com.dd3boh.outertune.constants.AppUpdateReleaseKey
import com.dd3boh.outertune.constants.AppUpdateViewedVersionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 验证更新标记事务失败和取消时的行为，不涉及 Compose 或 UI 测试。 */
class AppUpdateRepositoryTest {
    private val nextVersion = BuildConfig.VERSION_NAME.split('.').let { parts ->
        "${parts[0]}.${parts[1]}.${parts[2].toLong() + 1}"
    }
    private val release = AppRelease(
        version = nextVersion,
        versionCode = BuildConfig.VERSION_CODE.toLong() + 1,
        releaseUrl = "$APP_PROJECT_URL/releases/tag/v$nextVersion",
        notes = "更新内容",
    )
    private val initialPreferences = preferencesOf(
        AppUpdateReleaseKey to release.toCache(),
        AppUpdateLastSuccessKey to 1234L,
        AppUpdatePromptedVersionKey to BuildConfig.VERSION_NAME,
        AppUpdateViewedVersionKey to BuildConfig.VERSION_NAME,
    )

    @Test
    fun failedPromptCommitReturnsFalseAndCanBeRetried() = runBlocking {
        val dataStore = FailingDataStore(initialPreferences)
        val repository = AppUpdateRepository(dataStore)
        dataStore.writeFailure = IOException("模拟磁盘写入失败")

        assertFalse(repository.claimPrompt(release.version))
        assertEquals(initialPreferences, dataStore.data.first())
        assertTrue(repository.state.first().needsPrompt)
        assertEquals(release, repository.state.first().release)

        dataStore.writeFailure = null
        assertTrue(repository.claimPrompt(release.version))
        assertEquals(release.version, dataStore.data.first()[AppUpdatePromptedVersionKey])
        assertFalse(repository.claimPrompt(release.version))
        assertFalse(repository.state.first().needsPrompt)
        assertTrue(repository.state.first().hasUnreadUpdate)
    }

    @Test
    fun failedViewedCommitPreservesUnreadStateAndCanBeRetried() = runBlocking {
        val dataStore = FailingDataStore(initialPreferences)
        val repository = AppUpdateRepository(dataStore)
        dataStore.writeFailure = IOException("模拟磁盘写入失败")

        repository.markViewed(release.version)
        assertEquals(initialPreferences, dataStore.data.first())
        assertTrue(repository.state.first().hasUnreadUpdate)
        assertTrue(repository.state.first().needsPrompt)

        dataStore.writeFailure = null
        repository.markViewed(release.version)
        assertEquals(release.version, dataStore.data.first()[AppUpdateViewedVersionKey])
        assertFalse(repository.state.first().hasUnreadUpdate)
        assertFalse(repository.state.first().needsPrompt)
        assertEquals(release, repository.state.first().release)
    }

    @Test
    fun promptClaimDoesNotSwallowCancellation() = runBlocking {
        val dataStore = FailingDataStore(initialPreferences)
        val repository = AppUpdateRepository(dataStore)
        val cancellation = CancellationException("模拟调用方取消")
        dataStore.writeFailure = cancellation

        val error = runCatching { repository.claimPrompt(release.version) }.exceptionOrNull()
        assertSame(cancellation, error)
        assertEquals(initialPreferences, dataStore.data.first())
    }

    @Test
    fun markingViewedDoesNotSwallowCancellation() = runBlocking {
        val dataStore = FailingDataStore(initialPreferences)
        val repository = AppUpdateRepository(dataStore)
        val cancellation = CancellationException("模拟调用方取消")
        dataStore.writeFailure = cancellation

        val error = runCatching { repository.markViewed(release.version) }.exceptionOrNull()
        assertSame(cancellation, error)
        assertEquals(initialPreferences, dataStore.data.first())
    }

    private class FailingDataStore(initialPreferences: Preferences) : DataStore<Preferences> {
        private val preferences = MutableStateFlow(initialPreferences)
        override val data: Flow<Preferences> = preferences
        var writeFailure: Exception? = null

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val updated = transform(preferences.value)
            // 在转换完成、提交之前失败，覆盖 claimed 已设为 true 但实际未保存的情况。
            writeFailure?.let { throw it }
            preferences.value = updated
            return updated
        }
    }
}
