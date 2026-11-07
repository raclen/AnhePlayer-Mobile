package com.dd3boh.outertune.ui.component

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dd3boh.outertune.R
import com.dd3boh.outertune.updates.AppUpdateCheckResult
import com.dd3boh.outertune.updates.AppUpdateDownloadState
import com.dd3boh.outertune.updates.AppUpdateInstallResult
import com.dd3boh.outertune.viewmodels.AppUpdateViewModel
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun AppUpdateCard(
    viewModel: AppUpdateViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var manualCheckRunning by remember { mutableStateOf(false) }
    var actionRunning by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }
    val release = state.release
    val downloading = downloadState as? AppUpdateDownloadState.Downloading
    val ready = downloadState as? AppUpdateDownloadState.Ready
    val displayedRelease = downloading?.release ?: ready?.release ?: release

    suspend fun installUpdate(openPermission: (android.content.Intent) -> Unit) {
        actionRunning = true
        feedback = null
        try {
            when (val result = viewModel.prepareInstall()) {
                is AppUpdateInstallResult.PermissionRequired -> {
                    feedbackIsError = false
                    feedback = context.getString(R.string.app_update_install_permission_hint)
                    openPermission(result.intent)
                }
                is AppUpdateInstallResult.Installer -> {
                    context.startActivity(result.intent)
                    feedbackIsError = false
                    feedback = context.getString(R.string.app_update_installer_opened)
                }
                is AppUpdateInstallResult.Failed -> {
                    feedbackIsError = true
                    feedback = result.message
                }
            }
        } catch (_: android.content.ActivityNotFoundException) {
            feedbackIsError = true
            feedback = context.getString(R.string.app_update_install_open_failed)
        } catch (_: SecurityException) {
            feedbackIsError = true
            feedback = context.getString(R.string.app_update_install_open_failed)
        } finally {
            actionRunning = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        coroutineScope.launch {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
                // 授权页不保证返回 RESULT_OK，因此必须重新读取实际权限。
                installUpdate { }
            } else {
                feedbackIsError = true
                feedback = context.getString(R.string.app_update_install_permission_denied)
            }
        }
    }

    // 卡片位于关于页顶部；只在页面前台可见时清除该版本的未读红点。
    LaunchedEffect(release?.version, lifecycleState) {
        if (release != null && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
            viewModel.markViewed(release.version)
        }
    }

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (displayedRelease != null) {
                    stringResource(R.string.app_update_found, displayedRelease.version)
                } else {
                    stringResource(R.string.app_update_title)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = displayedRelease?.displayNotes?.ifBlank { stringResource(R.string.app_update_notes_empty) }
                    ?: stringResource(
                        if (state.lastSuccessAt > 0) R.string.app_update_no_new_version
                        else R.string.app_update_auto_hint
                    ),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.lastSuccessAt > 0) {
                val checkedAt = remember(state.lastSuccessAt) {
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(state.lastSuccessAt))
                }
                Text(
                    text = stringResource(R.string.app_update_last_checked, checkedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloading != null) {
                val progress = (downloading.downloadedBytes.toFloat() / downloading.totalBytes).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = stringResource(
                        R.string.app_update_download_progress,
                        (progress * 100).toInt(),
                        android.text.format.Formatter.formatFileSize(context, downloading.downloadedBytes),
                        android.text.format.Formatter.formatFileSize(context, downloading.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = stringResource(if (downloading.waitingForNetwork) R.string.app_update_download_waiting else R.string.app_update_download_background),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (ready != null) {
                Text(stringResource(R.string.app_update_download_ready), style = MaterialTheme.typography.bodySmall)
            }
            (downloadState as? AppUpdateDownloadState.Failed)?.let {
                Text(it.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            feedback?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (feedbackIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (downloading != null) {
                    TextButton(enabled = !actionRunning, onClick = {
                        coroutineScope.launch {
                            actionRunning = true
                            try { viewModel.cancelDownload() } finally { actionRunning = false }
                        }
                    }) { Text(stringResource(R.string.app_update_cancel_download)) }
                } else if (ready != null) {
                    Button(enabled = !actionRunning, onClick = {
                        coroutineScope.launch { installUpdate { permissionLauncher.launch(it) } }
                    }) { Text(stringResource(if (actionRunning) R.string.app_update_preparing_install else R.string.app_update_install)) }
                    TextButton(enabled = !actionRunning, onClick = {
                        coroutineScope.launch {
                            actionRunning = true
                            try { viewModel.cancelDownload() } finally { actionRunning = false }
                        }
                    }) { Text(stringResource(R.string.app_update_discard_download)) }
                } else if (release != null) {
                    Button(enabled = !actionRunning && !state.isChecking && !manualCheckRunning, onClick = {
                        coroutineScope.launch {
                            actionRunning = true
                            feedback = null
                            try {
                                viewModel.downloadUpdate(release)?.let {
                                    feedbackIsError = true
                                    feedback = it
                                }
                            } finally {
                                actionRunning = false
                            }
                        }
                    }) {
                        Text(stringResource(
                            if (actionRunning) R.string.app_update_starting_download
                            else if (downloadState is AppUpdateDownloadState.Failed) R.string.app_update_retry_download
                            else R.string.app_update_download
                        ))
                    }
                }
                TextButton(
                    enabled = state.isLoaded && !state.isChecking && !manualCheckRunning && !actionRunning,
                    onClick = {
                        manualCheckRunning = true
                        feedback = null
                        coroutineScope.launch {
                            try {
                                val result = viewModel.checkManually()
                                feedbackIsError = result is AppUpdateCheckResult.Failed
                                feedback = when (result) {
                                    is AppUpdateCheckResult.Available -> context.getString(R.string.app_update_found, result.release.version)
                                    AppUpdateCheckResult.UpToDate -> context.getString(R.string.app_update_no_new_version)
                                    is AppUpdateCheckResult.Failed -> result.message
                                    AppUpdateCheckResult.Skipped -> context.getString(R.string.checking_for_update)
                                }
                            } finally {
                                manualCheckRunning = false
                            }
                        }
                    },
                ) {
                    Text(stringResource(
                        if (state.isChecking || manualCheckRunning) R.string.app_update_checking
                        else R.string.check_for_update
                    ))
                }
            }
        }
    }
}
