package com.dd3boh.outertune.source.kugou

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object KugouRadioProtocol {
    const val PATH = "/v2/personal_recommend"
    const val ROUTER = "persnfm.service.kugou.com"

    fun body(session: KugouSession, time: Long, mode: KugouRadioMode): JsonObject = buildJsonObject {
        put("appid", 3116); put("clienttime", time); put("mid", session.mid); put("action", "play")
        put("recommend_source_locked", 0); put("song_pool_id", 0); put("callerid", 0); put("m_type", 1)
        put("platform", "ios"); put("area_code", 1); put("remain_songcnt", 0); put("clientver", 11440)
        put("is_overplay", 0); put("mode", mode.apiValue); put("key", KugouCrypto.parameterKey(time.toString()))
        put("userid", session.userId); put("kguid", session.userId); put("token", session.token)
    }
}
