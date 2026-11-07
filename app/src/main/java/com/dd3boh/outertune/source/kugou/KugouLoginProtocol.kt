/* Copyright (c) 2023 MakcRe, MIT. 协议适配通知见 assets/licenses/kugou-api-MIT.txt。 */
package com.dd3boh.outertune.source.kugou

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal object KugouLoginProtocol {
    fun renewBody(session: KugouSession, time: Long, device: String, key: String): JsonObject = buildJsonObject {
        put("dfid", session.dfid.ifBlank { "-" })
        put("p3", KugouCrypto.encryptHex(buildJsonObject {
            put("clienttime", time / 1000)
            put("token", session.token)
        }.toString(), "c24f74ca2820225badc01946dba4fdf7", "adc01946dba4fdf7"))
        put("plat", 1)
        put("t1", KugouCrypto.encryptHex("${session.t1}|$time", "5e4ef500e9597fe004bd09a46d8add98", "04bd09a46d8add98"))
        // 不伪造设备 MAC，沿用设备注册时的安装身份和公开设备型号。
        put("t2", KugouCrypto.encryptHex("${session.guid}|0f607264fc6318a92b9e13c65db7cd3c||$device|$time", "fd14b35e3f81af3817a20ae7adae7020", "17a20ae7adae7020"))
        put("t3", "MCwwLDAsMCwwLDAsMCwwLDA=")
        put("pk", KugouCrypto.rsa(buildJsonObject { put("clienttime_ms", time); put("key", key) }.toString(), raw = true).uppercase())
        put("params", KugouCrypto.encryptLogin("{}", key))
        put("userid", session.userId)
        put("clienttime_ms", time)
        put("dev", device)
    }

    fun unpack(data: JsonObject, key: String): JsonObject {
        val encrypted = data.text("secu_params")
        if (encrypted.isBlank()) return data
        val text = KugouCrypto.decryptLogin(encrypted, key)
        val decrypted = runCatching { kugouJson.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: buildJsonObject { put("token", text) }
        return JsonObject(data + decrypted)
    }
}
