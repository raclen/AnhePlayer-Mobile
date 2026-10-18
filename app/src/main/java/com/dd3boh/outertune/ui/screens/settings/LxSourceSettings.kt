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
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.constants.LxSourceNameKey
import com.dd3boh.outertune.constants.LxSourceQualityKey
import com.dd3boh.outertune.constants.LxSourceScriptKey
import com.dd3boh.outertune.constants.DownloadQualityKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LxSourceSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val (sourceScript, onSourceScriptChange) = rememberPreference(LxSourceScriptKey, "")
    val (sourceName, onSourceNameChange) = rememberPreference(LxSourceNameKey, "")
    val (quality, onQualityChange) = rememberPreference(LxSourceQualityKey, "320k")
    val (downloadQuality, onDownloadQualityChange) = rememberPreference(DownloadQualityKey, "320k")
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
            title = { Text("导入洛雪自定义音源") },
            description = sourceName.ifBlank { "当前使用内置：长青SVIP音源 v1.2.0" },
            icon = { Icon(Icons.Rounded.AudioFile, null) },
            onClick = { launcher.launch(arrayOf("application/javascript", "text/javascript", "text/plain", "*/*")) },
        )
        if (sourceName.isNotBlank()) {
            PreferenceEntry(
                title = { Text("恢复内置音源") },
                description = "清除已导入的脚本，回到内置音源",
                icon = { Icon(Icons.Rounded.Restore, null) },
                onClick = {
                    onSourceScriptChange("")
                    onSourceNameChange("")
                },
            )
        }
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
