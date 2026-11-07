package com.dd3boh.outertune.ui.screens.kugou

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import com.dd3boh.outertune.source.kugou.KugouAccountStatus
import com.dd3boh.outertune.source.kugou.KugouClaimStatus
import com.dd3boh.outertune.source.kugou.KugouConcept
import com.dd3boh.outertune.viewmodels.KugouAccountViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KugouAccountScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: KugouAccountViewModel = hiltViewModel(),
) {
    val account by viewModel.accountState.collectAsState()
    val state by viewModel.state.collectAsState()
    val session by KugouConcept.sessionVersion.collectAsState()
    val listState = rememberLazyListState()
    var showLogin by remember { mutableStateOf(false) }
    var smsMode by remember { mutableStateOf(false) }
    // 手机号与验证码只存在页面内存，不进入 SavedStateHandle、DataStore 或搜索历史。
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    val signedIn = account.status == KugouAccountStatus.SIGNED_IN
    fun cancelAuthorization() {
        viewModel.cancelLogin()
        mobile = ""
        code = ""
    }
    fun dismissSelection() {
        viewModel.dismissAccountSelection()
        mobile = ""
        code = ""
    }
    CancelOnKugouPageStop { cancelAuthorization() }
    LaunchedEffect(session, account.status) {
        if (account.status == KugouAccountStatus.SIGNED_IN) {
            mobile = ""
            code = ""
            showLogin = false
        }
    }
    LaunchedEffect(showLogin) {
        // 授权表单紧跟账号摘要；从底部切换账号时主动定位，避免新表单出现在屏幕外。
        if (showLogin) listState.animateScrollToItem(1)
    }
    KugouScreenFrame("酷狗账号", navController, scrollBehavior) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = kugouContentPadding()) {
            item("account") {
                KugouCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Person, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary) }
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(if (signedIn) account.nickname.ifBlank { "酷狗账号" } else "登录酷狗账号",
                                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                account.userId?.let {
                                    Text("ID $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    when (account.status) {
                                        KugouAccountStatus.SIGNED_OUT -> "尚未登录"
                                        KugouAccountStatus.RESTORING -> "正在恢复账号"
                                        KugouAccountStatus.SIGNED_IN -> "账号已连接"
                                        KugouAccountStatus.EXPIRED -> "授权已过期，请重新登录"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (account.status == KugouAccountStatus.EXPIRED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("仅用于概念版在线播放，账号凭据在本机加密保存。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (account.status == KugouAccountStatus.RESTORING) KugouLoadingStatus("正在恢复本地加密会话…")
                    }
                }
                account.error?.let { KugouMessage(it, isError = true) }
            }
            if (!signedIn || showLogin) {
                item("login_title") {
                    KugouSectionTitle(if (showLogin && signedIn) "授权另一个账号" else "选择登录方式")
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        KugouSegmentedControl(listOf("扫码登录", "短信登录"), if (smsMode) 1 else 0, enabled = !state.busy) {
                            cancelAuthorization()
                            smsMode = it == 1
                        }
                    }
                }
                item("login_form") {
                    KugouCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (smsMode) {
                            Text(
                                "验证码仅通过安全通道发送；接口不支持安全短信时，请改用扫码授权。",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedTextField(
                                value = mobile, onValueChange = { mobile = it.filter { digit -> digit in '0'..'9' }.take(11) },
                                label = { Text("11 位手机号") }, singleLine = true,
                                leadingIcon = { Icon(Icons.Rounded.PhoneAndroid, null) },
                                supportingText = { Text("手机号仅保留在当前页面") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                enabled = !state.busy && state.accounts.isEmpty(), modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedBorderColor = Color.Transparent,
                                ),
                            )
                            OutlinedTextField(
                                value = code, onValueChange = { code = it.filter { digit -> digit in '0'..'9' }.take(8) },
                                label = { Text("短信验证码") }, singleLine = true,
                                leadingIcon = { Icon(Icons.Rounded.Key, null) },
                                supportingText = { Text("输入收到的 4–8 位验证码") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                visualTransformation = PasswordVisualTransformation(),
                                enabled = !state.busy && state.accounts.isEmpty(), modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedBorderColor = Color.Transparent,
                                ),
                            )
                            // 发送只能由这一个按钮触发，切模式和取消不重置 ViewModel 的 60 秒限流。
                            OutlinedButton(
                                onClick = { viewModel.sendSms(mobile) },
                                enabled = mobile.matches(Regex("1[0-9]{10}")) && !state.busy && state.cooldown == 0 && account.status != KugouAccountStatus.RESTORING,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(if (state.cooldown > 0) "${state.cooldown} 秒后重发" else "发送验证码") }
                            Button(
                                onClick = { viewModel.loginSms(mobile, code) },
                                enabled = mobile.matches(Regex("1[0-9]{10}")) && code.matches(Regex("[0-9]{4,8}")) && !state.busy && account.status != KugouAccountStatus.RESTORING,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text("验证并登录") }
                            if (mobile.isNotEmpty() || code.isNotEmpty() || state.busy) {
                                TextButton(onClick = { cancelAuthorization() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消登录") }
                            }
                        } else {
                            Text("使用酷狗官方客户端扫码，并在客户端内确认授权。", style = MaterialTheme.typography.bodyMedium)
                            state.qr?.let { KugouQrImage(it.url) } ?: run {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(16.dp),
                                ) {
                                    Column(
                                        Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Icon(Icons.Rounded.QrCode2, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(64.dp))
                                        Text("生成二维码后开始扫码", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Text("二维码在本地生成，不发送到第三方图站。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(
                                onClick = viewModel::createQr, enabled = !state.busy && account.status != KugouAccountStatus.RESTORING,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(if (state.qr == null) "生成授权二维码" else "重新生成二维码") }
                            if (state.polling) {
                                TextButton(onClick = { cancelAuthorization() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消扫码") }
                            }
                        }
                      }
                    }
                }
            }
            item("feedback") {
                if (state.busy || state.polling) {
                    KugouLoadingStatus(
                        state.message.ifBlank { if (state.polling) "等待扫码授权…" else "正在处理…" },
                        Modifier.padding(horizontal = 16.dp),
                    )
                } else if (state.message.isNotBlank()) {
                    KugouMessage(state.message)
                }
                state.error?.let { KugouMessage(it, isError = true) }
            }
            item("vip") {
                KugouSectionTitle("每日 VIP 权益")
                KugouCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                  Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (signedIn) {
                        Text(account.vipDescription.ifBlank { "会员信息暂未返回，请刷新账号资料。" }, style = MaterialTheme.typography.bodyLarge)
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("每日自动检查领取", style = MaterialTheme.typography.titleSmall)
                            Text("仅领取官方当日可领的免费权益", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(
                            checked = account.autoClaimVip, onCheckedChange = viewModel::setAutoClaim,
                            enabled = !state.busy && !state.polling && account.status != KugouAccountStatus.RESTORING,
                        )
                    }
                    KugouClaimFeedback(account.claimStatus, account.claimMessage)
                    account.lastClaimCheck?.let { timestamp ->
                        Text(
                            "最近检查：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(timestamp))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(
                        onClick = viewModel::claimVip,
                        enabled = signedIn && !state.busy && !state.polling && account.claimStatus != KugouClaimStatus.CHECKING,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("立即检查领取") }
                    Text(
                        "后台检查可能受省电、断网或强制停止影响；下次打开时补检查当日权益，不追补过期奖励。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
            }
            item("account_actions") {
                KugouSectionTitle("账号管理")
                KugouCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column {
                        KugouAccountAction(Icons.Rounded.Refresh, "刷新账号资料", !state.busy && !state.polling, onClick = viewModel::refresh)
                        if (signedIn) {
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            KugouAccountAction(Icons.Rounded.SwapHoriz, if (showLogin) "取消切换" else "切换账号", !state.busy,
                                onClick = { cancelAuthorization(); showLogin = !showLogin })
                            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            KugouAccountAction(Icons.AutoMirrored.Rounded.Logout, "退出账号", !state.busy, destructive = true, onClick = { confirmLogout = true })
                        }
                    }
                }
            }
        }
    }
    if (state.accounts.isNotEmpty()) AlertDialog(
        onDismissRequest = { dismissSelection() },
        title = { Text("选择酷狗账号") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("此手机号绑定多个账号，请选择本次授权的账号。", style = MaterialTheme.typography.bodyMedium)
                if (state.busy) KugouLoadingStatus(state.message.ifBlank { "正在登录所选账号…" })
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                state.accounts.forEach { loginAccount ->
                    TextButton(
                        onClick = { viewModel.loginSms(mobile, code, loginAccount.userId) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                    ) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(loginAccount.nickname.ifBlank { "酷狗账号" }, style = MaterialTheme.typography.titleSmall)
                            Text("账号 ID：${loginAccount.userId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { dismissSelection() }) { Text("取消") } },
    )
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false }, title = { Text("退出酷狗账号？") },
        text = { Text("停止此账号的自动领取任务。本地喜欢、歌单和历史会保留，概念版歌曲再次播放时需要重新登录。") },
        confirmButton = {
            TextButton(
                onClick = { confirmLogout = false; showLogin = false; mobile = ""; code = ""; viewModel.logout() },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("退出账号") }
        },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
    )
}

@Composable
private fun KugouAccountAction(icon: ImageVector, label: String, enabled: Boolean, destructive: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Surface(onClick = onClick, enabled = enabled, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = color.copy(alpha = if (enabled) 1f else 0.4f))
            Text(label, Modifier.weight(1f), fontSize = 14.sp, color = color.copy(alpha = if (enabled) 1f else 0.4f))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun KugouClaimFeedback(status: KugouClaimStatus, message: String) {
    val failed = status == KugouClaimStatus.ERROR
    val claimed = status == KugouClaimStatus.CLAIMED || status == KugouClaimStatus.ALREADY_CLAIMED
    Surface(
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth(),
        color = when {
            failed -> MaterialTheme.colorScheme.errorContainer
            claimed -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        contentColor = when {
            failed -> MaterialTheme.colorScheme.onErrorContainer
            claimed -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status == KugouClaimStatus.CHECKING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (claimed || failed) Icon(if (claimed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, modifier = Modifier.size(18.dp))
                if (status == KugouClaimStatus.CHECKING || claimed || failed) Spacer(Modifier.width(8.dp))
                Text(claimStatusText(status), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun claimStatusText(status: KugouClaimStatus) = when (status) {
    KugouClaimStatus.IDLE -> "等待检查"
    KugouClaimStatus.CHECKING -> "正在检查当日权益"
    KugouClaimStatus.CLAIMED -> "今日领取成功"
    KugouClaimStatus.ALREADY_CLAIMED -> "今日已领取"
    KugouClaimStatus.INELIGIBLE -> "暂不符合领取条件"
    KugouClaimStatus.ERROR -> "上次检查失败，可重试"
    KugouClaimStatus.NEED_LOGIN -> "需要重新登录"
    KugouClaimStatus.NEED_OFFICIAL_APP -> "请在酷狗官方客户端处理"
}

@Composable
private fun KugouQrImage(url: String) {
    val image by produceState<QrImageState>(QrImageState(), url) {
        value = withContext(Dispatchers.Default) {
            try {
                val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 360, 360,
                    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 2))
                val pixels = IntArray(matrix.width * matrix.height) { index ->
                    if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                }
                QrImageState(bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                QrImageState(error = "二维码生成失败，请重新生成授权二维码。")
            }
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // 扫码需要稳定黑白对比，只有二维码区域固定白底，不覆盖应用主题。
        Surface(color = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 264.dp).fillMaxWidth().aspectRatio(1f)) {
            image.bitmap?.let {
                Image(it, contentDescription = "酷狗官方授权二维码", modifier = Modifier.fillMaxSize().padding(12.dp))
            } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (image.error == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
    image.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

private data class QrImageState(val bitmap: ImageBitmap? = null, val error: String? = null)
