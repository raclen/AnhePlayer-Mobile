package com.dd3boh.outertune.ui.component

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.models.PlaybackErrorDialogInfo

/**
 * 播放失败弹窗，引导用户前往音源平台寻找并配置合适音源
 */
@Composable
fun PlaybackErrorDialog(
    info: PlaybackErrorDialogInfo,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSourcesUrl: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboard.current

    val openUrlAction = {
        if (onOpenSourcesUrl != null) {
            onOpenSourcesUrl()
        } else {
            try {
                uriHandler.openUri(info.sourceUrl)
            } catch (e: Exception) {
                val clipData = ClipData.newPlainText("sources_url", AnnotatedString(info.sourceUrl))
                clipboardManager.nativeClipboard.setPrimaryClip(clipData)
                Toast.makeText(context, "无法打开浏览器，已复制音源链接到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = {
            Text(
                text = if (info.isLocal) "本地播放失败" else "当前平台播放失败",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!info.songTitle.isNullOrBlank()) {
                    val songDesc = buildString {
                        append("歌曲：《${info.songTitle}》")
                        if (!info.artistName.isNullOrBlank()) {
                            append(" - ${info.artistName}")
                        }
                    }
                    Text(
                        text = songDesc,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (info.isLocal) {
                    Text(
                        text = "本地音频文件无法读取或已损坏，请检查文件是否存在。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val platformDesc = if (info.platformName.isNotBlank() && info.platformName != "当前平台") {
                        "当前平台（${info.platformName}）"
                    } else {
                        "当前平台"
                    }

                    Text(
                        text = "${platformDesc}播放失败，可能是音源失效、版本更新或暂无可用版权。",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (!info.detailMessage.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = info.detailMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "建议前往音源网站寻找并配置合适音源：",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = info.sourceUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier
                            .clickable {
                                openUrlAction()
                                onDismiss()
                            }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            if (!info.isLocal) {
                TextButton(
                    onClick = {
                        openUrlAction()
                        onDismiss()
                    }
                ) {
                    Text("获取音源")
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("知道了")
                }
            }
        },
        dismissButton = {
            if (!info.isLocal) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            onDismiss()
                            onOpenSettings()
                        }
                    ) {
                        Text("音源设置")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("知道了")
                    }
                }
            }
        }
    )
}
