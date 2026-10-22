package com.dd3boh.outertune.updates

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只验证版本解析和提醒状态，不涉及 Compose 或 UI 测试。 */
class AppReleaseTest {
    @Test
    fun numericVersionsAreComparedByComponents() {
        assertTrue(isNewerVersion("2.0.10", "2.0.9"))
        assertTrue(isNewerVersion("2.1.0", "2.0.99"))
        assertTrue(isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(isNewerVersion("2.0.9", "2.0.10"))
        assertFalse(isNewerVersion("2.0.10", "2.0.10"))
    }

    @Test
    fun onlyStableThreePartVersionsAreAccepted() {
        assertEquals("2.0.10", normalizeVersion("v2.0.10"))
        assertEquals("2.0.10", normalizeVersion("V2.0.10"))
        assertNull(normalizeVersion("v2.0.10-beta1"))
        assertNull(normalizeVersion("build-2026-10-22"))
        assertNull(normalizeVersion("2.0"))
        assertNull(normalizeVersion("999999999999999999999.0.0"))
    }

    @Test
    fun updatesRequireHigherVersionNameAndVersionCode() {
        val release = cachedRelease("2.0.10", 210)
        assertTrue(release.isNewerThan("2.0.9", 209))
        assertFalse(release.isNewerThan("2.0.10", 210))
        assertFalse(release.isNewerThan("2.0.9", 211))
        assertFalse(release.isNewerThan("2.1.0", 209))
    }

    @Test
    fun latestVersionIsSelectedRegardlessOfResponseOrder() {
        val releases = listOf(apiRelease("2.0.9", 209), apiRelease("2.0.10", 210), apiRelease("2.0.8", 208))
        assertEquals("2.0.10", findLatestRelease(JsonArray(releases).toString(), "core")?.version)
    }

    @Test
    fun draftAndPrereleaseAreIgnored() {
        val releases = listOf(
            apiRelease("2.0.4", 204),
            apiRelease("2.0.5", 205, draft = true),
            apiRelease("2.0.6", 206, prerelease = true),
        )
        assertEquals("2.0.4", findLatestRelease(JsonArray(releases).toString(), "core")?.version)
    }

    @Test
    fun releaseWithoutUploadedApkDoesNotReplaceReadyRelease() {
        val missingApk = JsonObject(apiRelease("2.0.5", 205) + ("assets" to JsonArray(emptyList())))
        val releases = listOf(apiRelease("2.0.4", 204), missingApk)
        assertEquals("2.0.4", findLatestRelease(JsonArray(releases).toString(), "core")?.version)
    }

    @Test
    fun apkFlavorAndArchitectureMustMatch() {
        val full = apiRelease("2.0.5", 205, flavor = "full")
        val wrongAbi = apiRelease("2.0.6", 206, abi = "x86_64")
        assertNull(findLatestRelease(JsonArray(listOf(full, wrongAbi)).toString(), "core"))
        assertEquals("2.0.5", findLatestRelease(JsonArray(listOf(full)).toString(), "full")?.version)
    }

    @Test
    fun olderOuterTuneApkNamesRemainSupported() {
        assertNotNull(findLatestRelease(JsonArray(listOf(apiRelease("1.0.18", 118, brand = "OuterTune"))).toString(), "core"))
    }

    @Test
    fun unrelatedReleaseOrDownloadUrlsAreIgnored() {
        val wrongRepository = JsonObject(apiRelease("2.0.5", 205) + ("html_url" to JsonPrimitive("https://github.com/raclen/OuterTune/releases/tag/v2.0.5")))
        val release = apiRelease("2.0.6", 206)
        val originalAsset = (release["assets"] as JsonArray)[0] as JsonObject
        val wrongDownload = JsonObject(release + ("assets" to JsonArray(listOf(
            JsonObject(originalAsset + ("browser_download_url" to JsonPrimitive("https://example.com/update.apk")))
        ))))
        assertNull(findLatestRelease(JsonArray(listOf(wrongRepository, wrongDownload)).toString(), "core"))
    }

    @Test
    fun emptyOrUnfinishedAssetsAreIgnored() {
        val release = apiRelease("2.0.5", 205)
        val asset = (release["assets"] as JsonArray)[0] as JsonObject
        val empty = JsonObject(release + ("assets" to JsonArray(listOf(JsonObject(asset + ("size" to JsonPrimitive(0)))))))
        val uploading = JsonObject(release + ("assets" to JsonArray(listOf(JsonObject(asset + ("state" to JsonPrimitive("new")))))))
        assertNull(findLatestRelease(JsonArray(listOf(empty, uploading)).toString(), "core"))
    }

    @Test
    fun emptyReleaseListDoesNotInventAnUpdate() {
        assertNull(findLatestRelease("[]", "core"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun apiErrorObjectIsNotAReleaseList() {
        findLatestRelease("{\"message\":\"API rate limit exceeded\"}", "core")
    }

    @Test
    fun cacheRoundTripPreservesReleaseDetails() {
        val release = cachedRelease("2.0.5", 205)
        assertEquals(release, AppRelease.fromCache(release.toCache()))
    }

    @Test
    fun corruptCacheIsIgnored() {
        assertNull(AppRelease.fromCache("not json"))
        assertNull(AppRelease.fromCache("{}"))
        assertNull(AppRelease.fromCache(cachedRelease("2.0.5", 0).toCache()))
        assertNull(AppRelease.fromCache(cachedRelease("2.0.5", 205).copy(releaseUrl = "https://example.com").toCache()))
    }

    @Test
    fun releaseNotesHaveReadableSummaryWithoutVersionHeading() {
        assertEquals("修复\n• 播放优化", cachedRelease("2.0.5", 205).displayNotes)
    }

    @Test
    fun newVersionIsUnreadAndCanPromptOnce() {
        val state = AppUpdateState(isLoaded = true, release = cachedRelease("2.0.5", 205))
        assertTrue(state.hasUnreadUpdate)
        assertTrue(state.needsPrompt)
    }

    @Test
    fun laterDismissalSuppressesPromptButKeepsUnreadDot() {
        val state = AppUpdateState(release = cachedRelease("2.0.5", 205), promptedVersion = "2.0.5")
        assertTrue(state.hasUnreadUpdate)
        assertFalse(state.needsPrompt)
    }

    @Test
    fun viewedUpdateKeepsCardButClearsDotAndAutomaticPrompt() {
        val state = AppUpdateState(release = cachedRelease("2.0.5", 205), viewedVersion = "2.0.5")
        assertNotNull(state.release)
        assertFalse(state.hasUnreadUpdate)
        assertFalse(state.needsPrompt)
    }

    @Test
    fun newerVersionEnablesReminderAgain() {
        val state = AppUpdateState(
            release = cachedRelease("2.0.10", 210),
            promptedVersion = "2.0.9",
            viewedVersion = "2.0.9",
        )
        assertTrue(state.hasUnreadUpdate)
        assertTrue(state.needsPrompt)
    }

    @Test
    fun serverRollbackDoesNotRemindForAnOlderVersion() {
        val state = AppUpdateState(
            release = cachedRelease("2.0.5", 205),
            promptedVersion = "2.0.6",
            viewedVersion = "2.0.6",
        )
        assertFalse(state.hasUnreadUpdate)
        assertFalse(state.needsPrompt)
    }

    @Test
    fun noUpdateMeansNoDotOrPrompt() {
        assertFalse(AppUpdateState().hasUnreadUpdate)
        assertFalse(AppUpdateState().needsPrompt)
    }

    private fun cachedRelease(version: String, code: Long) = AppRelease(
        version, code, "$APP_PROJECT_URL/releases/tag/v$version", "# AnhePlayer v$version\n\n## 修复\n\n- **播放优化**\n"
    )

    private fun apiRelease(
        version: String,
        code: Int,
        draft: Boolean = false,
        prerelease: Boolean = false,
        flavor: String = "core",
        abi: String = "arm64-v8a",
        brand: String = "AnhePlayer",
    ): JsonObject {
        val name = "$brand-$version-$flavor-$abi-release-$code.apk"
        return buildJsonObject {
            put("tag_name", "v$version")
            put("draft", draft)
            put("prerelease", prerelease)
            put("html_url", "$APP_PROJECT_URL/releases/tag/v$version")
            put("body", "更新内容")
            put("assets", JsonArray(listOf(buildJsonObject {
                put("name", name)
                put("state", "uploaded")
                put("size", 1024)
                put("browser_download_url", "$APP_PROJECT_URL/releases/download/v$version/$name")
            })))
        }
    }
}
