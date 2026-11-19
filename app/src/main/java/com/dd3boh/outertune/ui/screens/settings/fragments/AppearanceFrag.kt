/*
 * Copyright (C) 2025 O‌ute‌rTu‌ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune.ui.screens.settings.fragments

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ShowKugouKey
import com.dd3boh.outertune.constants.ShowNeteaseRecommendKey
import com.dd3boh.outertune.constants.ShowRadioKey
import com.dd3boh.outertune.constants.SlimNavBarKey
import com.dd3boh.outertune.ui.component.SwitchPreference
import com.dd3boh.outertune.utils.rememberPreference

@Composable
fun ColumnScope.AppearanceMiscFrag() {
    val (showNeteaseRecommend, onShowNeteaseRecommendChange) = rememberPreference(ShowNeteaseRecommendKey, defaultValue = true)
    val (showKugou, onShowKugouChange) = rememberPreference(ShowKugouKey, defaultValue = true)
    val (showRadio, onShowRadioChange) = rememberPreference(ShowRadioKey, defaultValue = true)
    val (slimNav, onSlimNavChange) = rememberPreference(SlimNavBarKey, defaultValue = false)

    SwitchPreference(
        title = { Text("显示网易云推荐") },
        description = if (showNeteaseRecommend) "在左侧菜单「在线」中显示网易云推荐（关闭后隐藏）" else "已在左侧菜单中隐藏（开启后在「在线」显示）",
        icon = { Icon(Icons.Rounded.Waves, null) },
        checked = showNeteaseRecommend,
        onCheckedChange = onShowNeteaseRecommendChange
    )

    SwitchPreference(
        title = { Text("显示酷狗专区") },
        description = if (showKugou) "在左侧菜单「在线」中显示酷狗专区（关闭后隐藏）" else "已在左侧菜单中隐藏（开启后在「在线」显示）",
        icon = { Icon(Icons.Rounded.Headphones, null) },
        checked = showKugou,
        onCheckedChange = onShowKugouChange
    )

    SwitchPreference(
        title = { Text("显示电台") },
        description = if (showRadio) "在左侧菜单「在线」中显示全球网络电台（关闭后隐藏）" else "已在左侧菜单中隐藏（开启后在「在线」显示）",
        icon = { Icon(Icons.Rounded.Radio, null) },
        checked = showRadio,
        onCheckedChange = onShowRadioChange
    )

    SwitchPreference(
        title = { Text(stringResource(R.string.slim_navbar_title)) },
        description = stringResource(R.string.slim_navbar_description),
        icon = { Icon(Icons.Rounded.MoreHoriz, null) },
        checked = slimNav,
        onCheckedChange = onSlimNavChange
    )
}