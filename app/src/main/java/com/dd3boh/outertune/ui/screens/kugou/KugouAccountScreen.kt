package com.dd3boh.outertune.ui.screens.kugou

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
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
    var showLogin by remember { mutableStateOf(false) }
    var smsMode by remember { mutableStateOf(false) }
    // 手机号与验证码只存在页面内存，不进入 SavedStateHandle、DataStore 或搜索历史。
    var mobile by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var confirmLogout by remember { mutableStateOf(false) }
    val signedIn = account.status == KugouAccountStatus.SIGNED_IN
    CancelOnKugouPageStop {
        viewModel.cancelLogin()
        mobile = ""
        code = ""
    }
    LaunchedEffect(session, account.status) {
        if (account.status == KugouAccountStatus.SIGNED_IN) {
            mobile = ""
            code = ""
            showLogin = false
        }
    }
    KugouScreenFrame("酷狗账号", navController, scrollBehavior) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = kugouContentPadding()) {
            item("account") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(when (account.status) {
                        KugouAccountStatus.SIGNED_OUT -> "未登录"
                        KugouAccountStatus.RESTORING -> "正在恢复账号"
                        KugouAccountStatus.SIGNED_IN -> account.nickname.ifBlank { "已登录酷狗账号" }
                        KugouAccountStatus.EXPIRED -> "账号授权已过期"
                    }, style = MaterialTheme.typography.headlineSmall)
                    account.userId?.let { Text("账号 ID：$it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (signedIn) Text(account.vipDescription.ifBlank { "会员信息暂未返回，请刷新账号资料。" }, style = MaterialTheme.typography.bodyLarge)
                    if (account.status == KugouAccountStatus.EXPIRED) Text("请重新扫码或使用短信登录。", style = MaterialTheme.typography.bodyMedium)
                    account.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    Row {
                        TextButton(onClick = viewModel::refresh, enabled = !state.busy && !state.polling) { Text("刷新账号资料") }
                        if (signedIn) {
                            TextButton(onClick = { viewModel.cancelLogin(); showLogin = !showLogin }, enabled = !state.busy) { Text(if (showLogin) "取消切换" else "切换账号") }
                            TextButton(onClick = { confirmLogout = true }, enabled = !state.busy) { Text("退出账号") }
                        }
                    }
                }
            }
            item("vip") {
                KugouSectionTitle("每日 VIP 权益")
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("每日自动检查领取", style = MaterialTheme.typography.bodyLarge)
                            Text("仅领取官方当日可领的免费权益", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = account.autoClaimVip, onCheckedChange = viewModel::setAutoClaim,
                            enabled = !state.busy && !state.polling && account.status != KugouAccountStatus.RESTORING)
                    }
                    Text(claimStatusText(account.claimStatus), style = MaterialTheme.typography.titleSmall)
                    if (account.claimMessage.isNotBlank()) Text(account.claimMessage, style = MaterialTheme.typography.bodyMedium)
                    account.lastClaimCheck?.let { timestamp ->
                        Text("最近检查：${DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochMilli(timestamp))}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = viewModel::claimVip, enabled = signedIn && !state.busy && !state.polling && account.claimStatus != KugouClaimStatus.CHECKING) { Text("立即检查领取") }
                    Text("后台检查可能受省电、断网或强制停止影响；下次打开时补检查当日权益，不追补过期奖励。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!signedIn || showLogin) {
                item("login_title") {
                    KugouSectionTitle(if (showLogin && signedIn) "授权另一个账号" else "登录酷狗")
                    Row(Modifier.padding(horizontal = 16.dp)) {
                        FilterChip(selected = !smsMode, onClick = { viewModel.cancelLogin(); smsMode = false }, enabled = !state.busy, label = { Text("扫码授权") })
                        FilterChip(selected = smsMode, onClick = { viewModel.cancelLogin(); smsMode = true }, enabled = !state.busy, label = { Text("短信验证码") }, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                item("login_form") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (smsMode) {
                            Text("验证码仅通过安全通道发送；接口不支持安全短信时，请改用扫码授权。", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(value = mobile, onValueChange = { mobile = it.filter { digit -> digit in '0'..'9' }.take(11) },
                                label = { Text("11 位手机号") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                enabled = !state.busy && state.accounts.isEmpty(), modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(value = code, onValueChange = { code = it.filter { digit -> digit in '0'..'9' }.take(8) },
                                label = { Text("短信验证码") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                visualTransformation = PasswordVisualTransformation(), enabled = !state.busy && state.accounts.isEmpty(), modifier = Modifier.fillMaxWidth())
                            OutlinedButton(onClick = { viewModel.sendSms(mobile) }, enabled = mobile.matches(Regex("1[0-9]{10}")) && !state.busy && state.cooldown == 0 && account.status != KugouAccountStatus.RESTORING) {
                                Text(if (state.cooldown > 0) "${state.cooldown} 秒后重发" else "发送验证码")
                            }
                            Button(onClick = { viewModel.loginSms(mobile, code) }, enabled = mobile.matches(Regex("1[0-9]{10}")) && code.matches(Regex("[0-9]{4,8}")) && !state.busy && account.status != KugouAccountStatus.RESTORING) { Text("登录") }
                        } else {
                            Text("使用酷狗官方客户端扫码，并在客户端内确认授权。二维码在本地生成，不发送到第三方图站。", style = MaterialTheme.typography.bodyMedium)
                            state.qr?.let { KugouQrImage(it.url) }
                            Button(onClick = viewModel::createQr, enabled = !state.busy && account.status != KugouAccountStatus.RESTORING) {
                                Text(if (state.qr == null) "生成授权二维码" else "重新生成二维码")
                            }
                            if (state.polling) TextButton(onClick = viewModel::cancelLogin) { Text("取消扫码") }
                        }
                    }
                }
            }
            item("feedback") {
                if (state.busy) Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在处理…", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (state.message.isNotBlank()) KugouMessage(state.message)
                state.error?.let { KugouMessage(it) }
            }
        }
    }
    if (state.accounts.isNotEmpty()) AlertDialog(
        onDismissRequest = viewModel::dismissAccountSelection,
        title = { Text("选择酷狗账号") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text("此手机号绑定多个账号，请选择本次授权的账号。", style = MaterialTheme.typography.bodyMedium)
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                state.accounts.forEach { loginAccount ->
                    TextButton(onClick = { viewModel.loginSms(mobile, code, loginAccount.userId) }, enabled = !state.busy) {
                        Text("${loginAccount.nickname.ifBlank { "酷狗账号" }}\n账号 ID：${loginAccount.userId}")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = viewModel::dismissAccountSelection) { Text("取消") } },
    )
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false }, title = { Text("退出酷狗账号？") },
        text = { Text("停止此账号的自动领取任务。本地喜欢、歌单和历史会保留，概念版歌曲再次播放时需要重新登录。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; showLogin = false; mobile = ""; code = ""; viewModel.logout() }) { Text("退出账号") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } },
    )
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
    image.bitmap?.let { Image(it, contentDescription = "酷狗官方授权二维码", modifier = Modifier.size(240.dp)) }
    image.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
}

private data class QrImageState(val bitmap: ImageBitmap? = null, val error: String? = null)
