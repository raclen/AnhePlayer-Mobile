package com.dd3boh.outertune.updates

import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.dd3boh.outertune.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState
    data class Downloading(
        val release: AppRelease,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val waitingForNetwork: Boolean,
    ) : AppUpdateDownloadState
    data class Ready(val release: AppRelease) : AppUpdateDownloadState
    data class Failed(val message: String) : AppUpdateDownloadState
}

sealed interface AppUpdateInstallResult {
    data class PermissionRequired(val intent: Intent) : AppUpdateInstallResult
    data class Installer(val intent: Intent) : AppUpdateInstallResult
    data class Failed(val message: String) : AppUpdateInstallResult
}

/** 系统下载管理器负责后台下载和网络重试，页面退出、进程重建均不丢失任务。 */
@Singleton
class AppUpdateDownloader @Inject constructor(@ApplicationContext private val context: Context) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val preferences = context.getSharedPreferences("app_update_download", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var failure: String? = null
    private var verifiedId: Long? = null
    private var verificationFailure: String? = null

    // 没有更新卡片订阅时不轮询；下载本身由系统继续执行。
    val state = flow {
        while (true) {
            val current = mutex.withLock {
                try {
                    readState()
                } catch (_: Exception) {
                    AppUpdateDownloadState.Failed("无法读取更新下载任务，请重试")
                }
            }
            emit(current)
            delay(1_000)
        }
    }.flowOn(Dispatchers.IO).stateIn(scope, SharingStarted.WhileSubscribed(5_000), AppUpdateDownloadState.Idle)

    suspend fun download(release: AppRelease) = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val current = readState()
                if (current is AppUpdateDownloadState.Downloading) return@withLock
                if (current is AppUpdateDownloadState.Ready && current.release == release) return@withLock
                // 再校验缓存中的 URL 与 flavor，绝不把外部任意地址交给安装链路。
                val validated = AppRelease.fromCache(release.toCache(), BuildConfig.FLAVOR)
                    ?: throw UpdateDownloadException("更新信息无效，请重新检查更新")
                if (!validated.isNewerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())) {
                    throw UpdateDownloadException("当前已是该版本，请重新检查更新")
                }
                val asset = validated.downloadAsset(Build.SUPPORTED_ABIS.toList())
                    ?: throw UpdateDownloadException("暂未提供适合此设备架构的安装包，请稍后重新检查更新")
                clearTask()
                val file = destination(asset)
                if (file.exists() && !file.delete()) throw UpdateDownloadException("无法清理旧安装包，请重试")
                val request = DownloadManager.Request(Uri.parse(asset.downloadUrl))
                    .setTitle("AnhePlayer v${validated.version}")
                    .setDescription("应用更新 · ${asset.abi}")
                    .setMimeType(APK_MIME_TYPE)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .addRequestHeader("User-Agent", "AnhePlayer-Mobile")
                    .setDestinationUri(Uri.fromFile(file))
                val id = manager.enqueue(request)
                if (!preferences.edit().putLong("id", id).putString("release", validated.toCache()).commit()) {
                    manager.remove(id)
                    throw UpdateDownloadException("无法保存下载任务，请重试")
                }
                failure = null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failure = (error as? UpdateDownloadException)?.message ?: "无法开始下载，请检查系统下载管理器和存储空间"
            }
        }
    }

    suspend fun cancel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                clearTask()
                failure = null
            } catch (_: Exception) {
                failure = "无法取消更新下载，请重试"
            }
        }
    }

    suspend fun prepareInstall(): AppUpdateInstallResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                val ready = readState() as? AppUpdateDownloadState.Ready
                    ?: throw UpdateDownloadException("安装包尚未就绪，请完成下载后重试")
                val asset = ready.release.downloadAsset(Build.SUPPORTED_ABIS.toList())
                    ?: throw UpdateDownloadException("安装包架构不匹配，请重新下载")
                val file = destination(asset)
                // 安装前再次检查，不能只依赖页面展示过的就绪状态。
                UpdateApkValidator.validate(context, file, ready.release, asset)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                    return@withLock AppUpdateInstallResult.PermissionRequired(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    )
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.UpdateFileProvider", file)
                AppUpdateInstallResult.Installer(
                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME_TYPE)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .apply { clipData = ClipData.newRawUri("应用更新", uri) }
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppUpdateInstallResult.Failed((error as? UpdateDownloadException)?.message ?: "无法准备安装包，请重新下载")
            }
        }
    }

    private fun readState(): AppUpdateDownloadState {
        failure?.let { return AppUpdateDownloadState.Failed(it) }
        val id = preferences.getLong("id", -1)
        if (id < 0) return AppUpdateDownloadState.Idle
        val release = preferences.getString("release", null)?.let { AppRelease.fromCache(it, BuildConfig.FLAVOR) }
            ?: return AppUpdateDownloadState.Failed("下载记录已失效，请重新下载")
        if (!release.isNewerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())) {
            clearTask()
            return AppUpdateDownloadState.Idle
        }
        val asset = release.downloadAsset(Build.SUPPORTED_ABIS.toList())
            ?: return AppUpdateDownloadState.Failed("下载记录缺少适合此设备的安装包，请重新下载")
        manager.query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return AppUpdateDownloadState.Failed("下载任务已被移除，请重新下载")
            return when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (verifiedId != id) {
                        verificationFailure = try {
                            UpdateApkValidator.validate(context, destination(asset), release, asset)
                            null
                        } catch (error: Exception) {
                            (error as? UpdateDownloadException)?.message ?: "安装包校验失败，请重新下载"
                        }
                        verifiedId = id
                    }
                    verificationFailure?.let { AppUpdateDownloadState.Failed(it) }
                        ?: if (destination(asset).isFile) AppUpdateDownloadState.Ready(release)
                        else AppUpdateDownloadState.Failed("安装包已被清理，请重新下载")
                }
                DownloadManager.STATUS_FAILED -> AppUpdateDownloadState.Failed(
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))) {
                        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "存储空间不足，请清理后重新下载"
                        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "下载存储不可用，请重试"
                        else -> "更新下载失败，请检查网络后重试"
                    }
                )
                DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> AppUpdateDownloadState.Downloading(
                    release = release,
                    downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0),
                    totalBytes = asset.size,
                    waitingForNetwork = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_PAUSED,
                )
                else -> AppUpdateDownloadState.Failed("下载任务状态异常，请重新下载")
            }
        }
    }

    private fun destination(asset: AppReleaseAsset): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw UpdateDownloadException("下载存储不可用，请检查存储空间")
        val directory = File(base, "app-updates")
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateDownloadException("无法创建更新下载目录")
        return File(directory, asset.name)
    }

    private fun clearTask() {
        val id = preferences.getLong("id", -1)
        if (id >= 0) manager.remove(id)
        if (!preferences.edit().clear().commit()) throw UpdateDownloadException("无法清理下载记录，请重试")
        verifiedId = null
        verificationFailure = null
    }

    private companion object {
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    }
}
