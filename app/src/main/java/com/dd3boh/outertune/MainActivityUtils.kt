package com.dd3boh.outertune

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.AUTO_SCAN_COOLDOWN
import com.dd3boh.outertune.constants.AUTO_SCAN_SOFT_COOLDOWN
import com.dd3boh.outertune.constants.AutomaticScannerKey
import com.dd3boh.outertune.constants.ExcludedScanPathsKey
import com.dd3boh.outertune.constants.LastLocalScanKey
import com.dd3boh.outertune.constants.LocalLibraryEnableKey
import com.dd3boh.outertune.constants.SCANNER_OWNER_LM
import com.dd3boh.outertune.constants.ScanPathsKey
import com.dd3boh.outertune.constants.ScannerImpl
import com.dd3boh.outertune.constants.ScannerImplKey
import com.dd3boh.outertune.constants.ScannerMatchCriteria
import com.dd3boh.outertune.constants.ScannerSensitivityKey
import com.dd3boh.outertune.constants.ScannerStrictExtKey
import com.dd3boh.outertune.constants.ScannerStrictFilePathsKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.ui.utils.clearDtCache
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.enumPreference
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.destroyScanner
import com.dd3boh.outertune.utils.scanners.LocalMediaScanner.Companion.scannerState
import com.dd3boh.outertune.utils.scanners.uriListFromString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneOffset

internal enum class ScanTrigger {
    STARTUP,
    AUDIO_PERMISSION_GRANTED,
}

internal enum class ScanStatus {
    COMPLETED,
    SKIPPED,
    WAITING_PERMISSION,
    FAILED,
}

internal data class ScanInitResult(
    val status: ScanStatus,
    val localScanAttempted: Boolean = false,
)

private val localMediaScanMutex = Mutex()

/** 顶层扫描串行执行，内部元数据提取仍保留原有并行度。 */
internal suspend fun <T> withLocalMediaScanner(
    context: Context,
    scannerImpl: ScannerImpl,
    block: suspend (LocalMediaScanner) -> T,
): T = localMediaScanMutex.withLock {
    check(scannerState.value <= 0) { "本地媒体扫描器正在使用中" }
    val scanner = LocalMediaScanner.getScanner(context, scannerImpl, SCANNER_OWNER_LM)
    try {
        block(scanner)
    } finally {
        withContext(NonCancellable) {
            clearDtCache()
            destroyScanner(SCANNER_OWNER_LM)
        }
    }
}

internal suspend fun scanInit(
    context: Context,
    database: MusicDatabase,
    downloadUtil: DownloadUtil,
    coroutineScope: CoroutineScope,
    playerConnection: PlayerConnection?,
    snackbarHostState: SnackbarHostState,
    trigger: ScanTrigger = ScanTrigger.STARTUP,
): ScanInitResult {
    val MAIN_TAG = "MainOtActivity"
    val localLibEnable = context.dataStore.get(LocalLibraryEnableKey, defaultValue = true)
    val autoScan = context.dataStore.get(AutomaticScannerKey, defaultValue = true)
    if (!autoScan || (trigger == ScanTrigger.AUDIO_PERMISSION_GRANTED && !localLibEnable)) {
        Log.i(MAIN_TAG, "Automatic scan is disabled")
        return ScanInitResult(ScanStatus.SKIPPED)
    }

    val timeNow = LocalDateTime.now().toInstant(ZoneOffset.UTC).toEpochMilli()
    val lastLocalScan = context.dataStore.get(LastLocalScanKey, 0L)
    val scanDue = lastLocalScan + AUTO_SCAN_COOLDOWN <= timeNow
    val missingPermission = localLibEnable &&
        context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED
    // 旧版本可能在申请权限前已写冷却，缺权限与授权补扫不能被该时间戳拦住。
    if (trigger == ScanTrigger.STARTUP && !scanDue && !missingPermission) {
        Log.i(MAIN_TAG, "Aborting automatic scan. Not enough time has passed since the last scan")
        downloadUtil.resumeDownloadsOnStart()
        return ScanInitResult(ScanStatus.SKIPPED)
    }

    var localScanAttempted = false
    fun showMessage(message: String) {
        coroutineScope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                withDismissAction = true,
                duration = SnackbarDuration.Short,
            )
        }
    }

    try {
        // scan download folders：授权补扫只处理本地媒体，不重复扫描下载目录。
        if (trigger == ScanTrigger.STARTUP) {
            if (scanDue) downloadUtil.scanDownloads()
            downloadUtil.resumeDownloadsOnStart()
        }
        if (!localLibEnable) {
            context.dataStore.edit { settings -> settings[LastLocalScanKey] = timeNow }
            withContext(Dispatchers.Main) { playerConnection?.service?.initQueue() }
            Log.i(MAIN_TAG, "Downloads scan completed. Local media is disabled.")
            showMessage(context.getString(R.string.scanner_auto_end))
            return ScanInitResult(ScanStatus.COMPLETED)
        }

        // Check if the permissions for local media access
        if (context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED) {
            Log.w(MAIN_TAG, "Not enough permission to perform local media scan")
            return ScanInitResult(ScanStatus.WAITING_PERMISSION)
        }

        // auto scanner
        val scannerSensitivity by enumPreference(
            context = context,
            key = ScannerSensitivityKey,
            defaultValue = ScannerMatchCriteria.LEVEL_2,
        )
        val scannerImpl by enumPreference(
            context = context,
            key = ScannerImplKey,
            defaultValue = ScannerImpl.TAGLIB,
        )
        val scanPaths = context.dataStore.get(ScanPathsKey, defaultValue = "")
        val excludedScanPaths = context.dataStore.get(ExcludedScanPathsKey, defaultValue = "")
        val strictExtensions = context.dataStore.get(ScannerStrictExtKey, defaultValue = false)
        val strictFilePaths = context.dataStore.get(ScannerStrictFilePathsKey, defaultValue = false)

        // local media scan
        val result = withLocalMediaScanner(context, scannerImpl) { scanner ->
            if (context.checkSelfPermission(MEDIA_PERMISSION_LEVEL) != PackageManager.PERMISSION_GRANTED) {
                return@withLocalMediaScanner ScanInitResult(ScanStatus.WAITING_PERMISSION)
            }
            currentCoroutineContext().ensureActive()
            localScanAttempted = true
            context.dataStore.edit { settings ->
                settings[LastLocalScanKey] =
                    timeNow - AUTO_SCAN_COOLDOWN + AUTO_SCAN_SOFT_COOLDOWN // min cooldown to avoid crash loops
            }
            Log.i(MAIN_TAG, "Starting local media auto scan")
            showMessage(context.getString(R.string.scanner_auto_start))
            withContext(Dispatchers.Main) { playerConnection?.player?.pause() }
            if (scannerImpl == ScannerImpl.MEDIASTORE) {
                scanner.fullMediaStoreSync(
                    database = database,
                    scanPaths = uriListFromString(scanPaths),
                    excludedScanPaths = uriListFromString(excludedScanPaths),
                    matchCriteria = scannerSensitivity,
                    strictFileNames = strictExtensions,
                    strictFilePaths = strictFilePaths,
                    refreshExisting = false,
                )
            } else {
                // equivalent to (quick scan)
                val uris = scanner.scanLocal(scanPaths, excludedScanPaths)
                scanner.quickSync(database, uris, scannerSensitivity, strictExtensions, strictFilePaths)
            }
            currentCoroutineContext().ensureActive()
            ScanInitResult(ScanStatus.COMPLETED, localScanAttempted = true)
        }
        if (result.status == ScanStatus.WAITING_PERMISSION) return result

        // post scan actions
        currentCoroutineContext().ensureActive()
        context.dataStore.edit { settings -> settings[LastLocalScanKey] = timeNow }
        withContext(Dispatchers.Main) { playerConnection?.service?.initQueue() }
        Log.i(MAIN_TAG, "Local media and downloads scan completed")
        showMessage(context.getString(R.string.scanner_auto_end))
        return result
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        showMessage("${context.getString(R.string.scanner_scan_fail)}: ${e.message}")
        reportException(e)
        return ScanInitResult(ScanStatus.FAILED, localScanAttempted)
    }
}
