package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RadioAutoReconnectKey
import com.dd3boh.outertune.constants.RadioBitrateKey
import com.dd3boh.outertune.constants.RadioCountryKey
import com.dd3boh.outertune.constants.RadioKeepAliveOnTaskClearKey
import com.dd3boh.outertune.constants.RadioLanguageKey
import com.dd3boh.outertune.constants.RadioWakeLockKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.radio.RadioConstants
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
import com.dd3boh.outertune.utils.BatteryOptimizationHelper
import com.dd3boh.outertune.utils.rememberPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val (bitrate, onBitrateChange) = rememberPreference(RadioBitrateKey, defaultValue = "all")
    val (country, onCountryChange) = rememberPreference(RadioCountryKey, defaultValue = "CN")
    val (language, onLanguageChange) = rememberPreference(RadioLanguageKey, defaultValue = "chinese")
    val (wakeLock, onWakeLockChange) = rememberPreference(RadioWakeLockKey, defaultValue = true)
    val (autoReconnect, onAutoReconnectChange) = rememberPreference(RadioAutoReconnectKey, defaultValue = true)
    val (keepAliveOnClear, onKeepAliveOnClearChange) = rememberPreference(RadioKeepAliveOnTaskClearKey, defaultValue = true)

    val context = LocalContext.current
    var isIgnoringBattery by remember {
        mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context))
    }

    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(title = "电台筛选偏好")

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            ListPreference(
                title = { Text("码率过滤") },
                icon = { Icon(Icons.Rounded.Speed, null) },
                selectedValue = bitrate,
                values = RadioConstants.BITRATE_OPTIONS.map { it.first },
                valueText = { RadioConstants.getBitrateLabel(it) },
                onValueSelected = onBitrateChange,
            )

            ListPreference(
                title = { Text("国家和地区") },
                icon = { Icon(Icons.Rounded.Public, null) },
                selectedValue = country,
                values = RadioConstants.COUNTRY_OPTIONS.map { it.first },
                valueText = { RadioConstants.getCountryName(it) },
                onValueSelected = onCountryChange,
            )

            ListPreference(
                title = { Text("广播语言") },
                icon = { Icon(Icons.Rounded.Language, null) },
                selectedValue = language,
                values = RadioConstants.LANGUAGE_OPTIONS.map { it.first },
                valueText = { RadioConstants.getLanguageName(it) },
                onValueSelected = onLanguageChange,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        PreferenceGroupTitle(title = "后台保活与稳定性")

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text("电池优化（防息屏断网）") },
                description = if (isIgnoringBattery) {
                    "已设为无限制（推荐），息屏时系统不会冻结网络"
                } else {
                    "当前可能受省电限制，息屏数分钟后可能断网。点击设为「无限制」"
                },
                icon = { Icon(Icons.Rounded.Security, null) },
                onClick = {
                    BatteryOptimizationHelper.requestIgnoreBatteryOptimization(context)
                    isIgnoringBattery = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
                }
            )

            SwitchPreference(
                title = { Text("后台防休眠唤醒锁") },
                description = "播放时持有 CPU 与 Wi-Fi 锁，防止息屏后硬件休眠断音",
                icon = { Icon(Icons.Rounded.Lock, null) },
                checked = wakeLock,
                onCheckedChange = onWakeLockChange,
            )

            SwitchPreference(
                title = { Text("掉线与卡顿自动重连") },
                description = "遇到网络短暂断开或连续缓冲超时时，自动重新连接电台流",
                icon = { Icon(Icons.Rounded.Sync, null) },
                checked = autoReconnect,
                onCheckedChange = onAutoReconnectChange,
            )

            SwitchPreference(
                title = { Text("清理最近任务时保持播放") },
                description = "从多任务列表划掉卡片时，电台在前台服务继续收听",
                icon = { Icon(Icons.Rounded.Radio, null) },
                checked = keepAliveOnClear,
                onCheckedChange = onKeepAliveOnClearChange,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        PreferenceGroupTitle(title = "快捷重置")

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text("重置所有电台筛选") },
                description = "还原为默认收听中国地区与中文广播",
                icon = { Icon(Icons.Rounded.CleaningServices, null) },
                onClick = {
                    onBitrateChange("all")
                    onCountryChange("CN")
                    onLanguageChange("chinese")
                }
            )
        }

        Spacer(modifier = Modifier.height(96.dp))
    }

    TopAppBar(
        title = { Text("电台设置") },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = null
                )
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior
    )
}
