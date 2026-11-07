/* 请求协议参考 KuGouMusicApi，Copyright (c) 2023 MakcRe，MIT；完整通知见 assets/licenses/kugou-api-MIT.txt。 */
package com.dd3boh.outertune.source.kugou

import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object KugouCrypto {
    private const val ANDROID_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    private const val WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
    private const val PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"
    private val random = SecureRandom()
    fun randomKey(length: Int): String = buildString {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        repeat(length) { append(chars[random.nextInt(chars.length)]) }
    }
    fun md5(value: String): String = hex(MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)))
    fun mid(guid: String): String = BigInteger(1, MessageDigest.getInstance("MD5").digest(guid.toByteArray(Charsets.UTF_8))).toString()
    fun signature(params: Map<String, String>, body: String = "", web: Boolean = false): String {
        val salt = if (web) WEB_SALT else ANDROID_SALT
        return md5(salt + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + body + salt)
    }
    fun parameterKey(time: String): String = md5("3116${ANDROID_SALT}11440$time")
    fun streamKey(hash: String, mid: String, userId: String): String = md5("${hash}185672dd44712f60bb1736df5a377e82${3116}${mid}${userId}")
    fun encodeBase64(bytes: ByteArray): String = bytes.toByteString().base64()
    fun decodeBase64(value: String): ByteArray = requireNotNull(value.decodeBase64()) { "酷狗编码内容不正确" }.toByteArray()
    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0) { "酷狗加密响应格式不正确" }
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
    private fun aes(input: ByteArray, key: String, iv: String, decrypt: Boolean): ByteArray = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
        init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec(iv.toByteArray(Charsets.UTF_8)))
        doFinal(input)
    }
    fun encryptHex(text: String, key: String, iv: String): String = hex(aes(text.toByteArray(Charsets.UTF_8), key, iv, false))
    fun encryptLogin(text: String, key: String): String {
        val digest = md5(key)
        return encryptHex(text, digest, digest.takeLast(16))
    }
    fun decryptLogin(hex: String, key: String): String {
        val digest = md5(key)
        return aes(unhex(hex), digest, digest.takeLast(16), true).toString(Charsets.UTF_8)
    }
    fun encryptDevice(text: String, key: String): String {
        val digest = md5(key)
        return encodeBase64(aes(text.toByteArray(Charsets.UTF_8), digest.take(16), digest.takeLast(16), false))
    }
    fun decryptDevice(bytes: ByteArray, key: String): String {
        val digest = md5(key)
        return aes(bytes, digest.take(16), digest.takeLast(16), true).toString(Charsets.UTF_8)
    }
    fun rsa(text: String, raw: Boolean = false): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decodeBase64(PUBLIC_KEY)))
        val bytes = text.toByteArray(Charsets.UTF_8)
        // 上游登录协议为右侧补零的无填充 RSA，设备注册则使用 PKCS1。
        val input = if (raw) bytes.copyOf(128).also { require(bytes.size <= 128) { "酷狗登录参数过长" } } else bytes
        return Cipher.getInstance(if (raw) "RSA/ECB/NoPadding" else "RSA/ECB/PKCS1Padding").run {
            init(Cipher.ENCRYPT_MODE, key)
            hex(doFinal(input))
        }
    }
}
