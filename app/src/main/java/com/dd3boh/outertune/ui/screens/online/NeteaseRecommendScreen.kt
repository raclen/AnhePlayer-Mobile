package com.dd3boh.outertune.ui.screens.online

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.dd3boh.outertune.LocalDatabase
import com.dd3boh.outertune.LocalPlayerAwareWindowInsets
import com.dd3boh.outertune.LocalPlayerConnection
import com.dd3boh.outertune.LocalSnackbarHostState
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.playback.queues.ListQueue
import com.dd3boh.outertune.source.netease.NeteaseAccountProfile
import com.dd3boh.outertune.source.netease.NeteaseSongItem
import com.dd3boh.outertune.viewmodels.NeteaseRecommendViewModel
import com.dd3boh.outertune.viewmodels.NeteaseTab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 网易云音乐推荐专区
 *
 * 完整对齐 PC 版 AnhePlayer，支持网易云账号扫码登录、Cookie 同步、
 * 私人雷达、每日推荐歌曲以及私人漫游收听。
 */
@Composable
fun NeteaseRecommendScreen(
    navController: NavController,
    viewModel: NeteaseRecommendViewModel = hiltViewModel()
) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val snackbarHostState = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    val profile by viewModel.profile.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val isLoggedIn = profile != null

    val currentMediaMetadata by playerConnection?.mediaMetadata?.collectAsState()
        ?: remember { mutableStateOf(null) }
    val isPlaying = playerConnection?.isPlaying?.collectAsState()?.value == true

    var showLogoutConfirm by remember { mutableStateOf(false) }

    val insets = LocalPlayerAwareWindowInsets.current.asPaddingValues()
    val direction = LocalLayoutDirection.current
    val contentPadding = PaddingValues(
        start = insets.calculateStartPadding(direction) + 16.dp,
        end = insets.calculateEndPadding(direction) + 16.dp,
        top = insets.calculateTopPadding() + 8.dp,
        bottom = insets.calculateBottomPadding() + 24.dp,
    )

    // 播放指定歌曲或全部
    fun playSongs(songs: List<NeteaseSongItem>, startIndex: Int, title: String) {
        if (songs.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("当前列表为空，无法播放") }
            return
        }
        scope.launch {
            val mediaMetadataList = songs.map { it.toMediaMetadata() }
            withContext(Dispatchers.IO) {
                mediaMetadataList.forEach { database.insert(it) }
            }
            playerConnection?.playQueue(
                ListQueue(
                    playlistId = "netease_${uiState.currentTab.name.lowercase()}",
                    title = title,
                    items = mediaMetadataList,
                    startIndex = startIndex.coerceIn(songs.indices)
                )
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 用户账号栏 / 登录引导卡片
            item("account_header") {
                if (isLoggedIn && profile != null) {
                    LoggedInHeader(
                        profile = profile!!,
                        onRefresh = { viewModel.refreshCurrentTab() },
                        onLogoutClick = { showLogoutConfirm = true }
                    )
                } else {
                    UnloggedHeroCard(
                        onLoginClick = { viewModel.openLoginDialog() }
                    )
                }
            }

            // 2. 如果已登录，展示分类 Tabs 与核心音乐列表
            if (isLoggedIn) {
                item("tabs") {
                    PrimaryTabRow(
                        selectedTabIndex = uiState.currentTab.ordinal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        NeteaseTab.entries.forEach { tab ->
                            Tab(
                                selected = uiState.currentTab == tab,
                                onClick = { viewModel.switchTab(tab) },
                                text = { Text(tab.title) },
                                icon = {
                                    val icon = when (tab) {
                                        NeteaseTab.RADAR -> Icons.Rounded.Radio
                                        NeteaseTab.DAILY -> Icons.Rounded.Whatshot
                                        NeteaseTab.FM -> Icons.Rounded.Waves
                                    }
                                    Icon(icon, contentDescription = null, Modifier.size(18.dp))
                                }
                            )
                        }
                    }
                }

                // 加载中进度条
                if (uiState.isLoading) {
                    item("loading") {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                        )
                    }
                }

                // 错误提示与重试
                uiState.error?.let { err ->
                    item("error") {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = err,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { viewModel.refreshCurrentTab() }) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                }

                // 获取当前 Tab 的歌曲列表与元信息
                val (currentSongs, tabTitle, tabDesc, tabCover) = when (uiState.currentTab) {
                    NeteaseTab.RADAR -> {
                        val res = uiState.radarResult
                        Quadruple(res.songs, res.title, res.description, res.coverUrl)
                    }
                    NeteaseTab.DAILY -> {
                        Quadruple(
                            uiState.dailySongs,
                            "每日推荐",
                            "每天 06:00 精选更新，早安专属好歌",
                            uiState.dailySongs.firstOrNull()?.coverUrl
                        )
                    }
                    NeteaseTab.FM -> {
                        Quadruple(
                            uiState.fmSongs,
                            "私人漫游 FM",
                            "顺着你的喜好流淌的无界电台",
                            uiState.fmSongs.firstOrNull()?.coverUrl
                        )
                    }
                }

                // 3. Tab 横幅与快捷操作栏
                item("tab_banner") {
                    TabBanner(
                        title = tabTitle,
                        description = tabDesc,
                        coverUrl = tabCover,
                        songCount = currentSongs.size,
                        tab = uiState.currentTab,
                        onPlayAll = {
                            playSongs(currentSongs, 0, tabTitle)
                        },
                        onShufflePlay = {
                            val shuffled = currentSongs.shuffled()
                            playSongs(shuffled, 0, "$tabTitle (随机)")
                        }
                    )
                }

                // 4. 歌曲列表项
                if (currentSongs.isNotEmpty()) {
                    itemsIndexed(
                        items = currentSongs,
                        key = { _, song -> "wy_${song.id}" }
                    ) { index, song ->
                        val isCurrent = currentMediaMetadata?.id == "wy_${song.id}"
                        NeteaseSongListItem(
                            index = index + 1,
                            song = song,
                            isActive = isCurrent,
                            isPlaying = isCurrent && isPlaying,
                            onClick = {
                                playSongs(currentSongs, index, tabTitle)
                            }
                        )
                    }
                } else if (!uiState.isLoading && uiState.error == null) {
                    item("empty_tab") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "暂无推荐曲目，请点击右上角刷新重试",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                // 未登录状态下的特色功能介绍列表
                item("unlogged_features") {
                    UnloggedFeaturesList(
                        onLoginClick = { viewModel.openLoginDialog() }
                    )
                }
            }
        }

        // 登录弹窗
        if (uiState.isLoginDialogOpen) {
            NeteaseLoginDialog(
                qrState = uiState.qrState,
                onDismiss = { viewModel.closeLoginDialog() },
                onRefreshQr = { viewModel.startQrLogin() },
                onSubmitCookie = { cookie, cb ->
                    viewModel.loginWithCookie(cookie, cb)
                }
            )
        }

        // 退出登录二次确认对话框
        if (showLogoutConfirm) {
            AlertDialog(
                onDismissRequest = { showLogoutConfirm = false },
                title = { Text("退出登录") },
                text = { Text("确定要退出当前网易云音乐账号吗？退出后将无法查看专属私人雷达与每日推荐。") },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.logout()
                            showLogoutConfirm = false
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("退出")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutConfirm = false }) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

/**
 * 已登录时的顶部卡片
 */
@Composable
private fun LoggedInHeader(
    profile: NeteaseAccountProfile,
    onRefresh: () -> Unit,
    onLogoutClick: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 用户头像
            if (profile.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = profile.nickname,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            // 昵称与简介
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = profile.nickname.ifBlank { "网易云音乐用户" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (profile.isVip) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color(0xFFE53935), Color(0xFFFF5252))
                                    ),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "VIP",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }
                }

                Spacer(Modifier.height(3.dp))

                Text(
                    text = if (profile.signature.isNotBlank()) {
                        "${profile.accountHint} · ${profile.signature}"
                    } else {
                        profile.accountHint
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 刷新与退出登录
            IconButton(onClick = onRefresh) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = "刷新",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onLogoutClick) {
                Icon(
                    Icons.AutoMirrored.Rounded.Logout,
                    contentDescription = "退出登录",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f)
                )
            }
        }
    }
}

/**
 * 未登录时的唯美引导大卡片
 */
@Composable
private fun UnloggedHeroCard(
    onLoginClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFFE53935),
                            Color(0xFFB71C1C)
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Whatshot,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "网易云音乐专属推荐",
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "解锁专属于你的私人音乐品味",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = "登录网易云音乐账号后，将自动同步你专属的「私人雷达」、每天清晨更新的「每日推荐」歌曲以及「私人漫游」电台，畅享个性化好歌旅程。",
                    color = Color.White.copy(alpha = 0.9f),
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick = onLoginClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFFC62828)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "立即授权登录",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * Tab 核心内容横幅与操作按钮
 */
@Composable
private fun TabBanner(
    title: String,
    description: String,
    coverUrl: String?,
    songCount: Int,
    tab: NeteaseTab,
    onPlayAll: () -> Unit,
    onShufflePlay: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 封面或专属视觉图标
                Surface(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(14.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    if (!coverUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val icon = when (tab) {
                                NeteaseTab.RADAR -> Icons.Rounded.Radio
                                NeteaseTab.DAILY -> Icons.Rounded.CalendarToday
                                NeteaseTab.FM -> Icons.Rounded.Waves
                            }
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (songCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "精选收录 $songCount 首",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 快捷播放动作条
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onPlayAll,
                    enabled = songCount > 0,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("全部播放")
                }

                FilledTonalButton(
                    onClick = onShufflePlay,
                    enabled = songCount > 0,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Shuffle, contentDescription = null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("随机播放")
                }
            }
        }
    }
}

/**
 * 网易云单曲列表项
 */
@Composable
private fun NeteaseSongListItem(
    index: Int,
    song: NeteaseSongItem,
    isActive: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 序号
            Text(
                text = index.toString().padStart(2, '0'),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.width(28.dp)
            )

            // 封面
            Surface(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(8.dp)),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                if (!song.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = song.coverUrl,
                        contentDescription = song.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.AutoMirrored.Rounded.QueueMusic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // 歌名、歌手与推荐标签
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = song.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(3.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 推荐理由胶囊标签（若有）
                    if (!song.reason.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = song.reason,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }

                    val subText = if (song.albumName.isNotBlank()) {
                        "${song.singer} · ${song.albumName}"
                    } else {
                        song.singer
                    }
                    Text(
                        text = subText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 时长
            if (song.durationSeconds > 0) {
                val durText = "%02d:%02d".format(song.durationSeconds / 60, song.durationSeconds % 60)
                Text(
                    text = durText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/**
 * 未登录时的三大特色卡片
 */
@Composable
private fun UnloggedFeaturesList(
    onLoginClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "专区特权功能",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp)
        )

        FeatureGuideItem(
            icon = Icons.Rounded.Radio,
            title = "私人雷达",
            desc = "每天为你更新 35 首专属歌曲，发现更合口味的音乐。",
            tag = "算法定制",
            onClick = onLoginClick
        )

        FeatureGuideItem(
            icon = Icons.Rounded.Whatshot,
            title = "每日推荐",
            desc = "每天清晨六点更新，听见新歌与值得收藏的宝藏旋律。",
            tag = "晨间更新",
            onClick = onLoginClick
        )

        FeatureGuideItem(
            icon = Icons.Rounded.Waves,
            title = "私人漫游 FM",
            desc = "随心开启个人电台，让喜欢的音乐不间断陪伴。",
            tag = "无限电台",
            onClick = onLoginClick
        )
    }
}

@Composable
private fun FeatureGuideItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    desc: String,
    tag: String,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.surfaceContainerLowest
        ),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.65f)),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp,
            draggedElevation = 0.dp,
            disabledElevation = 0.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .background(
                            color = colors.primary.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                // 窄屏或大字体下允许标签换行，避免挤压标题。
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onSurface,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .background(
                                color = colors.primary.copy(alpha = 0.08f),
                                shape = CircleShape
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.width(12.dp))

                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = "需登录",
                    tint = colors.outline,
                    modifier = Modifier.size(16.dp)
                )
            }

            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp,
                color = colors.onSurfaceVariant
            )
        }
    }
}

private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)
