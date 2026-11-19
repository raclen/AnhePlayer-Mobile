package com.dd3boh.outertune.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Badge
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.ShowKugouKey
import com.dd3boh.outertune.constants.ShowNeteaseRecommendKey
import com.dd3boh.outertune.constants.ShowRadioKey
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.utils.rememberPreference

private data class MenuItem(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val isSelected: (String?) -> Boolean,
)

/**
 * 主导航左侧侧边栏抽屉
 *
 * 结构对齐 PC 端：
 * - 在线分组（支持折叠展开）：排行榜、热门歌单、网易云推荐（可配置隐藏）、酷狗专区（可配置隐藏）、电台（可配置隐藏）
 * - 本地分组（支持折叠展开）：本地音乐、下载管理、最近播放
 * - 歌单
 * - 设置
 * - 关于
 */
@Composable
fun MainNavigationDrawer(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    hasUnreadUpdate: Boolean = false,
) {
    val (showKugou) = rememberPreference(ShowKugouKey, defaultValue = true)
    val (showNeteaseRecommend) = rememberPreference(ShowNeteaseRecommendKey, defaultValue = true)
    val (showRadio) = rememberPreference(ShowRadioKey, defaultValue = true)

    var onlineExpanded by rememberSaveable { mutableStateOf(true) }
    var localExpanded by rememberSaveable { mutableStateOf(true) }

    // 在线项定义
    val onlineItems = remember(showKugou, showNeteaseRecommend, showRadio) {
        buildList {
            add(
                MenuItem(
                    route = Screens.Toplist.route,
                    title = "排行榜",
                    icon = Icons.Rounded.Leaderboard,
                    isSelected = { it == Screens.Toplist.route || it == "kugou/browse/ranks" }
                )
            )
            add(
                MenuItem(
                    route = Screens.RecommendSheets.route,
                    title = "热门歌单",
                    icon = Icons.Rounded.Whatshot,
                    isSelected = { it == Screens.RecommendSheets.route || it == "kugou/browse/playlists" }
                )
            )
            if (showNeteaseRecommend) {
                add(
                    MenuItem(
                        route = Screens.NeteaseRecommend.route,
                        title = "网易云推荐",
                        icon = Icons.Rounded.Waves,
                        isSelected = { it == Screens.NeteaseRecommend.route }
                    )
                )
            }
            if (showKugou) {
                add(
                    MenuItem(
                        route = Screens.Kugou.route,
                        title = "酷狗专区",
                        icon = Icons.Rounded.Headphones,
                        isSelected = {
                            it == Screens.Kugou.route ||
                                (it?.startsWith("kugou/") == true && it != "kugou/browse/ranks" && it != "kugou/browse/playlists")
                        }
                    )
                )
            }
            if (showRadio) {
                add(
                    MenuItem(
                        route = Screens.Radio.route,
                        title = "电台",
                        icon = Icons.Rounded.Radio,
                        isSelected = { it == Screens.Radio.route }
                    )
                )
            }
        }
    }

    // 本地项定义
    val localItems = remember {
        listOf(
            MenuItem(
                route = Screens.Songs.route,
                title = "本地音乐",
                icon = Icons.Rounded.MusicNote,
                isSelected = { it == Screens.Songs.route }
            ),
            MenuItem(
                route = Screens.Download.route,
                title = "下载管理",
                icon = Icons.Rounded.Download,
                isSelected = { it == Screens.Download.route || it == "auto_playlist/downloaded" }
            ),
            MenuItem(
                route = "history",
                title = "最近播放",
                icon = Icons.Rounded.History,
                isSelected = { it == "history" }
            )
        )
    }

    // 当切换到属于分组的路由时自动确保对应分组展开
    LaunchedEffect(currentRoute) {
        if (onlineItems.any { it.isSelected(currentRoute) }) {
            onlineExpanded = true
        }
        if (localItems.any { it.isSelected(currentRoute) }) {
            localExpanded = true
        }
    }

    ModalDrawerSheet {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Spacer(Modifier.height(36.dp))

            // Logo & 品牌标题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(R.drawable.app_logo),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(8.dp))

            // ── 在线分组 ──
            DrawerGroupHeader(
                title = "在线",
                expanded = onlineExpanded,
                onToggle = { onlineExpanded = !onlineExpanded }
            )

            AnimatedVisibility(
                visible = onlineExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    onlineItems.forEach { item ->
                        DrawerSubItem(
                            item = item,
                            selected = item.isSelected(currentRoute),
                            onClick = { onNavigate(item.route) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // ── 本地分组 ──
            DrawerGroupHeader(
                title = "本地",
                expanded = localExpanded,
                onToggle = { localExpanded = !localExpanded }
            )

            AnimatedVisibility(
                visible = localExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    localItems.forEach { item ->
                        DrawerSubItem(
                            item = item,
                            selected = item.isSelected(currentRoute),
                            onClick = { onNavigate(item.route) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            // ── 歌单 ──
            val isPlaylistsSelected = currentRoute == Screens.Playlists.route ||
                (currentRoute?.startsWith("local_playlist/") == true)
            NavigationDrawerItem(
                label = { Text("歌单", fontWeight = if (isPlaylistsSelected) FontWeight.Bold else FontWeight.Normal) },
                selected = isPlaylistsSelected,
                icon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
                onClick = { onNavigate(Screens.Playlists.route) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
            )

            // ── 设置 ──
            val isSettingsSelected = currentRoute == Screens.Settings.route ||
                (currentRoute?.startsWith("settings/") == true && currentRoute != "settings/about")
            NavigationDrawerItem(
                label = { Text("设置", fontWeight = if (isSettingsSelected) FontWeight.Bold else FontWeight.Normal) },
                selected = isSettingsSelected,
                icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                onClick = { onNavigate(Screens.Settings.route) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
            )

            // ── 关于 ──
            val isAboutSelected = currentRoute == "settings/about"
            NavigationDrawerItem(
                label = { Text("关于", fontWeight = if (isAboutSelected) FontWeight.Bold else FontWeight.Normal) },
                selected = isAboutSelected,
                icon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                badge = {
                    if (hasUnreadUpdate) {
                        val description = stringResource(R.string.app_update_unread)
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { contentDescription = description }
                        )
                    }
                },
                onClick = { onNavigate("settings/about") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(NavigationDrawerItemDefaults.ItemPadding)
            )
        }
    }
}

/**
 * 抽屉分组折叠/展开标头
 */
@Composable
private fun DrawerGroupHeader(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 0f else -90f,
        label = "DrawerGroupArrow"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle),
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                modifier = Modifier
                    .size(20.dp)
                    .rotate(arrowRotation)
            )
        }
    }
}

/**
 * 抽屉子导航项
 */
@Composable
private fun DrawerSubItem(
    item: MenuItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    NavigationDrawerItem(
        label = {
            Text(
                text = item.title,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
        },
        selected = selected,
        icon = { Icon(item.icon, contentDescription = null) },
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 12.dp)
            .padding(vertical = 1.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigationTopBar(
    currentRoute: String?,
    onOpenDrawer: () -> Unit,
    onSearch: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    val title = when (currentRoute) {
        Screens.Songs.route -> stringResource(R.string.local_music)
        Screens.Download.route, "download" -> stringResource(R.string.download_manager)
        "history" -> stringResource(R.string.recent_played)
        Screens.Playlists.route -> "歌单"
        Screens.Toplist.route -> stringResource(R.string.toplist)
        Screens.RecommendSheets.route -> stringResource(R.string.recommend_sheets)
        Screens.NeteaseRecommend.route -> stringResource(R.string.netease_recommend)
        Screens.Kugou.route -> stringResource(R.string.kugou_zone)
        Screens.Radio.route -> stringResource(R.string.radio)
        Screens.Settings.route -> stringResource(R.string.settings)
        "settings/about" -> stringResource(R.string.about)
        else -> return
    }

    CenterAlignedTopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Rounded.Menu, contentDescription = "打开菜单")
            }
        },
        actions = {
            if (currentRoute == Screens.Songs.route || currentRoute == "history" ||
                currentRoute == Screens.Playlists.route || currentRoute == Screens.Kugou.route ||
                currentRoute == Screens.Toplist.route || currentRoute == Screens.RecommendSheets.route ||
                currentRoute == Screens.NeteaseRecommend.route || currentRoute == Screens.Download.route ||
                currentRoute == "download"
            ) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索")
                }
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}
