// Adapted from pavi2410/kmp-app-updater, Apache-2.0.
// Local changes: inject the app's transport instead of Ktor, support URL acceleration,
// filter draft/prerelease responses, and fetch up to 30 releases for the Beta channel.
// Source and license: third_party/app-updater/README.md.
package com.pavi2410.appupdater

import kotlinx.serialization.json.Json

class GitHubUpdateSource(
    val owner: String,
    val repo: String,
    val includePreReleases: Boolean = false,
    private val request: suspend (String) -> String,
) {
    init {
        require(owner.isNotBlank()) { "owner must not be blank" }
        require(repo.isNotBlank()) { "repo must not be blank" }
    }

    private val apiUrl = "https://api.github.com/repos/$owner/$repo/releases"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetchReleases(): List<ReleaseInfo> {
        val url = if (includePreReleases) "$apiUrl?per_page=30" else "$apiUrl/latest"
        val body = request(url)
        val releases = if (includePreReleases) json.decodeFromString<List<GitHubRelease>>(body)
            else listOf(json.decodeFromString<GitHubRelease>(body))
        return releases.filterNot { it.draft || (!includePreReleases && it.prerelease) }
            .map { it.toReleaseInfo() }
    }
}
