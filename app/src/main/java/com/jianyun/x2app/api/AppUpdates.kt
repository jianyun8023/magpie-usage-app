package com.jianyun.x2app.api

import com.github.zafarkhaja.semver.Version
import com.jianyun.x2app.data.UpdateChannel
import com.jianyun.x2app.data.UpdatePreferences
import com.jianyun.x2app.data.acceleratedGitHubUrl
import com.pavi2410.appupdater.GitHubUpdateSource
import com.pavi2410.appupdater.ReleaseAsset
import com.pavi2410.appupdater.ReleaseInfo
import java.net.URI
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val GITHUB_PROJECT_URL = "https://github.com/jianyun8023/magpie-usage-app"

data class AppUpdate(val release: ReleaseInfo, val apk: ReleaseAsset) {
    val pageUrl: String get() = "$GITHUB_PROJECT_URL/releases/tag/${release.tagName}"
}

suspend fun checkForAppUpdate(
    preferences: UpdatePreferences,
    currentVersion: String,
    revision: String,
    revisionTime: Long,
    transport: HttpTransport = DirectHttpTransport,
): AppUpdate? = withContext(Dispatchers.IO) {
    val source = GitHubUpdateSource("jianyun8023", "magpie-usage-app",
        includePreReleases = preferences.channel == UpdateChannel.BETA,
        request = { url ->
            // Public release metadata never receives the Magpie gateway credential.
            val response = transport.request(acceleratedGitHubUrl(url, preferences.proxyPrefix), "", "GET")
            check(response.statusCode == 200) {
                when (response.statusCode) {
                    403, 429 -> "GitHub 请求受限，请稍后重试或使用支持 API 的加速地址"
                    404 -> "未找到 GitHub 发布版本"
                    else -> "更新检查失败（HTTP ${response.statusCode}）"
                }
            }
            response.body
        },
    )
    selectAppUpdate(source.fetchReleases(), preferences.channel, currentVersion, revision, revisionTime)
}

internal fun selectAppUpdate(
    releases: List<ReleaseInfo>,
    channel: UpdateChannel,
    currentVersion: String,
    revision: String,
    revisionTime: Long,
): AppUpdate? {
    val current = Version.parse(currentVersion.removePrefix("v"))
    val candidates = releases.filter { channel == UpdateChannel.BETA || !it.prerelease }
        .mapNotNull { release ->
            release.assets.filter(::isProjectApk).maxByOrNull { timestamp(it.updatedAt) }
                ?.let { AppUpdate(release, it) }
        }
    val versionUpdate = candidates.filter { candidate ->
        val version = parsedVersion(candidate.release.version)
        version != null && version.isHigherThan(current) && (channel == UpdateChannel.BETA || !version.isPreRelease)
    }.maxWithOrNull { a, b ->
        val av = checkNotNull(parsedVersion(a.release.version))
        val bv = checkNotNull(parsedVersion(b.release.version))
        when {
            av.isHigherThan(bv) -> 1
            av.isLowerThan(bv) -> -1
            else -> 0
        }
    }
    // The repository's rolling Snapshot tag has no SemVer; its APK name carries the source revision.
    val snapshot = candidates.filter { candidate ->
        val sha = Regex("magpie-usage-snapshot-([0-9a-f]{7,40})\\.apk").matchEntire(candidate.apk.name)
            ?.groupValues?.get(1)
        channel == UpdateChannel.BETA && candidate.release.tagName == "snapshot" &&
            sha != null && revision.isNotBlank() && revisionTime > 0 && !revision.startsWith(sha) &&
            timestamp(candidate.apk.updatedAt) > Instant.ofEpochSecond(revisionTime)
    }.maxByOrNull { timestamp(it.apk.updatedAt) }
    return when {
        snapshot == null -> versionUpdate
        versionUpdate == null -> snapshot
        timestamp(snapshot.apk.updatedAt) > timestamp(versionUpdate.release.publishedAt) -> snapshot
        else -> versionUpdate
    }
}

private fun parsedVersion(value: String): Version? = runCatching { Version.parse(value) }.getOrNull()

private fun timestamp(value: String): Instant = runCatching { Instant.parse(value) }.getOrDefault(Instant.EPOCH)

private fun isProjectApk(asset: ReleaseAsset): Boolean {
    if (!asset.name.endsWith(".apk", ignoreCase = true)) return false
    val uri = runCatching { URI(asset.downloadUrl) }.getOrNull() ?: return false
    return uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null &&
        '\\' !in uri.path && uri.path.split('/').none { it == "." || it == ".." } &&
        uri.rawPath.startsWith("/jianyun8023/magpie-usage-app/releases/download/") &&
        uri.rawPath.endsWith(".apk", ignoreCase = true)
}
