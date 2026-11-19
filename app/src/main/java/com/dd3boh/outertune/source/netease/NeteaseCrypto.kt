package com.dd3boh.outertune.source.netease

import android.util.Base64
import java.math.BigInteger
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 WeAPI 加密实现
 *
 * 采用双重 AES-128-CBC 与无填充 RSA 模幂运算，与 PC 版 AnhePlayer 算法完全一致。
 */
object NeteaseCrypto {
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val RSA_MODULUS = BigInteger(
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7",
        16
    )
    private val RSA_EXPONENT = BigInteger("010001", 16)
    private val random = SecureRandom()

    private fun aesCbc(text: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(IV.toByteArray(Charsets.UTF_8))
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(text)
    }

    private fun randomSecretKey(): String {
        val sb = StringBuilder(16)
        for (i in 0 until 16) {
            sb.append(BASE62[random.nextInt(BASE62.length)])
        }
        return sb.toString()
    }

    private fun rsaNoPaddingHex(key: String): String {
        val reversed = key.toByteArray(Charsets.UTF_8).reversedArray()
        val keyBigInt = BigInteger(1, reversed)
        val encrypted = keyBigInt.modPow(RSA_EXPONENT, RSA_MODULUS)
        return encrypted.toString(16).padStart(256, '0')
    }

    data class WeapiEncrypted(
        val params: String,
        val encSecKey: String
    ) {
        fun toFormBody(): String {
            return "params=" + URLEncoder.encode(params, "UTF-8") +
                    "&encSecKey=" + URLEncoder.encode(encSecKey, "UTF-8")
        }
    }

    fun encryptWeapi(jsonText: String): WeapiEncrypted {
        val secretKey = randomSecretKey()
        // 第一次 AES
        val p1Bytes = aesCbc(jsonText.toByteArray(Charsets.UTF_8), PRESET_KEY.toByteArray(Charsets.UTF_8))
        val p1Base64 = Base64.encodeToString(p1Bytes, Base64.NO_WRAP)

        // 第二次 AES
        val paramsBytes = aesCbc(p1Base64.toByteArray(Charsets.UTF_8), secretKey.toByteArray(Charsets.UTF_8))
        val params = Base64.encodeToString(paramsBytes, Base64.NO_WRAP)

        // RSA 模幂加密
        val encSecKey = rsaNoPaddingHex(secretKey)
        return WeapiEncrypted(params, encSecKey)
    }
}
