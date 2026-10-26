// Adapted from pavi2410/kmp-app-updater, Apache-2.0.
// Local changes: retain release links, names, prerelease flags and asset timestamps.
// Source and license: third_party/app-updater/README.md.
package com.pavi2410.appupdater

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

data class ReleaseInfo(
    val tagName: String,
    val version: String,
    val name: String,
    val changelog: String,
    val publishedAt: String,
    val htmlUrl: String,
    val prerelease: Boolean,
    val assets: List<ReleaseAsset>,
)

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
    val updatedAt: String,
)

internal fun GitHubRelease.toReleaseInfo(): ReleaseInfo = ReleaseInfo(
    tagName = tagName,
    version = tagName.removePrefix("v"),
    name = name?.takeIf { it.isNotBlank() } ?: tagName,
    changelog = body.orEmpty(),
    publishedAt = publishedAt.orEmpty(),
    htmlUrl = htmlUrl,
    prerelease = prerelease,
    assets = assets.map { ReleaseAsset(it.name, it.browserDownloadUrl, it.size, it.updatedAt.orEmpty()) },
)
