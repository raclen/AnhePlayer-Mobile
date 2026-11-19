package com.dd3boh.outertune.ui.component

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
 * 播放失败弹窗，保留音源帮助入口，并允许用户关闭当日提醒。
 */
@Composable
fun PlaybackErrorDialog(
    info: PlaybackErrorDialogInfo,
    onDismiss: () -> Unit,
    onDismissForToday: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboard.current

    val openUrlAction = {
        try {
            uriHandler.openUri(info.sourceUrl)
        } catch (e: Exception) {
            val clipData = ClipData.newPlainText("sources_url", AnnotatedString(info.sourceUrl))
            clipboardManager.nativeClipboard.setPrimaryClip(clipData)
            Toast.makeText(context, "无法打开浏览器，已复制音源链接到剪贴板", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = if (info.isLocal) "本地播放失败" else "当前平台播放失败",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!info.songTitle.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(14.dp),
                        ) {
                            Text(
                                text = info.songTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (!info.artistName.isNullOrBlank()) {
                                Text(
                                    text = info.artistName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }

                if (info.isLocal) {
                    Text(
                        text = "本地音频文件无法读取或已损坏，请检查文件是否存在。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val platformDesc = if (info.platformName.isNotBlank() && info.platformName != "当前平台") {
                        info.platformName
                    } else {
                        "当前平台"
                    }

                    Text(
                        text = "${platformDesc}暂时无法播放，可能是音源失效、平台更新或版权限制。",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (!info.detailMessage.isNullOrBlank()) {
                        Text(
                            text = info.detailMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "可前往音源网站寻找并配置其他音源。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "音源帮助网站",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .clickable {
                                    openUrlAction()
                                    onDismiss()
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }

                Text(
                    text = "今日关闭后，明天自动恢复提醒。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onDismiss) {
                Text("知道了")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissForToday) {
                Text("今日关闭")
            }
        },
    )
}
