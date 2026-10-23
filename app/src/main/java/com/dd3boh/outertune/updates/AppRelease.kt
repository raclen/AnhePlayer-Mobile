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

/** 面向用户的下载入口；更新检查仍走 GitHub API，但点击下载统一跳官网，避免直接暴露仓库。 */
const val APP_DOWNLOAD_URL = "https://anhe-player.loc.cc/mobile"

data class AppRelease(
    val version: String,
    val versionCode: Long,
    val releaseUrl: String,
    val notes: String,
) {
    fun isNewerThan(installedVersion: String, installedVersionCode: Long): Boolean =
        isNewerVersion(version, installedVersion) && versionCode > installedVersionCode

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
    }.toString()

    companion object {
        fun fromCache(value: String): AppRelease? = runCatching {
            val item = Json.parseToJsonElement(value) as? JsonObject ?: return null
            val version = normalizeVersion(item.string("version")) ?: return null
            val versionCode = item.long("versionCode")?.takeIf { it > 0 } ?: return null
            val url = item.string("releaseUrl")
            val tag = url.removePrefix("$APP_PROJECT_URL/releases/tag/")
            if (url != "$APP_PROJECT_URL/releases/tag/$tag" || normalizeVersion(tag) != version) return null
            AppRelease(version, versionCode, url, item.string("notes").take(MAX_NOTES_LENGTH))
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

/** 不依赖 GitHub 的 latest 标记；只从有对应正式 APK 的发布中挑选最高版本。 */
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
        val assetPattern = Regex("^(?:AnhePlayer|OuterTune)-${Regex.escape(version)}-${Regex.escape(flavor)}-arm64-v8a-release-(\\d+)\\.apk$")
        val versionCode = (release["assets"] as? JsonArray).orEmpty().mapNotNull asset@{ assetElement ->
            val asset = assetElement as? JsonObject ?: return@asset null
            val name = asset.string("name")
            val match = assetPattern.matchEntire(name) ?: return@asset null
            if (asset.string("state") != "uploaded" || (asset.long("size") ?: 0) <= 0) return@asset null
            if (asset.string("browser_download_url") != "$APP_PROJECT_URL/releases/download/$tag/$name") return@asset null
            match.groupValues[1].toLongOrNull()?.takeIf { it > 0 }
        }.maxOrNull() ?: return@mapNotNull null
        AppRelease(version, versionCode, releaseUrl, release.string("body").take(MAX_NOTES_LENGTH))
    }.maxWithOrNull { left, right ->
        compareVersions(left.version, right.version).takeIf { it != 0 }
            ?: left.versionCode.compareTo(right.versionCode)
    }
}

private const val MAX_NOTES_LENGTH = 12_000
private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
