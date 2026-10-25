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
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.RadioBitrateKey
import com.dd3boh.outertune.constants.RadioCountryKey
import com.dd3boh.outertune.constants.RadioLanguageKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.radio.RadioConstants
import com.dd3boh.outertune.ui.component.ColumnWithContentPadding
import com.dd3boh.outertune.ui.component.ListPreference
import com.dd3boh.outertune.ui.component.PreferenceEntry
import com.dd3boh.outertune.ui.component.PreferenceGroupTitle
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.utils.backToMain
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
