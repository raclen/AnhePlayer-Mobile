package com.dd3boh.outertune.constants

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Appearance
 */
val DynamicThemeKey = booleanPreferencesKey("dynamicTheme")
val HighContrastKey = booleanPreferencesKey("highContrast")
val PlayerBackgroundStyleKey = stringPreferencesKey("playerBackgroundStyle")
val DarkModeKey = stringPreferencesKey("darkMode")
val PureBlackKey = booleanPreferencesKey("pureBlack")
val ShowLikedAndDownloadedPlaylist = booleanPreferencesKey("showLikedAndDownloadedPlaylist")
val SwipeToQueueKey = booleanPreferencesKey("swipeToQueue")
val FlatSubfoldersKey = booleanPreferencesKey("flatSubfolders")
val TabletUiKey = booleanPreferencesKey("tabletUi")

val EnabledTabsKey = stringPreferencesKey("enabledTabs")
val EnabledFiltersKey = stringPreferencesKey("enabledFilters")
val DefaultOpenTabKey = stringPreferencesKey("defaultOpenTab")
val SlimNavBarKey = booleanPreferencesKey("slimNavBar")

/**
 * Content
 */
const val SYSTEM_DEFAULT = "SYSTEM_DEFAULT"
//val LikedAutoDownloadKey = stringPreferencesKey("likedAutoDownloadKey")
val ProxyEnabledKey = booleanPreferencesKey("proxyEnabled")
val ProxyUrlKey = stringPreferencesKey("proxyUrl")
val ProxyTypeKey = stringPreferencesKey("proxyType")

/**
 * 洛雪音源
 */
val LxSourceScriptKey = stringPreferencesKey("lxSourceScript")
val LxSourceNameKey = stringPreferencesKey("lxSourceName")
val LxSourceQualityKey = stringPreferencesKey("lxSourceQuality")
val DownloadQualityKey = stringPreferencesKey("downloadQuality")



/**
 * Player & audio
 */
val AudioDecoderKey = intPreferencesKey("audioDecoder")
val AudioQualityKey = stringPreferencesKey("audioQuality")
val AudioOffloadKey = booleanPreferencesKey("enableOffload")
val AudioGaplessOffloadKey = booleanPreferencesKey("enableGaplessOffload")

val MaxQueuesKey = intPreferencesKey("maxQueues")
val PersistentQueueKey = booleanPreferencesKey("persistentQueue")

val SeekIncrementKey = stringPreferencesKey("seekIncrement")
val SkipSilenceKey = booleanPreferencesKey("skipSilence")
val SkipOnErrorKey = booleanPreferencesKey("skipOnError")
val AudioNormalizationKey = booleanPreferencesKey("audioNormalization")
val AutoLoadMoreKey = booleanPreferencesKey("autoLoadMore")
val KeepAliveKey = booleanPreferencesKey("keepAlive")
val StopMusicOnTaskClearKey = booleanPreferencesKey("stopMusicOnTaskClear")

val PlayerVolumeKey = floatPreferencesKey("playerVolume")
val EqualizerEnabledKey = booleanPreferencesKey("equalizerEnabled")
val EqualizerPresetKey = stringPreferencesKey("equalizerPreset")
val RepeatModeKey = intPreferencesKey("repeatMode")
val LockQueueKey = booleanPreferencesKey("lockQueue")
val minPlaybackDurKey = intPreferencesKey("minPlaybackDur")


/**
 * Lyrics
 */
val ShowLyricsKey = booleanPreferencesKey("showLyrics")
val LyricsTextPositionKey = stringPreferencesKey("lyricsTextPosition")
val MultilineLrcKey = booleanPreferencesKey("multilineLrc")
val LyricTrimKey = booleanPreferencesKey("lyricTrim")
val LyricSourcePrefKey = booleanPreferencesKey("preferLocalLyrics")
val LyricFontSizeKey = intPreferencesKey("lyricFontSize")
val LyricClickable = booleanPreferencesKey("lyricClickable")
val LyricKaraokeEnable = booleanPreferencesKey("lyricKaraokeEnable")
val LyricUpdateSpeed = stringPreferencesKey("lyricUpdateSpeed")


/**
 * Storage
 */
val DownloadExtraPathKey = stringPreferencesKey("dlExtraPath") // previously "downloadExtraPath"
val DownloadPathKey = stringPreferencesKey("dlPath") // previously "downloadPath"
val MaxImageCacheSizeKey = intPreferencesKey("maxImageCacheSize")
val MaxSongCacheSizeKey = intPreferencesKey("maxSongCacheSize")


/**
 * Privacy
 */
val PauseListenHistoryKey = booleanPreferencesKey("pauseListenHistory")
val PauseSearchHistoryKey = booleanPreferencesKey("pauseSearchHistory")
val EnableLrcLibKey = booleanPreferencesKey("enableLrcLib")


/**
 * Local library
 */
val LocalLibraryEnableKey = booleanPreferencesKey("localLibraryEnable")


/**
 * Local media scanner
 */
val AutomaticScannerKey = booleanPreferencesKey("autoLocalScanner")
val ScannerSensitivityKey = stringPreferencesKey("scannerSensitivity")
val ScannerImplKey = stringPreferencesKey("scannerImpl")
val ScannerStrictFilePathsKey = booleanPreferencesKey("scannerStrictFilePaths")
val ScannerStrictExtKey = booleanPreferencesKey("scannerStrictExt")

val ScanPathsKey = stringPreferencesKey("inclScanPaths") // previously "scanPaths"
val ExcludedScanPathsKey = stringPreferencesKey("exclScanPaths") // previously "excludedScanPaths"
// 隐藏的扫描文件夹（换行分隔的 SAF 目录 URI），由「隐藏文件夹」界面维护，扫描时整棵子树被跳过
val LastLocalScanKey = longPreferencesKey("lastLocalScan")

/**
 * Experimental settings
 */
val SwipeToSkipKey = booleanPreferencesKey("swipeToSkip")


/**
 * Non-settings UI preferences
 */
val SongSortTypeKey = stringPreferencesKey("songSortType")
val SongSortDescendingKey = booleanPreferencesKey("songSortDescending")
val FolderSortTypeKey = stringPreferencesKey("folderSortType")
val FolderSongSortTypeKey = stringPreferencesKey("folderSongSortType")
val FolderSongSortDescendingKey = booleanPreferencesKey("folderSongSortDescending")
val PlaylistSongSortTypeKey = stringPreferencesKey("playlistSongSortType")
val PlaylistSongSortDescendingKey = booleanPreferencesKey("playlistSongSortDescending")
val ArtistSortTypeKey = stringPreferencesKey("artistSortType")
val ArtistSortDescendingKey = booleanPreferencesKey("artistSortDescending")
val AlbumSortTypeKey = stringPreferencesKey("albumSortType")
val AlbumSortDescendingKey = booleanPreferencesKey("albumSortDescending")
val PlaylistSortTypeKey = stringPreferencesKey("playlistSortType")
val PlaylistSortDescendingKey = booleanPreferencesKey("playlistSortDescending")
val LibrarySortTypeKey = stringPreferencesKey("librarySortType")
val LibrarySortDescendingKey = booleanPreferencesKey("librarySortDescending")
val ArtistSongSortTypeKey = stringPreferencesKey("artistSongSortType")
val ArtistSongSortDescendingKey = booleanPreferencesKey("artistSongSortDescending")

val SongFilterKey = stringPreferencesKey("songFilter")
val ArtistFilterKey = stringPreferencesKey("artistFilter")
val ArtistViewTypeKey = stringPreferencesKey("artistViewType")
val AlbumFilterKey = stringPreferencesKey("albumFilter")
val PlaylistFilterKey = stringPreferencesKey("playlistFilter")
val AlbumViewTypeKey = stringPreferencesKey("albumViewType")
val PlaylistViewTypeKey = stringPreferencesKey("playlistViewType")
val LibraryFilterKey = stringPreferencesKey("libraryFilter")
val LibraryViewTypeKey = stringPreferencesKey("libraryViewType")

val PlaylistEditLockKey = booleanPreferencesKey("playlistEditLock")

val SearchSourceKey = stringPreferencesKey("searchSource")



/**
 * Misc
 */

// 更新检查独立保存缓存、弹窗与已读状态，不复用旧更新器的布尔标记。
val AppUpdateReleaseKey = stringPreferencesKey("appUpdateRelease")
val AppUpdateLastAttemptKey = longPreferencesKey("appUpdateLastAttempt")
val AppUpdateLastSuccessKey = longPreferencesKey("appUpdateLastSuccess")
val AppUpdatePromptedVersionKey = stringPreferencesKey("appUpdatePromptedVersion")
val AppUpdateViewedVersionKey = stringPreferencesKey("appUpdateViewedVersion")
