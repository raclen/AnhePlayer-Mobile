package com.dd3boh.outertune.source.kugou

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.dd3boh.outertune.utils.AppLogManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
internal data class KugouSession(
    val guid: String = UUID.randomUUID().toString().replace("-", ""),
    val dfid: String = "",
    val userId: String = "0",
    val token: String = "",
    val t1: String = "",
    val nickname: String = "",
    val vipType: String = "0",
    val vipToken: String = "",
    val renewedAt: Long = 0,
) {
    val mid: String get() = KugouCrypto.mid(guid)
    val loggedIn: Boolean get() = userId != "0" && userId.isNotBlank() && token.isNotBlank()
}

internal class KugouSessionStore(context: Context) {
    private val TAG = "KugouSessionStore"
    private val prefs = context.getSharedPreferences("kugou_concept_secure", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val keyAlias = "anhe.kugou.session.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): KugouSession {
        val encoded = prefs.getString("session", null) ?: return KugouSession()
        return try {
            val bytes = KugouCrypto.decodeBase64(encoded)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            json.decodeFromString<KugouSession>(cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // 换机或 Keystore 丢失时不能把不可解密的凭据当成有效会话。
            AppLogManager.w(TAG, "酷狗会话解密失败，已清除本地凭据（可能是换机或系统密钥库丢失）", e)
            prefs.edit().remove("session").commit()
            KugouSession()
        }
    }
    @Synchronized fun write(session: KugouSession) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.encodeToString(session).toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("session", KugouCrypto.encodeBase64(bytes)).commit()) { "酷狗账号保存失败" }
    }
}
