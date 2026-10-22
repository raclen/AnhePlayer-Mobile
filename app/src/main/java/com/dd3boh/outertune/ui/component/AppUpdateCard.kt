package com.dd3boh.outertune.ui.component

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dd3boh.outertune.R
import com.dd3boh.outertune.updates.AppUpdateCheckResult
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
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    var manualCheckRunning by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var feedbackIsError by remember { mutableStateOf(false) }
    val release = state.release

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
                text = if (release != null) {
                    stringResource(R.string.app_update_found, release.version)
                } else {
                    stringResource(R.string.app_update_title)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = release?.displayNotes?.ifBlank { stringResource(R.string.app_update_notes_empty) }
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
            feedback?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (feedbackIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (release != null) {
                    Button(onClick = {
                        try {
                            uriHandler.openUri(release.releaseUrl)
                        } catch (_: Exception) {
                            Toast.makeText(context, R.string.app_update_open_failed, Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text(stringResource(R.string.app_update_view))
                    }
                }
                TextButton(
                    enabled = state.isLoaded && !state.isChecking && !manualCheckRunning,
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
