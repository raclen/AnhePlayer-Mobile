package com.dd3boh.outertune.source.kugou

import com.dd3boh.outertune.models.MediaMetadata

const val KUGOU_CONCEPT_SOURCE = "kg_concept"
const val KUGOU_CONCEPT_ID_PREFIX = "kgc_"

enum class KugouAccountStatus { SIGNED_OUT, RESTORING, SIGNED_IN, EXPIRED }
enum class KugouClaimStatus { IDLE, CHECKING, CLAIMED, ALREADY_CLAIMED, INELIGIBLE, ERROR, NEED_LOGIN, NEED_OFFICIAL_APP }

data class KugouAccountState(
    val status: KugouAccountStatus = KugouAccountStatus.SIGNED_OUT,
    val userId: String? = null,
    val nickname: String = "",
    val vipDescription: String = "",
    val autoClaimVip: Boolean = true,
    val claimStatus: KugouClaimStatus = KugouClaimStatus.IDLE,
    val claimMessage: String = "",
    val lastClaimCheck: Long? = null,
    val error: String? = null,
)

data class KugouQrLogin(val key: String, val url: String)
data class KugouQrStatus(val status: Int, val message: String = "")
data class KugouLoginAccount(val userId: String, val nickname: String)
class KugouAccountSelectionException(val accounts: List<KugouLoginAccount>) : Exception("请选择要登录的酷狗账号")

// 卡片只承载展示信息；进入队列的歌曲仍使用统一 MediaMetadata。
data class KugouCollection(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
    val description: String = "",
    val songs: List<MediaMetadata> = emptyList(),
)

data class KugouHomeData(
    val radioSongs: List<MediaMetadata> = emptyList(),
    val dailySongs: List<MediaMetadata> = emptyList(),
    val playlists: List<KugouCollection> = emptyList(),
    val ranks: List<KugouCollection> = emptyList(),
    val newSongs: List<MediaMetadata> = emptyList(),
    val errors: Map<String, String> = emptyMap(),
)

data class KugouSongPage(
    val songs: List<MediaMetadata>,
    val hasMore: Boolean,
    val title: String = "",
    val description: String = "",
    val coverUrl: String? = null,
)

data class KugouCollectionPage(val collections: List<KugouCollection>, val hasMore: Boolean)
enum class KugouSearchType { SONG, PLAYLIST }
enum class KugouCollectionType { PLAYLIST, RANK }
enum class KugouPlaybackAccess { FULL, PREVIEW, UNKNOWN }

data class KugouResolvedStream(
    val urls: List<String>,
    val expiresAt: Long,
    val access: KugouPlaybackAccess = KugouPlaybackAccess.UNKNOWN,
    val headers: Map<String, String> = emptyMap(),
    val sessionVersion: Long = 0,
)
