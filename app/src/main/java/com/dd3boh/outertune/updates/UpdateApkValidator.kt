package com.dd3boh.outertune.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.dd3boh.outertune.BuildConfig
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal class UpdateDownloadException(message: String) : IOException(message)

/** 必须在 IO 线程执行；系统安装器仍负责最终的 APK 签名与覆盖安装校验。 */
internal object UpdateApkValidator {
    @Suppress("DEPRECATION")
    fun validate(context: Context, file: File, release: AppRelease, asset: AppReleaseAsset) {
        if (!file.isFile || file.length() != asset.size) {
            throw UpdateDownloadException("安装包不完整，请重新下载")
        }
        asset.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != expected) throw UpdateDownloadException("安装包校验失败，请重新下载")
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val manager = context.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw UpdateDownloadException("安装包无法识别，请重新下载")
        val installed = manager.getPackageInfo(context.packageName, flags)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archive.longVersionCode else archive.versionCode.toLong()
        if (archive.packageName != context.packageName) {
            throw UpdateDownloadException("安装包与当前应用不匹配，测试包不能直接升级为正式包")
        }
        if (normalizeVersion(archive.versionName.orEmpty()) != release.version || code != release.versionCode ||
            !release.isNewerThan(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
        ) {
            throw UpdateDownloadException("安装包版本不匹配，请重新检查更新")
        }
        if (!signaturesMatch(installed, archive)) {
            throw UpdateDownloadException("安装包签名与当前应用不一致，无法覆盖安装")
        }
    }

    @Suppress("DEPRECATION")
    private fun signaturesMatch(installed: PackageInfo, archive: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val current = installed.signingInfo ?: return false
            val next = archive.signingInfo ?: return false
            val currentSigners = current.apkContentsSigners.map { it.toCharsString() }.toSet()
            if (currentSigners.isEmpty()) return false
            return if (current.hasMultipleSigners() || next.hasMultipleSigners()) {
                currentSigners == next.apkContentsSigners.map { it.toCharsString() }.toSet()
            } else {
                // 支持系统认可的签名轮换，不接受与已安装证书毫无关联的安装包。
                val history = next.signingCertificateHistory?.map { it.toCharsString() }.orEmpty()
                currentSigners.all { it in history }
            }
        }
        val current = installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        val next = archive.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
        return current.isNotEmpty() && current == next
    }
}
