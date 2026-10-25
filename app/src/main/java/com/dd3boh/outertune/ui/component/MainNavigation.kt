package com.dd3boh.outertune.ui.component

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dd3boh.outertune.R
import com.dd3boh.outertune.constants.TopBarInsets
import com.dd3boh.outertune.ui.component.button.IconButton
import com.dd3boh.outertune.ui.screens.Screens

private data class DrawerDestination(
    val route: String,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val drawerDestinations = listOf(
    DrawerDestination(Screens.Songs.route, "歌曲", Icons.Rounded.MusicNote),
    DrawerDestination(Screens.Playlists.route, "歌单", Icons.AutoMirrored.Rounded.QueueMusic),
    DrawerDestination(Screens.Radio.route, "电台", Icons.Rounded.Radio),
    DrawerDestination(Screens.Settings.route, "设置", Icons.Rounded.Settings),
    DrawerDestination("settings/about", "关于", Icons.Rounded.Info),
)

@Composable
fun MainNavigationDrawer(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    hasUnreadUpdate: Boolean = false,
) {
    ModalDrawerSheet {
        Spacer(Modifier.height(40.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
        )
        drawerDestinations.forEach { destination ->
            NavigationDrawerItem(
                label = { Text(destination.title) },
                selected = currentRoute == destination.route ||
                    (currentRoute == "history" && destination.route == Screens.Songs.route),
                icon = { Icon(destination.icon, contentDescription = null) },
                badge = {
                    if (destination.route == "settings/about" && hasUnreadUpdate) {
                        val description = stringResource(R.string.app_update_unread)
                        Badge(
                            containerColor = MaterialTheme.colorScheme.error,
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    }
                },
                onClick = { onNavigate(destination.route) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }
    }
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
        Screens.Songs.route -> stringResource(R.string.songs)
        "history" -> stringResource(R.string.songs)
        Screens.Playlists.route -> "歌单"
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
            if (currentRoute == Screens.Songs.route || currentRoute == "history" || currentRoute == Screens.Playlists.route) {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索")
                }
            }
        },
        windowInsets = TopBarInsets,
        scrollBehavior = scrollBehavior,
    )
}
