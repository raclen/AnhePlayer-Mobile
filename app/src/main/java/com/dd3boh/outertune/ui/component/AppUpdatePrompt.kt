package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.updates.AppRelease
import com.dd3boh.outertune.updates.AppUpdateState
import com.dd3boh.outertune.viewmodels.AppUpdateViewModel
import kotlinx.coroutines.delay

@Composable
fun AppUpdatePrompt(
    state: AppUpdateState,
    viewModel: AppUpdateViewModel,
    canShow: Boolean,
    onViewUpdate: () -> Unit,
) {
    var promptedRelease by remember { mutableStateOf<AppRelease?>(null) }

    LaunchedEffect(canShow, state.isLoaded, state.release?.version) {
        if (!canShow || !state.isLoaded) return@LaunchedEffect
        // 首页稳定后再提醒；进入播放器、打开菜单或申请权限时取消等待。
        delay(1_200)
        val currentState = viewModel.state.value
        val release = currentState.release ?: return@LaunchedEffect
        if (currentState.needsPrompt && viewModel.claimPrompt(release.version)) {
            promptedRelease = release
        }
    }

    // 弹窗自身会夺走 Activity 的窗口焦点，因此焦点只用于决定何时首次展示。
    promptedRelease?.let { release ->
        AlertDialog(
            onDismissRequest = { promptedRelease = null },
            title = { Text(stringResource(R.string.app_update_found, release.version)) },
            text = {
                Column {
                    Text(
                        text = release.displayNotes.ifBlank { stringResource(R.string.app_update_notes_empty) },
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.app_update_prompt_hint))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    promptedRelease = null
                    onViewUpdate()
                }) {
                    Text(stringResource(R.string.app_update_view))
                }
            },
            dismissButton = {
                TextButton(onClick = { promptedRelease = null }) {
                    Text(stringResource(R.string.app_update_later))
                }
            },
        )
    }
}
