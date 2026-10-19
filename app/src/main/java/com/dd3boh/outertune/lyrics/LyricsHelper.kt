package com.dd3boh.outertune.lyrics

import android.content.Context
import android.util.LruCache
import com.dd3boh.outertune.constants.LyricSourcePrefKey
import com.dd3boh.outertune.constants.LyricTrimKey
import com.dd3boh.outertune.constants.MultilineLrcKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.LyricsEntity
import com.dd3boh.outertune.db.entities.LyricsEntity.Companion.LYRICS_NOT_FOUND
import com.dd3boh.outertune.models.MediaMetadata
import com.dd3boh.outertune.source.lx.LxSourceRuntime
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import com.dd3boh.outertune.utils.reportException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.akanework.gramophone.logic.utils.LrcUtils
import org.akanework.gramophone.logic.utils.SemanticLyrics
import org.akanework.gramophone.logic.utils.parseLrc
import javax.inject.Inject

class LyricsHelper @Inject constructor(
    @ApplicationContext private val context: Context,
    val database: MusicDatabase
) {
    // 歌词来源统一走洛雪音源；这里只保留 lrclib 作为洛雪没返回时的兜底（不再使用酷狗那套）
    private val lyricsProviders = listOf(LrcLibLyricsProvider)
    private val cache = LruCache<String, List<LyricsResult>>(MAX_CACHE_SIZE)

    /**
     * 获取歌曲歌词。
     *
     * 内嵌歌词始终优先；不存在内嵌歌词时，再根据本地歌词偏好决定同目录歌词、数据库缓存和在线源的顺序。
     *
     * @param mediaMetadata 待获取歌词的歌曲
     */
    suspend fun getLyrics(mediaMetadata: MediaMetadata): SemanticLyrics? =
        getLyricsWithSource(mediaMetadata).lyrics

    /**
     * 与 [getLyrics] 相同，额外返回歌词来源，供播放页显示来源标签。
     */
    suspend fun getLyricsWithSource(mediaMetadata: MediaMetadata): LoadedLyrics {
        val trim = context.dataStore.get(LyricTrimKey, defaultValue = false)
        val multiline = context.dataStore.get(MultilineLrcKey, defaultValue = true)

        val prefLocal = context.dataStore.get(LyricSourcePrefKey, true)

        val parserOptions = LrcUtils.LrcParserOptions(trim, multiline, "Unable to parse lyrics")
        getEmbeddedLyrics(mediaMetadata, parserOptions)?.let {
            return LoadedLyrics(it, LyricsSource.EMBEDDED)
        }

        val cached = cache.get(mediaMetadata.id)?.firstOrNull()
        if (cached != null) {
            return LoadedLyrics(parseLrc(cached.lyrics, trim, multiline), LyricsSource.ONLINE)
        }
        val dbLyrics = database.lyrics(mediaMetadata.id).let { it.first()?.lyrics }
        if (dbLyrics != null && !prefLocal) {
            return LoadedLyrics(parseLrc(dbLyrics, trim, multiline), LyricsSource.ONLINE)
        }

        val localLyrics: SemanticLyrics? =
            getLocalLyrics(mediaMetadata, parserOptions)
        val remoteLyrics: String?

        // fallback to secondary provider when primary is unavailable
        if (prefLocal) {
            if (localLyrics != null) {
                return LoadedLyrics(localLyrics, LyricsSource.LOCAL)
            }
            if (dbLyrics != null) {
                return LoadedLyrics(parseLrc(dbLyrics, trim, multiline), LyricsSource.ONLINE)
            }

            // "lazy eval" the remote lyrics cuz it is laughably slow
            remoteLyrics = getRemoteLyrics(mediaMetadata)
            if (remoteLyrics != null) {
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = remoteLyrics
                        )
                    )
                }
                return LoadedLyrics(parseLrc(remoteLyrics, trim, multiline), LyricsSource.ONLINE)
            }
        } else {
            remoteLyrics = getRemoteLyrics(mediaMetadata)
            if (remoteLyrics != null) {
                database.query {
                    upsert(
                        LyricsEntity(
                            id = mediaMetadata.id,
                            lyrics = remoteLyrics
                        )
                    )
                }
                return LoadedLyrics(parseLrc(remoteLyrics, trim, multiline), LyricsSource.ONLINE)
            } else if (localLyrics != null) {
                return LoadedLyrics(localLyrics, LyricsSource.LOCAL)
            }

        }

        database.query {
            upsert(
                LyricsEntity(
                    id = mediaMetadata.id,
                    lyrics = LYRICS_NOT_FOUND
                )
            )
        }
        return LoadedLyrics(null, null)
    }

    /**
     * Lookup lyrics from remote providers
     */
    private suspend fun getRemoteLyrics(mediaMetadata: MediaMetadata): String? {
        // 洛雪音源优先
        if (mediaMetadata.source != null) {
            runCatching { LxSourceRuntime.resolveLyric(mediaMetadata) }
                .getOrNull()
                ?.let { return it }
        }
        lyricsProviders.forEach { provider ->
            if (provider.isEnabled(context)) {
                provider.getLyrics(
                    mediaMetadata.id,
                    mediaMetadata.title,
                    mediaMetadata.artists.joinToString { it.name },
                    mediaMetadata.duration
                ).onSuccess { lyrics ->
                    return lyrics
                }.onFailure {
                    reportException(it)
                }
            }
        }
        return null
    }

    /**
     * Lookup lyrics from local disk (.lrc) file
     */
    private fun getLocalLyrics(
        mediaMetadata: MediaMetadata,
        parserOptions: LrcUtils.LrcParserOptions
    ): SemanticLyrics? {
        if (LocalLyricsProvider.isEnabled(context) && mediaMetadata.localPath != null) {
            return LocalLyricsProvider.getLyricsNew(
                mediaMetadata.localPath,
                parserOptions
            )
        }

        return null
    }

    private fun getEmbeddedLyrics(
        mediaMetadata: MediaMetadata,
        parserOptions: LrcUtils.LrcParserOptions
    ): SemanticLyrics? {
        return mediaMetadata.localPath?.let { path ->
            LocalLyricsProvider.getEmbeddedLyrics(path, parserOptions)
        }
    }

    suspend fun getAllLyrics(
        mediaId: String,
        songTitle: String,
        songArtists: String,
        duration: Int,
        callback: (LyricsResult) -> Unit,
    ) {
        val cacheKey = "$songArtists-$songTitle".replace(" ", "")
        cache.get(cacheKey)?.let { results ->
            results.forEach {
                callback(it)
            }
            return
        }
        val allResult = mutableListOf<LyricsResult>()
        lyricsProviders.forEach { provider ->
            if (provider.isEnabled(context)) {
                provider.getAllLyrics(mediaId, songTitle, songArtists, duration) { lyrics ->
                    val result = LyricsResult(provider.name, lyrics)
                    allResult += result
                    callback(result)
                }
            }
        }
        cache.put(cacheKey, allResult)
    }

    companion object {
        private const val MAX_CACHE_SIZE = 3
    }
}

data class LyricsResult(
    val providerName: String,
    val lyrics: String,
)

/** 歌词来源，仅用于界面展示。 */
enum class LyricsSource {
    EMBEDDED, LOCAL, ONLINE
}

data class LoadedLyrics(
    val lyrics: SemanticLyrics?,
    val source: LyricsSource?,
)

/**
 * 播放页当前歌词。[mediaId] 用于识别歌词归属，避免切歌瞬间显示上一首的歌词。
 * [lyrics] 为 null 表示未找到歌词。
 */
data class PlayerLyrics(
    val mediaId: String,
    val lyrics: SemanticLyrics?,
    val source: LyricsSource?,
)
