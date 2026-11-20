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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val configuration = LocalConfiguration.current
    val dialogWidth = (configuration.screenWidthDp.dp - 48.dp).coerceAtMost(340.dp)
    val dialogMaxHeight = (configuration.screenHeightDp.dp * 0.7f).coerceAtMost(360.dp)
    val message = when {
        info.isLocal -> "文件无法读取，请检查文件。"
        !info.detailMessage.isNullOrBlank() -> info.detailMessage
        else -> "请稍后重试，或更换音源。"
    }

    val openUrlAction = {
        try {
            uriHandler.openUri(info.sourceUrl)
        } catch (e: Exception) {
            val clipData = ClipData.newPlainText("sources_url", AnnotatedString(info.sourceUrl))
            clipboardManager.nativeClipboard.setPrimaryClip(clipData)
            Toast.makeText(context, "无法打开浏览器，链接已复制", Toast.LENGTH_SHORT).show()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .width(dialogWidth)
                .heightIn(max = dialogMaxHeight)
                .semantics { paneTitle = "播放失败" },
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "播放失败",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 18.sp,
                        lineHeight = 24.sp,
                    ),
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.height(12.dp))

                // 只让正文在空间不足时滚动，避免大字号撑满屏幕或挤走底部按钮。
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (!info.songTitle.isNullOrBlank()) {
                        Text(
                            text = info.songTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!info.artistName.isNullOrBlank()) {
                            Text(
                                text = info.artistName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!info.isLocal) {
                        Text(
                            text = "音源帮助",
                            style = MaterialTheme.typography.bodySmall,
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

                Spacer(Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextButton(
                        onClick = onDismissForToday,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("今日关闭")
                    }
                    FilledTonalButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("知道了")
                    }
                }
            }
        }
    }
}
