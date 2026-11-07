package com.dd3boh.outertune.updates

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

const val APP_PROJECT_URL = "https://github.com/raclen/AnhePlayer-Mobile"
internal const val APP_RELEASES_API = "https://api.github.com/repos/raclen/AnhePlayer-Mobile/releases?per_page=30"

data class AppReleaseAsset(
    val name: String,
    val abi: String,
    val versionCode: Long,
    val downloadUrl: String,
    val size: Long,
    val sha256: String? = null,
) {
    internal fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("state", "uploaded")
        put("browser_download_url", downloadUrl)
        put("size", size)
        sha256?.let { put("digest", "sha256:$it") }
    }
}

data class AppRelease(
    val version: String,
    val versionCode: Long,
    val releaseUrl: String,
    val notes: String,
    val assets: List<AppReleaseAsset> = emptyList(),
) {
    fun isNewerThan(installedVersion: String, installedVersionCode: Long): Boolean =
        isNewerVersion(version, installedVersion) && versionCode > installedVersionCode

    fun downloadAsset(supportedAbis: List<String>): AppReleaseAsset? =
        supportedAbis.firstNotNullOfOrNull { abi -> assets.firstOrNull { it.abi == abi && it.versionCode == versionCode } }

    val displayNotes: String
        get() = notes.lineSequence()
            .filterNot { it.matches(Regex("^#\\s+(AnhePlayer|OuterTune)\\s+.*", RegexOption.IGNORE_CASE)) }
            .map { line ->
                line.trim()
                    .replace(Regex("^#{1,6}\\s+"), "")
                    .replace(Regex("^[-*+]\\s+"), "• ")
                    .replace("**", "")
            }
            .filter(String::isNotBlank)
            .joinToString("\n")

    fun toCache(): String = buildJsonObject {
        put("version", version)
        put("versionCode", versionCode)
        put("releaseUrl", releaseUrl)
        put("notes", notes)
        put("assets", JsonArray(assets.map(AppReleaseAsset::toJson)))
    }.toString()

    companion object {
        fun fromCache(value: String, flavor: String? = null): AppRelease? = runCatching {
            val item = Json.parseToJsonElement(value) as? JsonObject ?: return null
            val version = normalizeVersion(item.string("version")) ?: return null
            val versionCode = item.long("versionCode")?.takeIf { it > 0 } ?: return null
            val url = item.string("releaseUrl")
            val tag = url.removePrefix("$APP_PROJECT_URL/releases/tag/")
            if (url != "$APP_PROJECT_URL/releases/tag/$tag" || normalizeVersion(tag) != version) return null
            // 旧缓存没有 assets：保留提醒，下载前重新获取经过校验的附件信息。
            val assets = parseAssets(item, version, tag, flavor).filter { it.versionCode == versionCode }
            AppRelease(version, versionCode, url, item.string("notes").take(MAX_NOTES_LENGTH), assets)
        }.getOrNull()
    }
}

/** 只接受数字正式版本，并逐段比较，避免把 2.0.10 判断成小于 2.0.9。 */
internal fun normalizeVersion(value: String): String? {
    val version = value.trim().removePrefix("v").removePrefix("V")
    if (!version.matches(Regex("\\d+\\.\\d+\\.\\d+"))) return null
    return version.split('.').map { it.toLongOrNull() ?: return null }.joinToString(".")
}

internal fun compareVersions(left: String, right: String): Int {
    val leftParts = normalizeVersion(left)?.split('.')?.map(String::toLong) ?: return -1
    val rightParts = normalizeVersion(right)?.split('.')?.map(String::toLong) ?: return 1
    leftParts.indices.forEach { index ->
        val comparison = leftParts[index].compareTo(rightParts[index])
        if (comparison != 0) return comparison
    }
    return 0
}

internal fun isNewerVersion(version: String, previousVersion: String): Boolean =
    normalizeVersion(version) != null && compareVersions(version, previousVersion) > 0

/** 不依赖 GitHub 的 latest 标记；arm64 正式 APK 上传完成后，才视为该版本已发布。 */
internal fun findLatestRelease(response: String, flavor: String): AppRelease? {
    val releases = Json.parseToJsonElement(response) as? JsonArray
        ?: throw IllegalArgumentException("发布信息不是数组")
    return releases.mapNotNull { element ->
        val release = element as? JsonObject ?: return@mapNotNull null
        if (release.boolean("draft") != false || release.boolean("prerelease") != false) return@mapNotNull null
        val tag = release.string("tag_name")
        val version = normalizeVersion(tag) ?: return@mapNotNull null
        val releaseUrl = release.string("html_url")
        if (releaseUrl != "$APP_PROJECT_URL/releases/tag/$tag") return@mapNotNull null
        val assets = parseAssets(release, version, tag, flavor)
        val versionCode = assets.filter { it.abi == "arm64-v8a" }.maxOfOrNull { it.versionCode }
            ?: return@mapNotNull null
        AppRelease(version, versionCode, releaseUrl, release.string("body").take(MAX_NOTES_LENGTH),
            assets.filter { it.versionCode == versionCode })
    }.maxWithOrNull { left, right ->
        compareVersions(left.version, right.version).takeIf { it != 0 }
            ?: left.versionCode.compareTo(right.versionCode)
    }
}

private fun parseAssets(item: JsonObject, version: String, tag: String, flavor: String?): List<AppReleaseAsset> {
    val flavorPattern = flavor?.let(Regex::escape) ?: "(?:core|full)"
    val pattern = Regex("^(?:AnhePlayer|OuterTune)-${Regex.escape(version)}-$flavorPattern-(arm64-v8a|armeabi-v7a)-release-(\\d+)\\.apk$")
    return (item["assets"] as? JsonArray).orEmpty().mapNotNull { element ->
        val asset = element as? JsonObject ?: return@mapNotNull null
        val name = asset.string("name")
        val match = pattern.matchEntire(name) ?: return@mapNotNull null
        val size = asset.long("size")?.takeIf { it > 0 } ?: return@mapNotNull null
        if (asset.string("state") != "uploaded") return@mapNotNull null
        val url = asset.string("browser_download_url")
        if (url != "$APP_PROJECT_URL/releases/download/$tag/$name") return@mapNotNull null
        val code = match.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val digest = asset.string("digest")
        val sha256 = if (digest.isBlank()) null else {
            if (!digest.matches(Regex("sha256:[a-fA-F0-9]{64}"))) return@mapNotNull null
            digest.removePrefix("sha256:").lowercase()
        }
        AppReleaseAsset(name, match.groupValues[1], code, url, size, sha256)
    }
}

private const val MAX_NOTES_LENGTH = 12_000
private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
