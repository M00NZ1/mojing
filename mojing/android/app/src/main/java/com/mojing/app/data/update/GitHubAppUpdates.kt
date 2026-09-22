package com.mojing.app.data.update

import com.google.gson.JsonParser
import com.mojing.app.data.remote.executeCancellable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString(): String = "$major.$minor.$patch"
    companion object {
        fun parse(value: String): ReleaseVersion? {
            val parts = Regex("^[vV]?(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(value.trim())?.groupValues ?: return null
            return ReleaseVersion(parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null,
                parts[3].toIntOrNull() ?: return null)
        }
    }
}

internal data class UpdatePackage(val name: String, val url: String, val size: Long)
internal data class AppRelease(val version: ReleaseVersion, val notes: String, val apk: UpdatePackage?)
internal class UpdateCheckException(message: String) : Exception(message)

internal class GitHubAppUpdates(
    private val client: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build(),
    private val apiBase: String = "https://api.github.com",
) {
    companion object {
        const val REPOSITORY = "M00NZ1/mojing"
        const val RELEASES_URL = "https://github.com/$REPOSITORY/releases"

        fun parseRelease(json: String, supportedAbis: List<String>): AppRelease {
            val root = JsonParser.parseString(json).asJsonObject
            if (root.get("draft")?.asBoolean == true || root.get("prerelease")?.asBoolean == true)
                throw UpdateCheckException("暂未发布可用的正式版本")
            val version = ReleaseVersion.parse(root.get("tag_name")?.asString.orEmpty())
                ?: throw UpdateCheckException("发布版本号无法识别，请查看 GitHub 发布页")
            val assets = root.getAsJsonArray("assets")?.mapNotNull { value ->
                val asset = value.asJsonObject
                val name = asset.get("name")?.asString.orEmpty()
                val url = asset.get("browser_download_url")?.asString.orEmpty()
                val uri = runCatching { URI(url) }.getOrNull()
                val trusted = uri?.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
                    uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
                    uri.path.startsWith("/$REPOSITORY/releases/download/")
                val size = asset.get("size")?.asLong ?: 0L
                if (!trusted || size <= 0L || asset.get("state")?.asString != "uploaded") null
                else UpdatePackage(name, url, size)
            }.orEmpty()
            val names = supportedAbis.map { "MoJing-$version-$it-release.apk" } + "MoJing-$version-universal-release.apk"
            val apk = names.firstNotNullOfOrNull { name -> assets.firstOrNull { it.name == name } }
            return AppRelease(version, root.get("body")?.takeUnless { it.isJsonNull }?.asString.orEmpty().take(6000), apk)
        }
    }

    suspend fun latest(supportedAbis: List<String>): AppRelease {
        val request = Request.Builder().url("$apiBase/repos/$REPOSITORY/releases/latest")
            .header("Accept", "application/vnd.github+json").header("User-Agent", "MoJing-Android")
            .get().build()
        return client.executeCancellable(request) { response ->
            when (response.code) {
                404 -> throw UpdateCheckException("更新源暂不可访问，请确认发布仓库已公开且已有正式版本")
                403, 429 -> throw UpdateCheckException("GitHub 暂时限制了检查频率，请稍后重试")
            }
            if (!response.isSuccessful) throw UpdateCheckException("GitHub 暂时不可用（${response.code}），请稍后重试")
            try {
                parseRelease(response.body?.string().orEmpty(), supportedAbis)
            } catch (e: UpdateCheckException) { throw e }
            catch (_: Exception) { throw UpdateCheckException("更新信息格式异常，请稍后重试或查看发布页") }
        }
    }
}
