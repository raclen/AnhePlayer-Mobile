package com.dd3boh.outertune.ui.screens.online

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dd3boh.outertune.viewmodels.QrUiState

@Composable
fun NeteaseLoginDialog(
    qrState: QrUiState,
    onDismiss: () -> Unit,
    onRefreshQr: () -> Unit,
    onSubmitCookie: (String, (Boolean, String?) -> Unit) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var cookieInput by remember { mutableStateOf("") }
    var cookieError by remember { mutableStateOf<String?>(null) }
    var isSubmittingCookie by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "网易云音乐账号登录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "关闭")
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Tabs: 扫码登录 / 网页登录 / Cookie 登录
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("扫码登录") },
                        icon = { Icon(Icons.Rounded.QrCodeScanner, null, Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("网页登录") },
                        icon = { Icon(Icons.Rounded.Language, null, Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Cookie") },
                        icon = { Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp)) }
                    )
                }

                Spacer(Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> QrLoginTabContent(
                        qrState = qrState,
                        onRefreshQr = onRefreshQr
                    )
                    1 -> WebLoginTabContent(
                        onCookieCaptured = { capturedCookie ->
                            onSubmitCookie(capturedCookie) { _, _ -> }
                        }
                    )
                    2 -> CookieLoginTabContent(
                        cookieInput = cookieInput,
                        onCookieChange = {
                            cookieInput = it
                            cookieError = null
                        },
                        cookieError = cookieError,
                        isSubmitting = isSubmittingCookie,
                        onPaste = {
                            clipboardManager.getText()?.text?.let {
                                cookieInput = it
                                cookieError = null
                            }
                        },
                        onSubmit = {
                            if (cookieInput.isBlank()) {
                                cookieError = "请输入 Cookie 或 MUSIC_U 凭证"
                                return@CookieLoginTabContent
                            }
                            isSubmittingCookie = true
                            cookieError = null
                            onSubmitCookie(cookieInput) { success, err ->
                                isSubmittingCookie = false
                                if (!success) {
                                    cookieError = err ?: "凭据验证失败，请检查是否完整"
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QrLoginTabContent(
    qrState: QrUiState,
    onRefreshQr: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(220.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (qrState.isLoading) {
                CircularProgressIndicator()
            } else if (qrState.qrBitmap != null) {
                Image(
                    bitmap = qrState.qrBitmap.asImageBitmap(),
                    contentDescription = "登录二维码",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                )

                if (qrState.isExpired) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.72f))
                            .clickable(onClick = onRefreshQr),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "二维码已过期\n点击刷新",
                                color = MaterialTheme.colorScheme.onPrimary,
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            } else if (qrState.error != null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = qrState.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRefreshQr) {
                        Text("重试生成")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = qrState.statusText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (qrState.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "支持手机网易云音乐扫码快速授权",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebLoginTabContent(
    onCookieCaptured: (String) -> Unit
) {
    var isCaptured by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
    ) {
        Text(
            text = "在网页中完成登录后，将自动识别凭证并完成授权",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                if (url != null && !isCaptured) {
                                    val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
                                    if (cookie.contains("MUSIC_U=")) {
                                        isCaptured = true
                                        onCookieCaptured(cookie)
                                    }
                                }
                            }
                        }
                        loadUrl("https://music.163.com/#/login")
                    }
                }
            )

            if (isCaptured) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "检测到登录凭证，正在完成同步…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CookieLoginTabContent(
    cookieInput: String,
    onCookieChange: (String) -> Unit,
    cookieError: String?,
    isSubmitting: Boolean,
    onPaste: () -> Unit,
    onSubmit: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OutlinedTextField(
            value = cookieInput,
            onValueChange = onCookieChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(130.dp),
            placeholder = {
                Text(
                    text = "在此粘贴包含 MUSIC_U 的网易云 Cookie 字符串...",
                    style = MaterialTheme.typography.bodySmall
                )
            },
            shape = RoundedCornerShape(12.dp),
            isError = cookieError != null
        )

        AnimatedVisibility(visible = cookieError != null) {
            Text(
                text = cookieError.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onPaste,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("粘贴剪贴板")
            }

            Button(
                onClick = onSubmit,
                enabled = !isSubmitting,
                modifier = Modifier.weight(1f)
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("确认登录")
                }
            }
        }
    }
}
