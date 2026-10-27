/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O﻿ute﻿rTu﻿ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.BuildConfig
import com.dd3boh.outertune.R
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.utils.AppLogManager

/**
 * 设置首页
 *
 * 分组按「内容与下载 → 播放与界面 → 其他」排列，每一项都能直达二级页，
 * 不再出现「音乐库和内容」里再套「本地媒体」「储存」的重复入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    ColumnWithContentPadding(
        modifier = Modifier.fillMaxHeight(),
        columnModifier = Modifier
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        PreferenceGroupTitle(title = stringResource(R.string.grp_content_and_download))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.lx_source_title)) },
                description = stringResource(R.string.lx_source_description),
                icon = { Icon(Icons.Rounded.MusicNote, null) },
                onClick = { navController.navigate("settings/lx_source") }
            )
            PreferenceEntry(
                title = { Text("电台设置") },
                description = "码率、国家地区与语言筛选",
                icon = { Icon(Icons.Rounded.Radio, null) },
                onClick = { navController.navigate("settings/radio") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_local_media)) },
                description = stringResource(R.string.grp_local_media_description),
                icon = { Icon(Icons.Rounded.SdCard, null) },
                onClick = { navController.navigate("settings/local") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.grp_download_storage)) },
                description = stringResource(R.string.grp_download_storage_description),
                icon = { Icon(Icons.Rounded.Download, null) },
                onClick = { navController.navigate("settings/storage") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(title = stringResource(R.string.grp_display_and_behavior))

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            PreferenceEntry(
                title = { Text(stringResource(R.string.appearance)) },
                icon = { Icon(Icons.Rounded.Palette, null) },
                onClick = { navController.navigate("settings/appearance") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.sound_effects)) },
                description = stringResource(R.string.equalizer),
                icon = { Icon(Icons.Rounded.GraphicEq, null) },
                onClick = { navController.navigate("settings/equalizer") }
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        PreferenceGroupTitle(title = "诊断与支持")

        ElevatedCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            val hasCrash = remember { AppLogManager.hasCrashLog() }
            PreferenceEntry(
                title = { Text("日志与错误排查") },
                description = if (hasCrash) "检测到上次闪退崩溃记录，点击查看与复制" else "查看/复制近期运行日志与设备信息",
                icon = { Icon(Icons.Rounded.BugReport, null) },
                onClick = { navController.navigate("settings/log") }
            )
            PreferenceEntry(
                title = { Text(stringResource(R.string.about)) },
                description = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                icon = { Icon(Icons.Rounded.Info, null) },
                onClick = { navController.navigate("settings/about") }
            )
        }
        Spacer(modifier = Modifier.height(96.dp))
    }
}
