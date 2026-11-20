package com.dd3boh.outertune.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.constants.DownloadQualityKey
import com.dd3boh.outertune.constants.LxActiveSourceIdKey
import com.dd3boh.outertune.constants.LxSourceListKey
import com.dd3boh.outertune.constants.LxSourceNameKey
import com.dd3boh.outertune.constants.LxSourceQualityKey
import com.dd3boh.outertune.constants.LxSourceScriptKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.source.lx.LxSourceInstaller
import com.dd3boh.outertune.source.lx.LxSourceItem
import com.dd3boh.outertune.source.lx.LxSourceNameResolver
import com.dd3boh.outertune.source.lx.lxJson
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.dialog.TextFieldDialog
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.rememberPreference
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LxSourceSettings(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val coroutineScope = rememberCoroutineScope()

    val (sourceListJson, onSourceListChange) = rememberPreference(LxSourceListKey, "")
    val (activeSourceId, onActiveSourceIdChange) = rememberPreference(LxActiveSourceIdKey, "")
    val (legacyScript, onLegacyScriptChange) = rememberPreference(LxSourceScriptKey, "")
    val (legacyName, onLegacyNameChange) = rememberPreference(LxSourceNameKey, "")
    val (quality, onQualityChange) = rememberPreference(LxSourceQualityKey, "320k")
    val (downloadQuality, onDownloadQualityChange) = rememberPreference(DownloadQualityKey, "320k")

    var showUrlDialog by remember { mutableStateOf(false) }
    var isDownloading by remember { mutableStateOf(false) }

    // 内存反序列化多音源列表，兼容旧的单个音源自动迁移
    val sourceList = remember(sourceListJson, legacyScript, legacyName) {
        if (sourceListJson.isNotBlank()) {
            runCatching {
                lxJson.decodeFromString<List<LxSourceItem>>(sourceListJson)
            }.getOrDefault(emptyList())
        } else if (legacyScript.isNotBlank()) {
            val name = legacyName.takeIf { it.isNotBlank() }
                ?: LxSourceNameResolver.resolve(legacyScript)
            listOf(
                LxSourceItem(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    script = legacyScript,
                    version = LxSourceNameResolver.parseMeta(legacyScript).version,
                )
            )
        } else {
            emptyList()
        }
    }

    // 首次迁移旧音源持久化
    LaunchedEffect(sourceList) {
        if (sourceListJson.isBlank() && sourceList.isNotEmpty()) {
            onSourceListChange(lxJson.encodeToString(sourceList))
            if (activeSourceId.isBlank()) {
                onActiveSourceIdChange(sourceList.first().id)
            }
        }
    }

    // 保存/追加新音源
    val addOrUpdateSource: (LxSourceItem) -> Unit = { newItem ->
        val updated = sourceList.filter { it.script != newItem.script }.toMutableList()
        updated.add(0, newItem)
        val serialized = lxJson.encodeToString(updated)
        onSourceListChange(serialized)
        onActiveSourceIdChange(newItem.id)
        onLegacyScriptChange(newItem.script)
        onLegacyNameChange(newItem.name)
        coroutineScope.launch {
            snackbarHostState.showSnackbar("已导入并启用音源：${newItem.name}")
        }
    }

    // 切换当前激活音源
    val switchActiveSource: (LxSourceItem) -> Unit = { item ->
        onActiveSourceIdChange(item.id)
        onLegacyScriptChange(item.script)
        onLegacyNameChange(item.name)
        coroutineScope.launch {
            snackbarHostState.showSnackbar("已切换为当前音源：${item.name}")
        }
    }

    // 删除音源
    val deleteSource: (LxSourceItem) -> Unit = { item ->
        val updated = sourceList.filter { it.id != item.id }
        onSourceListChange(lxJson.encodeToString(updated))
        if (activeSourceId == item.id) {
            val nextActive = updated.firstOrNull()
            if (nextActive != null) {
                onActiveSourceIdChange(nextActive.id)
                onLegacyScriptChange(nextActive.script)
                onLegacyNameChange(nextActive.name)
            } else {
                onActiveSourceIdChange("")
                onLegacyScriptChange("")
                onLegacyNameChange("")
            }
        }
        coroutineScope.launch {
            snackbarHostState.showSnackbar("已移除音源：${item.name}")
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val script = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (!script.isNullOrBlank()) {
                val resolvedName = LxSourceNameResolver.resolve(script, uri.lastPathSegment.orEmpty())
                val meta = LxSourceNameResolver.parseMeta(script)
                val newItem = LxSourceItem(
                    id = UUID.randomUUID().toString(),
                    name = resolvedName,
                    script = script,
                    version = meta.version,
                )
                addOrUpdateSource(newItem)
            }
        }
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        PreferenceGroupTitle(title = "导入音源")

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            PreferenceEntry(
                title = { Text("导入本地音源脚本") },
                description = "选择本地 .js 脚本，名称将自动与检测页面保持一致",
                icon = { Icon(Icons.Rounded.AudioFile, null) },
                onClick = { launcher.launch(arrayOf("application/javascript", "text/javascript", "text/plain", "*/*")) },
            )
            PreferenceEntry(
                title = { Text("从在线链接导入") },
                description = if (isDownloading) "正在下载音源脚本…" else "粘贴指向 .js 音源脚本的 http/https 链接",
                icon = { Icon(Icons.Rounded.Link, null) },
                onClick = { if (!isDownloading) showUrlDialog = true },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        PreferenceGroupTitle(title = "已导入音源 (${sourceList.size})")

        if (sourceList.isEmpty()) {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "尚未导入音源，请通过上方入口导入本地或在线音源脚本",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                sourceList.forEachIndexed { index, item ->
                    val isActive = item.id == activeSourceId || (activeSourceId.isBlank() && index == 0)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (!isActive) switchActiveSource(item) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )

                                if (isActive) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        Text(
                                            text = "使用中",
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }

                            if (!item.version.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "版本：v${item.version.removePrefix("v").removePrefix("V")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // 切换按钮
                        if (!isActive) {
                            FilledTonalButton(
                                onClick = { switchActiveSource(item) },
                                modifier = Modifier.height(34.dp),
                                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                            ) {
                                Icon(
                                    Icons.Rounded.SwapHoriz,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("切换", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        // 删除按钮
                        IconButton(
                            onClick = { deleteSource(item) },
                        ) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = "删除",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    if (index < sourceList.size - 1) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        PreferenceGroupTitle(title = "音质偏好")

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
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
                            val newItem = LxSourceItem(
                                id = UUID.randomUUID().toString(),
                                name = imported.name,
                                script = imported.script,
                                version = imported.version,
                                url = imported.url,
                            )
                            addOrUpdateSource(newItem)
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
