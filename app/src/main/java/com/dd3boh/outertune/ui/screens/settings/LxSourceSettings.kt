package com.dd3boh.outertune.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.constants.LxSourceNameKey
import com.dd3boh.outertune.constants.LxSourceQualityKey
import com.dd3boh.outertune.constants.LxSourceScriptKey
import com.dd3boh.outertune.constants.DownloadQualityKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.source.lx.LxSourceInstaller
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LxSourceSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val coroutineScope = rememberCoroutineScope()
    val (_, onSourceScriptChange) = rememberPreference(LxSourceScriptKey, "")
    val (sourceName, onSourceNameChange) = rememberPreference(LxSourceNameKey, "")
    val (quality, onQualityChange) = rememberPreference(LxSourceQualityKey, "320k")
    val (downloadQuality, onDownloadQualityChange) = rememberPreference(DownloadQualityKey, "320k")
    var showUrlDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val script = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (!script.isNullOrBlank()) {
                onSourceScriptChange(script)
                onSourceNameChange(resolveDisplayName(context, uri))
            }
        }
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
    ) {
        PreferenceEntry(
            title = { Text("导入本地音源脚本") },
            description = if (sourceName.isNotBlank()) "当前音源：$sourceName" else "尚未导入音源，点击选择本地 .js 脚本",
            icon = { Icon(Icons.Rounded.AudioFile, null) },
            onClick = { launcher.launch(arrayOf("application/javascript", "text/javascript", "text/plain", "*/*")) },
        )
        PreferenceEntry(
            title = { Text("从在线链接导入") },
            description = if (isDownloading) "正在下载音源脚本…" else "粘贴指向 .js 音源脚本的 http/https 链接",
            icon = { Icon(Icons.Rounded.Link, null) },
            onClick = { if (!isDownloading) showUrlDialog = true },
        )
        ListPreference(
            title = { Text("默认音质") },
            icon = { Icon(Icons.Rounded.HighQuality, null) },
            selectedValue = quality,
            values = listOf("128k", "320k", "flac", "flac24bit"),
            valueText = { it },
            onValueSelected = onQualityChange,
            isEnabled = true,
        )
        ListPreference(
            title = { Text("下载音质") },
            icon = { Icon(Icons.Rounded.HighQuality, null) },
            selectedValue = downloadQuality,
            values = listOf("128k", "320k", "flac", "flac24bit"),
            valueText = {
                when (it) {
                    "128k" -> "低品质（128k / MP3）"
                    "320k" -> "标准（320k / MP3）"
                    "flac" -> "高品质（FLAC）"
                    "flac24bit" -> "无损 Hi-Res（FLAC 24bit）"
                    else -> it
                }
            },
            onValueSelected = onDownloadQualityChange,
            isEnabled = true,
        )
    }

    if (showUrlDialog) {
        TextFieldDialog(
            icon = { Icon(Icons.Rounded.Link, null) },
            title = { Text("从在线链接导入") },
            placeholder = { Text("https://example.com/lx-source.js") },
            keyboardType = KeyboardType.Uri,
            isInputValid = { it.isNotBlank() },
            onDone = { url ->
                coroutineScope.launch {
                    isDownloading = true
                    LxSourceInstaller.download(url)
                        .onSuccess { imported ->
                            onSourceScriptChange(imported.script)
                            onSourceNameChange(imported.name)
                            snackbarHostState.showSnackbar("音源已导入：${imported.name}")
                        }
                        .onFailure { error ->
                            snackbarHostState.showSnackbar("导入失败：${error.message ?: "未知错误"}")
                        }
                    isDownloading = false
                }
            },
            onDismiss = { showUrlDialog = false },
        )
    }

    TopAppBar(
        title = { Text("洛雪音源") },
        navigationIcon = {
            IconButton(onClick = navController::navigateUp, onLongClick = navController::backToMain) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null)
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}

/** 从 SAF 取用户看到的文件名，而不是 document:xxx 这种内部 id */
private fun resolveDisplayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "洛雪自定义音源"
}
