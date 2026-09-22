package com.mojing.app.data.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GitHubAppUpdatesTest {
    private fun release(vararg abis: String, tag: String = "v1.0.29", urlOverride: String? = null): String {
        val assets = JsonArray()
        for (abi in abis) {
            val name = "MoJing-1.0.29-$abi-release.apk"
            assets.add(JsonObject().apply {
                addProperty("name", name)
                addProperty("browser_download_url", urlOverride ?: "https://github.com/${GitHubAppUpdates.REPOSITORY}/releases/download/v1.0.29/$name")
                addProperty("size", 30000000L)
                addProperty("state", "uploaded")
            })
        }
        return JsonObject().apply {
            addProperty("tag_name", tag)
            addProperty("draft", false)
            addProperty("prerelease", false)
            addProperty("body", "新增功能")
            add("assets", assets)
        }.toString()
    }

    @Test fun comparesNumericVersionsAndRejectsPrereleaseTags() {
        assertTrue(ReleaseVersion.parse("v1.0.100")!! > ReleaseVersion.parse("1.0.99")!!)
        assertTrue(ReleaseVersion.parse("2.0.0")!! > ReleaseVersion.parse("1.99.99")!!)
        assertEquals(ReleaseVersion.parse("v1.0.28"), ReleaseVersion.parse("1.0.28"))
        assertNull(ReleaseVersion.parse("1.0.29-beta"))
        assertNull(ReleaseVersion.parse("99999999999999.0.1"))
    }

    @Test fun choosesPreferredDeviceAbiThenUniversal() {
        val json = release("universal", "armeabi-v7a", "arm64-v8a")
        assertTrue(GitHubAppUpdates.parseRelease(json, listOf("arm64-v8a", "armeabi-v7a")).apk!!.name.contains("arm64-v8a"))
        assertTrue(GitHubAppUpdates.parseRelease(json, listOf("armeabi-v7a")).apk!!.name.contains("armeabi-v7a"))
        assertTrue(GitHubAppUpdates.parseRelease(json, listOf("x86_64")).apk!!.name.contains("universal"))
        assertNull(GitHubAppUpdates.parseRelease(release("arm64-v8a"), listOf("armeabi-v7a")).apk)
    }

    @Test fun ignoresUntrustedOrIncompleteAssets() {
        for (url in listOf("http://github.com/${GitHubAppUpdates.REPOSITORY}/releases/download/v1/x.apk",
            "https://example.com/x.apk", "https://github.com/other/repo/releases/download/v1/x.apk")) {
            assertNull(GitHubAppUpdates.parseRelease(release("universal", urlOverride = url), listOf("arm64-v8a")).apk)
        }
        assertNull(GitHubAppUpdates.parseRelease(release("universal").replace("uploaded", "new"), emptyList()).apk)
        assertThrows(UpdateCheckException::class.java) {
            GitHubAppUpdates.parseRelease(release("universal").replace("\"prerelease\":false", "\"prerelease\":true"), emptyList())
        }
    }

    @Test fun checksReleaseWithoutAuthorizationOrUserData() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(release("universal")))
            val result = GitHubAppUpdates(apiBase = server.url("/").toString().trimEnd('/')).latest(listOf("x86_64"))
            assertEquals("1.0.29", result.version.toString())
            val request = server.takeRequest()
            assertEquals("/repos/${GitHubAppUpdates.REPOSITORY}/releases/latest", request.path)
            assertNull(request.getHeader("Authorization"))
            assertEquals(0L, request.bodySize)
        }
    }

    @Test fun reportsPrivateRepositoryAndRateLimitClearly() = runTest {
        MockWebServer().use { server ->
            val updates = GitHubAppUpdates(apiBase = server.url("/").toString().trimEnd('/'))
            for ((status, text) in listOf(404 to "更新源暂不可访问", 403 to "检查频率", 429 to "检查频率")) {
                server.enqueue(MockResponse().setResponseCode(status))
                val error = runCatching { updates.latest(emptyList()) }.exceptionOrNull()
                assertTrue(error is UpdateCheckException)
                assertTrue(error!!.message.orEmpty().contains(text))
            }
        }
    }
}
