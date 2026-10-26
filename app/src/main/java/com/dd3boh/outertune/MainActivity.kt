/*
 * Copyright (C) 2024 z-huang/InnerTune
 * Copyright (C) 2025 O​u​t​er​Tu​ne Project
 *
 * SPDX-License-Identifier: GPL-3.0
 *
 * For any other attributions, refer to the git commit history
 */

package com.dd3boh.outertune

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowWidthSizeClass
import com.dd3boh.outertune.constants.AppBarHeight
import com.dd3boh.outertune.constants.DEFAULT_ENABLED_TABS
import com.dd3boh.outertune.constants.DarkMode
import com.dd3boh.outertune.constants.DarkModeKey
import com.dd3boh.outertune.constants.DefaultOpenTabKey
import com.dd3boh.outertune.constants.DynamicThemeKey
import com.dd3boh.outertune.constants.EnabledTabsKey
import com.dd3boh.outertune.constants.HighContrastKey
import com.dd3boh.outertune.constants.LibraryFilterKey
import com.dd3boh.outertune.constants.MinMiniPlayerHeight
import com.dd3boh.outertune.constants.MiniPlayerHeight
import com.dd3boh.outertune.constants.NavigationBarAnimationSpec
import com.dd3boh.outertune.constants.NavigationBarHeight
import com.dd3boh.outertune.constants.PureBlackKey
import com.dd3boh.outertune.constants.SlimNavBarKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.extensions.tabMode
import com.dd3boh.outertune.playback.DownloadUtil
import com.dd3boh.outertune.playback.MediaControllerViewModel
import com.dd3boh.outertune.playback.MusicService
import com.dd3boh.outertune.playback.PlayerConnection
import com.dd3boh.outertune.ui.component.rememberBottomSheetState
import com.dd3boh.outertune.ui.component.MainNavigationDrawer
import com.dd3boh.outertune.ui.component.MainNavigationTopBar
import com.dd3boh.outertune.ui.component.AppUpdatePrompt
import com.dd3boh.outertune.ui.component.shimmer.ShimmerTheme
import com.dd3boh.outertune.ui.menu.BottomSheetMenu
import com.dd3boh.outertune.ui.menu.MenuState
import com.dd3boh.outertune.ui.player.BottomSheetPlayer
import com.dd3boh.outertune.ui.screens.AlbumScreen
import com.dd3boh.outertune.ui.screens.HistoryScreen
import com.dd3boh.outertune.ui.screens.HomeScreen
import com.dd3boh.outertune.ui.screens.PlayerScreen
import com.dd3boh.outertune.ui.screens.RadioScreen
import com.dd3boh.outertune.ui.screens.Screens
import com.dd3boh.outertune.ui.screens.StatsScreen
import com.dd3boh.outertune.ui.screens.artist.ArtistAlbumsScreen
import com.dd3boh.outertune.ui.screens.artist.ArtistScreen
import com.dd3boh.outertune.ui.screens.artist.ArtistSongsScreen
import com.dd3boh.outertune.ui.screens.library.FolderScreen
import com.dd3boh.outertune.ui.screens.library.LibraryAlbumsScreen
import com.dd3boh.outertune.ui.screens.library.LibraryArtistsScreen
import com.dd3boh.outertune.ui.screens.library.LibraryFoldersScreen
import com.dd3boh.outertune.ui.screens.library.LibraryPlaylistsScreen
import com.dd3boh.outertune.ui.screens.library.LibraryScreen
import com.dd3boh.outertune.ui.screens.library.LibrarySongsScreen
import com.dd3boh.outertune.ui.screens.playlist.AutoPlaylistScreen
import com.dd3boh.outertune.ui.screens.playlist.LocalPlaylistScreen
import com.dd3boh.outertune.ui.screens.search.OnlineSearchResult
import com.dd3boh.outertune.ui.screens.search.SearchBarContainer
import com.dd3boh.outertune.ui.screens.settings.AboutScreen
import com.dd3boh.outertune.ui.screens.settings.AppearanceSettings
import com.dd3boh.outertune.ui.screens.settings.AttributionScreen
import com.dd3boh.outertune.ui.screens.settings.EqualizerScreen
import com.dd3boh.outertune.ui.screens.settings.LibrariesScreen
import com.dd3boh.outertune.ui.screens.settings.LocalFoldersSettings
import com.dd3boh.outertune.ui.screens.settings.LxSourceSettings
import com.dd3boh.outertune.ui.screens.settings.RadioSettingsScreen
import com.dd3boh.outertune.ui.screens.settings.SettingsScreen
import com.dd3boh.outertune.ui.screens.settings.StorageSettings
import com.dd3boh.outertune.ui.theme.OuterTuneTheme
import com.dd3boh.outertune.ui.utils.MEDIA_PERMISSION_LEVEL
import com.dd3boh.outertune.ui.utils.appBarScrollBehavior
import com.dd3boh.outertune.utils.NetworkConnectivityObserver
import com.dd3boh.outertune.utils.lmScannerCoroutine
import com.dd3boh.outertune.utils.rememberEnumPreference
import com.dd3boh.outertune.utils.rememberPreference
import com.dd3boh.outertune.viewmodels.AppUpdateViewModel
import com.valentinilk.shimmer.LocalShimmerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    val MAIN_TAG = "MainOtActivity"

    @Inject
    lateinit var database: MusicDatabase

    @Inject
    lateinit var downloadUtil: DownloadUtil

    lateinit var connectivityObserver: NetworkConnectivityObserver

    private var playerConnection by mutableStateOf<PlayerConnection?>(null)

    val controllerViewModel: MediaControllerViewModel by viewModels()
    private val appUpdateViewModel: AppUpdateViewModel by viewModels()
    private var isActivityResumed by mutableStateOf(false)
    private var hasActivityWindowFocus by mutableStateOf(false)
    private var startupScanComplete by mutableStateOf(false)
    private var waitingForAudioPermission by mutableStateOf(false)
    private var lastObservedAudioPermission: Boolean? = null
    private var permissionRequestInFlight = false
    private var permissionScanPending = false
    private var audioPermissionDenied = false
    private var permissionScanGeneration = 0L
    private val audioPermissionResults = Channel<Boolean>(Channel.CONFLATED)

    private fun observeAudioPermission(granted: Boolean) {
        val previous = lastObservedAudioPermission
        lastObservedAudioPermission = granted
        if (granted && previous == false) {
            permissionScanGeneration++
            permissionScanPending = true
            audioPermissionDenied = false
            startupScanComplete = false
            audioPermissionResults.trySend(true)
        }
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        observeAudioPermission(checkSelfPermission(MEDIA_PERMISSION_LEVEL) == PackageManager.PERMISSION_GRANTED)
        appUpdateViewModel.checkAutomatically()
    }

    override fun onPause() {
        isActivityResumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        hasActivityWindowFocus = hasFocus
    }

    // storage permission helpers
    val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            permissionRequestInFlight = false
            observeAudioPermission(isGranted)
            if (!isGranted) {
                audioPermissionDenied = true
                audioPermissionResults.trySend(false)
                Toast.makeText(this, getString(R.string.scanner_missing_storage_perm), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pendingPermissionScan", permissionScanPending)
        outState.putBoolean("permissionRequestInFlight", permissionRequestInFlight)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        Log.i(MAIN_TAG, "onDestroy() called. isFinishing = $isFinishing")
        try {
            connectivityObserver.unregister()
        } catch (e: UninitializedPropertyAccessException) {
            // lol
        }
        // https://github.com/androidx/media/issues/805
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE && (playerConnection?.player?.playWhenReady != true || playerConnection?.player?.mediaItemCount == 0)) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(MusicService.NOTIFICATION_ID)
        }
        lifecycle.removeObserver(controllerViewModel)
        playerConnection = null

        super.onDestroy()
    }

    @SuppressLint("UnusedBoxWithConstraintsScope")
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lastObservedAudioPermission = checkSelfPermission(MEDIA_PERMISSION_LEVEL) == PackageManager.PERMISSION_GRANTED
        permissionScanPending = savedInstanceState?.getBoolean("pendingPermissionScan") == true &&
            lastObservedAudioPermission == true
        permissionRequestInFlight = savedInstanceState?.getBoolean("permissionRequestInFlight") == true
        if (permissionScanPending) audioPermissionResults.trySend(true)
        lifecycle.addObserver(controllerViewModel)
        controllerViewModel.addControllerCallback(lifecycle) { controller, _ ->
            playerConnection = PlayerConnection(controllerViewModel, database)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            Log.v(MAIN_TAG, "RC-1")
            val coroutineScope = rememberCoroutineScope()
            val haptic = LocalHapticFeedback.current
            val snackbarHostState = remember { SnackbarHostState() }
            val appUpdateState by appUpdateViewModel.state.collectAsState()

            val enableDynamicTheme by rememberPreference(DynamicThemeKey, defaultValue = true)
            val darkTheme by rememberEnumPreference(DarkModeKey, defaultValue = DarkMode.AUTO)
            val highContrastCompat by rememberPreference(HighContrastKey, defaultValue = false)
            val pureBlack by rememberPreference(PureBlackKey, defaultValue = false)
            val isSystemInDarkTheme = isSystemInDarkTheme()
            val useDarkTheme = remember(darkTheme, isSystemInDarkTheme) {
                if (darkTheme == DarkMode.AUTO) isSystemInDarkTheme else darkTheme == DarkMode.ON
            }

            val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
            val tabMode = this@MainActivity.tabMode()
            val useNavRail by remember {
                derivedStateOf {
                    windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED && !tabMode
                }
            }


            var filter by rememberEnumPreference(LibraryFilterKey, Screens.LibraryFilter.ALL)
            val (slimNav) = rememberPreference(SlimNavBarKey, defaultValue = false)
            val (enabledTabs) = rememberPreference(EnabledTabsKey, defaultValue = DEFAULT_ENABLED_TABS)
            val navigationItems = remember {
                Screens.getScreens(enabledTabs)
            }
            val (defaultOpenTab, onDefaultOpenTabChange) = rememberPreference(
                DefaultOpenTabKey,
                defaultValue = Screens.Home.route
            )




            LaunchedEffect(Unit) {
                var handledPermissionGeneration = -1L

                suspend fun runScan(trigger: ScanTrigger) {
                    startupScanComplete = false
                    val result = withContext(lmScannerCoroutine) {
                        scanInit(
                            this@MainActivity, database, downloadUtil, coroutineScope, playerConnection,
                            snackbarHostState, trigger,
                        )
                    }
                    if (result.localScanAttempted || trigger == ScanTrigger.AUDIO_PERMISSION_GRANTED) {
                        // 启动已实际扫描时，合并下载扫描期间到达的授权事件。
                        handledPermissionGeneration = permissionScanGeneration
                        permissionScanPending = false
                    }
                    waitingForAudioPermission = result.status == ScanStatus.WAITING_PERMISSION && !audioPermissionDenied
                    startupScanComplete = !waitingForAudioPermission && !permissionScanPending
                    if (waitingForAudioPermission) {
                        observeAudioPermission(
                            checkSelfPermission(MEDIA_PERMISSION_LEVEL) == PackageManager.PERMISSION_GRANTED,
                        )
                        if (!permissionScanPending && !permissionRequestInFlight) {
                            permissionRequestInFlight = true
                            permissionLauncher.launch(MEDIA_PERMISSION_LEVEL)
                        }
                    }
                }

                // local media & download folders auto scan
                runScan(ScanTrigger.STARTUP)
                for (granted in audioPermissionResults) {
                    if (!granted) {
                        waitingForAudioPermission = false
                        permissionScanPending = false
                        startupScanComplete = true
                    } else if (permissionScanPending || permissionScanGeneration > handledPermissionGeneration) {
                        runScan(ScanTrigger.AUDIO_PERMISSION_GRANTED)
                    }
                }
            }


            // 下载失败提示：以前下载失败是静默的，用户看不到任何反馈
            LaunchedEffect(Unit) {
                downloadUtil.downloadFailures.collect { failures ->
                    if (failures.isEmpty()) return@collect

                    val first = failures.first()
                    val message = if (failures.size == 1) {
                        getString(R.string.download_failed_single, first.title, first.reason)
                    } else {
                        getString(R.string.download_failed_multiple, failures.size, first.title, first.reason)
                    }

                    val result = snackbarHostState.showSnackbar(
                        message = message,
                        actionLabel = getString(R.string.download_retry),
                        withDismissAction = true,
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        failures.forEach { downloadUtil.retryFailedDownload(it.songId) }
                    }
                }
            }

            LaunchedEffect(useDarkTheme) {
                setSystemBarAppearance(useDarkTheme)
            }
            try {
                connectivityObserver.unregister()
            } catch (e: UninitializedPropertyAccessException) {
                // lol
            }
            connectivityObserver = NetworkConnectivityObserver(this@MainActivity)
            val isNetworkConnected by connectivityObserver.networkStatus.collectAsState(true)


            OuterTuneTheme(
                context = this@MainActivity,
                playerConnection = playerConnection,
                enableDynamicTheme = enableDynamicTheme,
                isSystemInDarkTheme = isSystemInDarkTheme,
                darkTheme = useDarkTheme,
                pureBlack = pureBlack,
                highContrastCompat = highContrastCompat,
            ) {
                Log.v(MAIN_TAG, "RC-2.1")
                val density = LocalDensity.current
                val windowsInsets = WindowInsets.systemBars
                val bottomInset = with(density) { windowsInsets.getBottom(density).toDp() }
                val cutoutInsets = WindowInsets.displayCutout

                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()

                val tabOpenedFromShortcut = remember {
                    // reroute to library page for new layout is handled in NavHost section
                    when (intent?.action) {
                        ACTION_SONGS -> if (navigationItems.contains(Screens.Songs)) Screens.Songs else Screens.Library
                        ACTION_ALBUMS -> if (navigationItems.contains(Screens.Albums)) Screens.Albums else Screens.Library
                        ACTION_PLAYLISTS -> if (navigationItems.contains(Screens.Playlists)) Screens.Playlists else Screens.Library
                        else -> null
                    }
                }
                // setup filters for new layout
                if (tabOpenedFromShortcut != null && navigationItems.contains(Screens.Library)) {
                    filter = when (intent?.action) {
                        ACTION_SONGS -> Screens.LibraryFilter.SONGS
                        ACTION_ALBUMS -> Screens.LibraryFilter.ALBUMS
                        ACTION_PLAYLISTS -> Screens.LibraryFilter.PLAYLISTS
                        ACTION_SEARCH -> {
                            navController.navigate("search")
                            filter
                        } // do change filter for search
                        else -> Screens.LibraryFilter.ALL
                    }
                }

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    val maxW = maxWidth
                    Log.v(MAIN_TAG, "RC-2.2")

                    fun getNavPadding(): Dp {
                        return 0.dp
                    }

                    val playerBottomSheetState = rememberBottomSheetState(
                        dismissedBound = 0.dp,
                        collapsedBound = bottomInset + MiniPlayerHeight + getNavPadding(),
                        expandedBound = maxHeight,
                    )

                    val playerAwareWindowInsets =
                        remember(
                            bottomInset,
                            playerBottomSheetState.isDismissed,
                        ) {
                            var bottom = bottomInset

                            if (!playerBottomSheetState.isDismissed) bottom += MiniPlayerHeight
                            windowsInsets
                                .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
                                .add(cutoutInsets.only(WindowInsetsSides.Horizontal))
                                .add(WindowInsets(top = AppBarHeight, bottom = bottom))
                        }

                    val scrollBehavior = appBarScrollBehavior(
                        canScroll = {
                            navBackStackEntry?.destination?.route?.startsWith("search/") == false &&
                                    (playerBottomSheetState.isCollapsed || playerBottomSheetState.isDismissed)
                        }
                    )


                    val menuSheetState = rememberModalBottomSheetState()
                    val menuState = remember(menuSheetState) { MenuState(menuSheetState) }
                    CompositionLocalProvider(
                        LocalDatabase provides database,
                        LocalContentColor provides contentColorFor(MaterialTheme.colorScheme.surface),
                        LocalMenuState provides menuState,
                        LocalPlayerConnection provides playerConnection,
                        LocalPlayerAwareWindowInsets provides playerAwareWindowInsets,
                        LocalDownloadUtil provides downloadUtil,
                        LocalShimmerTheme provides ShimmerTheme,
                        LocalNetworkConnected provides isNetworkConnected,
                        LocalSnackbarHostState provides snackbarHostState,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                        ) {
                            Log.v(MAIN_TAG, "RC-3")
                            val drawerState = rememberDrawerState(DrawerValue.Closed)

                            LaunchedEffect(navBackStackEntry?.destination?.route) {
                                if (navBackStackEntry?.destination?.route == Screens.Radio.route) {
                                    playerConnection?.softKillPlayer()
                                    playerBottomSheetState.dismiss()
                                }
                            }


                            val navHost: @Composable() (() -> Unit) = @Composable {
                                NavHost(
                                    navController = navController,
                                    startDestination = Screens.Songs.route,
                                    enterTransition = {
                                        val currentRouteIndex = navigationItems.indexOfFirst {
                                            it.route == targetState.destination.route
                                        }
                                        val previousRouteIndex = navigationItems.indexOfFirst {
                                            it.route == initialState.destination.route
                                        }

                                        if (currentRouteIndex == -1 || currentRouteIndex > previousRouteIndex)
                                            slideInHorizontally { it / 8 } + fadeIn(tween(200))
                                        else
                                            slideInHorizontally { -it / 8 } + fadeIn(tween(200))
                                    },
                                    exitTransition = {
                                        val currentRouteIndex = navigationItems.indexOfFirst {
                                            it.route == initialState.destination.route
                                        }
                                        val targetRouteIndex = navigationItems.indexOfFirst {
                                            it.route == targetState.destination.route
                                        }

                                        if (targetRouteIndex == -1 || targetRouteIndex > currentRouteIndex)
                                            slideOutHorizontally { -it / 8 } + fadeOut(tween(100))
                                        else
                                            slideOutHorizontally { it / 8 } + fadeOut(tween(100))
                                    },
                                    popEnterTransition = {
                                        val currentRouteIndex = navigationItems.indexOfFirst {
                                            it.route == targetState.destination.route
                                        }
                                        val previousRouteIndex = navigationItems.indexOfFirst {
                                            it.route == initialState.destination.route
                                        }

                                        if (previousRouteIndex != -1 && previousRouteIndex < currentRouteIndex)
                                            slideInHorizontally { it / 8 } + fadeIn(tween(200))
                                        else
                                            slideInHorizontally { -it / 8 } + fadeIn(tween(200))
                                    },
                                    popExitTransition = {
                                        val currentRouteIndex = navigationItems.indexOfFirst {
                                            it.route == initialState.destination.route
                                        }
                                        val targetRouteIndex = navigationItems.indexOfFirst {
                                            it.route == targetState.destination.route
                                        }

                                        if (currentRouteIndex != -1 && currentRouteIndex < targetRouteIndex)
                                            slideOutHorizontally { -it / 8 } + fadeOut(tween(100))
                                        else
                                            slideOutHorizontally { it / 8 } + fadeOut(tween(100))
                                    },
                                    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                                )
                                {
                                    composable(Screens.Home.route) {
                                        HomeScreen(navController)
                                    }
                                    composable(Screens.Songs.route) {
                                        LibrarySongsScreen(navController)
                                    }
                                    composable(Screens.Folders.route) {
                                        LibraryFoldersScreen(navController, scrollBehavior)
                                    }
                                    composable(
                                        route = "${Screens.Folders.route}/{path}",
                                        arguments = listOf(
                                            navArgument("path") {
                                                type = NavType.StringType
                                            }
                                        )
                                    ) {
                                        FolderScreen(navController, scrollBehavior)
                                    }
                                    composable(Screens.Artists.route) {
                                        LibraryArtistsScreen(navController)
                                    }
                                    composable(Screens.Albums.route) {
                                        LibraryAlbumsScreen(navController)
                                    }
                                    composable(Screens.Playlists.route) {
                                        LibraryPlaylistsScreen(navController)
                                    }
                                    composable(Screens.Radio.route) {
                                        RadioScreen(navController, scrollBehavior)
                                    }
                                    composable(Screens.Library.route) {
                                        LibraryScreen(navController, scrollBehavior)
                                    }
                                    composable(Screens.Player.route) {
                                        PlayerScreen(navController, bottomPadding = getNavPadding())
                                    }
                                    composable("history") {
                                        HistoryScreen(navController, showSongFilters = true)
                                    }
                                    composable("stats") {
                                        StatsScreen(navController)
                                    }
                                    composable(
                                        route = "search",
                                    ) {
                                        SearchBarContainer(navController, scrollBehavior)
                                    }
                                    composable(
                                        route = "search/{query}",
                                        arguments = listOf(
                                            navArgument("query") {
                                                type = NavType.StringType
                                            }
                                        )
                                    ) {
                                        OnlineSearchResult(navController)
                                    }
                                    composable(
                                        route = "album/{albumId}",
                                        arguments = listOf(
                                            navArgument("albumId") {
                                                type = NavType.StringType
                                            },
                                        )
                                    ) {
                                        AlbumScreen(navController, scrollBehavior)
                                    }
                                    composable(
                                        route = "artist/{artistId}",
                                        arguments = listOf(
                                            navArgument("artistId") {
                                                type = NavType.StringType
                                            }
                                        )
                                    ) {
                                        ArtistScreen(navController, scrollBehavior)
                                    }
                                    composable(
                                        route = "artist/{artistId}/songs",
                                        arguments = listOf(
                                            navArgument("artistId") {
                                                type = NavType.StringType
                                            }
                                        )
                                    ) {
                                        ArtistSongsScreen(navController, scrollBehavior)
                                    }
                                    composable(
                                        route = "artist/{artistId}/albums",
                                        arguments = listOf(
                                            navArgument("artistId") {
                                                type = NavType.StringType
                                            }
                                        )
                                    ) {
                                        ArtistAlbumsScreen(navController, scrollBehavior)
                                    }
                                    composable(
                                        route = "local_playlist/{playlistId}",
                                        arguments = listOf(
                                            navArgument("playlistId") {
                                                type = NavType.StringType
                                            }
                                        )
                                    ) {
                                        LocalPlaylistScreen(navController, scrollBehavior)
                                    }
                                    composable(
                                        route = "auto_playlist/{playlistId}",
                                        arguments = listOf(
                                            navArgument("playlistId") {
                                                type = NavType.StringType
                                            }
                                        )
                                    ) {
                                        AutoPlaylistScreen(navController, scrollBehavior)
                                    }
                                    composable("settings") {
                                        SettingsScreen(navController, scrollBehavior)
                                    }
                                    composable("settings/appearance") {
                                        AppearanceSettings(navController, scrollBehavior)
                                    }
                                    composable("settings/equalizer") {
                                        EqualizerScreen(navController, scrollBehavior)
                                    }
                                    composable("settings/lx_source") {
                                        LxSourceSettings(navController, scrollBehavior)
                                    }
                                    composable("settings/radio") {
                                        RadioSettingsScreen(navController, scrollBehavior)
                                    }
                                    composable("settings/storage") {
                                        StorageSettings(navController, scrollBehavior)
                                    }
                                    composable("settings/local") {
                                        LocalFoldersSettings(navController, scrollBehavior)
                                    }
                                    composable("settings/local/folders") {
                                        LocalFoldersSettings(navController, scrollBehavior)
                                    }
                                    composable("settings/about") {
                                        AboutScreen(navController, scrollBehavior, appUpdateViewModel)
                                    }
                                    composable("settings/about/attribution") {
                                        AttributionScreen(navController, scrollBehavior)
                                    }
                                    composable("settings/about/oss_licenses") {
                                        LibrariesScreen(navController, scrollBehavior)
                                    }
                                }
                            }

                            val navbar: @Composable() (() -> Unit) = @Composable {
                                val navigationBarHeight by animateDpAsState(
                                    targetValue = NavigationBarHeight,
                                    animationSpec = NavigationBarAnimationSpec,
                                    label = ""
                                )

                                NavigationBar(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .height(bottomInset + getNavPadding())
                                        .offset {
                                            if (navigationBarHeight == 0.dp) {
                                                IntOffset(
                                                    x = 0,
                                                    y = (bottomInset + NavigationBarHeight).roundToPx()
                                                )
                                            } else {
                                                val slideOffset =
                                                    (bottomInset + NavigationBarHeight) * playerBottomSheetState.progress.coerceIn(
                                                        0f,
                                                        1f
                                                    )
                                                val hideOffset =
                                                    (bottomInset + NavigationBarHeight) * (1 - navigationBarHeight / NavigationBarHeight)
                                                IntOffset(
                                                    x = 0,
                                                    y = (slideOffset + hideOffset).roundToPx()
                                                )
                                            }
                                        },
                                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp)
                                ) {
                                    navigationItems.fastForEach { screen ->
                                        // TODO: display selection when based on root page user entered
//                                        val isSelected = navBackStackEntry?.destination?.hierarchy?.any {
//                                            it.route?.substringBefore("?")?.substringBefore("/") == screen.route
//                                        } == true
                                        NavigationBarItem(
                                            selected = navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true,
                                            icon = {
                                                Icon(
                                                    screen.icon,
                                                    contentDescription = null
                                                )
                                            },
                                            label = {
                                                if (!slimNav) {
                                                    Text(
                                                        text = stringResource(screen.titleId),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            },
                                            onClick = {
                                                if (playerBottomSheetState.isExpanded) {
                                                    playerBottomSheetState.collapseSoft()
                                                }

                                                if (navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true) {
                                                    navBackStackEntry?.savedStateHandle?.set(
                                                        "scrollToTop",
                                                        true
                                                    )
                                                } else if (navigationItems.none { scr -> navBackStackEntry?.destination?.hierarchy?.any { it.route == scr.route } == true }) {
                                                    // this eye bleach allows you to navigate back when you tap on the navbar on a non-root page
                                                    // TODO: nav3 allows us to access back stack... maybe do indicators properly and remove this hack
                                                    navController.navigateUp()
                                                } else {
                                                    navController.navigate(screen.route) {
                                                        popUpTo(navController.graph.startDestinationId) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }

                                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            }
                                        )
                                    }
                                }
                            }

                            @Composable
                            fun navRail(alignment: Alignment) {
                                val layoutDirection = LocalLayoutDirection.current
                                val navigationBarHeight by animateDpAsState(
                                    targetValue = NavigationBarHeight,
                                    animationSpec = NavigationBarAnimationSpec,
                                    label = ""
                                )
                                val leftInset = remember {
                                    derivedStateOf {
                                        playerAwareWindowInsets.getLeft(density, layoutDirection).dp
                                    }
                                }
                                NavigationRail(
                                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(6.dp),
                                    header = {
                                        Spacer(Modifier.height(8.dp))
                                        Image(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .padding(start = 8.dp),
                                            painter = painterResource(R.drawable.app_logo),
                                            contentDescription = null
                                        )
                                    },
                                    modifier = Modifier
                                        .align(alignment)
                                        .fillMaxHeight()
                                        .verticalScroll(rememberScrollState())
                                        .offset {
                                            if (navigationBarHeight == 0.dp) {
                                                IntOffset(
                                                    x = 0,
                                                    y = (bottomInset + NavigationBarHeight).roundToPx()
                                                )
                                            } else {
                                                val slideOffset =
                                                    (bottomInset + NavigationBarHeight + leftInset.value) *
                                                            playerBottomSheetState.progress.coerceIn(0f, 1f)
                                                val hideOffset =
                                                    (bottomInset + NavigationBarHeight) * (1 - navigationBarHeight / NavigationBarHeight)
                                                IntOffset(
                                                    x = -(slideOffset + hideOffset).roundToPx(),
                                                    y = 0
                                                )
                                            }
                                        },
                                ) {
                                    navigationItems.fastForEach { screen ->
                                        // TODO: display selection when based on root page user entered
//                                                val isSelected = navBackStackEntry?.destination?.hierarchy?.any {
//                                                    it.route?.substringBefore("?")?.substringBefore("/") == screen.route
//                                                } == true
                                        NavigationRailItem(
                                            selected = navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true,
                                            icon = {
                                                Icon(
                                                    screen.icon,
                                                    contentDescription = null
                                                )
                                            },
                                            label = {
                                                if (!slimNav) {
                                                    Text(
                                                        text = stringResource(screen.titleId),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            },
                                            onClick = {
                                                if (playerBottomSheetState.isExpanded) {
                                                    playerBottomSheetState.collapseSoft()
                                                }
                                                if (navBackStackEntry?.destination?.hierarchy?.any { it.route == screen.route } == true) {
                                                    navBackStackEntry?.savedStateHandle?.set(
                                                        "scrollToTop",
                                                        true
                                                    )
                                                } else {
                                                    navController.navigate(screen.route) {
                                                        popUpTo(navController.graph.startDestinationId) {
                                                            saveState = true
                                                        }

                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }

                                                haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            }
                                        )
                                    }
                                }
                            }

                            val bottomSheetMenu: @Composable() (() -> Unit) = @Composable {
                                BottomSheetMenu(
                                    state = LocalMenuState.current,
                                    modifier = Modifier.align(Alignment.BottomCenter)
                                )
                            }

                            ModalNavigationDrawer(
                                drawerState = drawerState,
                                drawerContent = {
                                    MainNavigationDrawer(
                                        currentRoute = navBackStackEntry?.destination?.route,
                                        hasUnreadUpdate = appUpdateState.hasUnreadUpdate,
                                        onNavigate = { route ->
                                            if (route == Screens.Radio.route) {
                                                playerConnection?.softKillPlayer()
                                                playerBottomSheetState.dismiss()
                                            } else if (playerBottomSheetState.isExpanded) {
                                                playerBottomSheetState.collapseSoft()
                                            }
                                            if (navBackStackEntry?.destination?.route == route) {
                                                navBackStackEntry?.savedStateHandle?.set("scrollToTop", true)
                                            } else {
                                                navController.navigate(route) {
                                                    popUpTo(navController.graph.startDestinationId) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                                            coroutineScope.launch { drawerState.close() }
                                        },
                                    )
                                },
                            ) {
                                Box(Modifier.fillMaxSize()) {
                                    navHost()
                                    SearchBarContainer(navController, scrollBehavior)
                                    MainNavigationTopBar(
                                        currentRoute = navBackStackEntry?.destination?.route,
                                        onOpenDrawer = {
                                            if (playerBottomSheetState.isExpanded) {
                                                playerBottomSheetState.collapseSoft()
                                            }
                                            coroutineScope.launch { drawerState.open() }
                                        },
                                        onSearch = {
                                            navController.navigate("search") {
                                                launchSingleTop = true
                                            }
                                        },
                                        scrollBehavior = scrollBehavior,
                                    )

                                    BottomSheetPlayer(
                                        state = playerBottomSheetState,
                                        navController = navController,
                                    )
                                    bottomSheetMenu()

                                    SnackbarHost(
                                        hostState = snackbarHostState,
                                        modifier = Modifier
                                            .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                                            .align(Alignment.BottomCenter),
                                    )
                                }
                            }

                            AppUpdatePrompt(
                                state = appUpdateState,
                                viewModel = appUpdateViewModel,
                                canShow = isActivityResumed && hasActivityWindowFocus && startupScanComplete &&
                                    navBackStackEntry?.destination?.route == Screens.Songs.route &&
                                    playerBottomSheetState.isCollapsed &&
                                    drawerState.currentValue == DrawerValue.Closed &&
                                    drawerState.targetValue == DrawerValue.Closed &&
                                    !menuState.isVisible && snackbarHostState.currentSnackbarData == null,
                                onViewUpdate = {
                                    navController.navigate("settings/about") { launchSingleTop = true }
                                },
                            )

                            if (BuildConfig.DEBUG) {
                                val debugColour = Color.Red
                                Column(
                                    modifier = Modifier.padding(start = 50.dp, top = 100.dp)
                                ) {
                                    Text(
                                        text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | ${BuildConfig.FLAVOR}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = debugColour
                                    )
                                    Text(
                                        text = "${BuildConfig.APPLICATION_ID} | ${BuildConfig.BUILD_TYPE}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = debugColour
                                    )
                                    Text(
                                        text = "${Build.BRAND} ${Build.DEVICE} (${Build.MODEL})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = debugColour
                                    )
                                    Text(
                                        text = "${Build.VERSION.SDK_INT} (${Build.ID})",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = debugColour
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setSystemBarAppearance(isDark: Boolean) {
        WindowCompat.getInsetsController(window, window.decorView.rootView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }

        // sdk24 support
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            window.navigationBarColor = (if (isDark) Color.Transparent else Color.Black.copy(alpha = 0.2f)).toArgb()
        }
    }

    companion object {
        const val ACTION_SEARCH = "com.dd3boh.outertune.action.SEARCH"
        const val ACTION_SONGS = "com.dd3boh.outertune.action.SONGS"
        const val ACTION_ALBUMS = "com.dd3boh.outertune.action.ALBUMS"
        const val ACTION_PLAYLISTS = "com.dd3boh.outertune.action.PLAYLISTS"
    }
}

val LocalDatabase = staticCompositionLocalOf<MusicDatabase> { error("No database provided") }
val LocalMenuState = staticCompositionLocalOf<MenuState> { error("No menu state provided") }
val LocalPlayerConnection = staticCompositionLocalOf<PlayerConnection?> { error("No PlayerConnection provided") }
val LocalPlayerAwareWindowInsets = compositionLocalOf<WindowInsets> { error("No player WindowInsets provided") }
val LocalDownloadUtil = staticCompositionLocalOf<DownloadUtil> { error("No DownloadUtil provided") }
val LocalNetworkConnected = staticCompositionLocalOf<Boolean> { error("No Network Status provided") }
val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState> { error("No SnackbarHostState provided") }
