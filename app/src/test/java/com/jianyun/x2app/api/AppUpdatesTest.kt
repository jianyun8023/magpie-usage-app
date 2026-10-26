package com.jianyun.x2app.api

import com.jianyun.x2app.data.UpdateChannel
import com.jianyun.x2app.data.UpdatePreferences
import com.jianyun.x2app.vpn.AppNetworkMode
import com.jianyun.x2app.vpn.AppRequestRouter
import com.pavi2410.appupdater.GitHubUpdateSource
import com.pavi2410.appupdater.ReleaseAsset
import com.pavi2410.appupdater.ReleaseInfo
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppUpdatesTest {
    private val currentTime = Instant.parse("2026-10-20T00:00:00Z").epochSecond
    private val currentRevision = "abcdef1234567890"

    @Test fun officialCheckUsesPublicGitHubApiWithoutGatewayAuthentication() = runTest {
        val transport = HttpTransport { url, authorization, method ->
            assertEquals("https://api.github.com/repos/jianyun8023/magpie-usage-app/releases/latest", url)
            assertEquals("", authorization)
            assertEquals("GET", method)
            HttpResponse(200, releaseJson)
        }
        val found = checkForAppUpdate(UpdatePreferences(), "0.2.1", currentRevision, currentTime, transport)
        assertEquals("v0.3.0", found?.release?.tagName)
        assertEquals("$GITHUB_PROJECT_URL/releases/tag/v0.3.0", found?.pageUrl)
    }

    @Test fun betaChecksThroughConfiguredProxyAndIgnoresDrafts() = runTest {
        val transport = HttpTransport { url, authorization, _ ->
            assertEquals("https://proxy.example/https://api.github.com/repos/jianyun8023/magpie-usage-app/releases?per_page=30", url)
            assertEquals("", authorization)
            HttpResponse(200, "[${releaseJson.replace("\"draft\":false", "\"draft\":true")}]")
        }
        assertNull(checkForAppUpdate(UpdatePreferences(UpdateChannel.BETA, "https://proxy.example/"),
            "0.2.1", currentRevision, currentTime, transport))
    }

    @Test fun semverDistinguishesBetaNumbersAndFinalRelease() {
        val releases = listOf(release("v0.3.0-beta.2", true), release("v0.3.0-beta.10", true), release("v0.2.1"))
        assertEquals("v0.3.0-beta.10", choose(releases, UpdateChannel.BETA, "0.3.0-beta.2")?.release?.tagName)
        assertNull(choose(releases, UpdateChannel.STABLE, "0.2.1"))
        assertNull(choose(listOf(release("v0.3.0-beta.10")), UpdateChannel.STABLE, "0.2.1"))
        assertEquals("v0.3.0", choose(listOf(release("v0.3.0")), UpdateChannel.BETA, "0.3.0-beta.10")?.release?.tagName)
        assertNull(choose(listOf(release("v0.3.0+build.2")), UpdateChannel.BETA, "0.3.0+build.1"))
    }

    @Test fun versionsNeverOfferDowngradesAndUnknownTagsAreNotVersions() {
        assertNull(choose(listOf(release("v0.2.0"), release("nonsense")), UpdateChannel.BETA, "0.2.1"))
        assertEquals("v0.10.0", choose(listOf(release("v0.9.9"), release("v0.10.0")), UpdateChannel.STABLE, "0.2.1")?.release?.tagName)
    }

    @Test fun rollingSnapshotSelectsNewestApkAndDoesNotRepeatInstalledRevision() {
        val old = asset("magpie-usage-snapshot-1234567.apk", "2026-10-19T00:00:00Z")
        val latest = asset("magpie-usage-snapshot-7654321.apk", "2026-10-25T00:00:00Z")
        val snapshot = release("snapshot", true, "2026-09-01T00:00:00Z").copy(assets = listOf(old, latest))
        assertEquals(latest, choose(listOf(snapshot), UpdateChannel.BETA, "0.2.1")?.apk)
        assertNull(choose(listOf(snapshot.copy(assets = listOf(old))), UpdateChannel.BETA, "0.2.1"))
        assertNull(selectAppUpdate(listOf(snapshot), UpdateChannel.BETA, "0.2.1", "7654321ffffffff", currentTime))
        assertNull(choose(listOf(snapshot), UpdateChannel.STABLE, "0.2.1"))
        assertNull(selectAppUpdate(listOf(snapshot), UpdateChannel.BETA, "0.2.1", "", 0))
    }

    @Test fun betaPrefersNewerFormalReleaseOverOlderSnapshot() {
        val snapshot = release("snapshot", true).copy(assets = listOf(asset("magpie-usage-snapshot-7654321.apk", "2026-10-22T00:00:00Z")))
        val stable = release("v0.3.0", published = "2026-10-24T00:00:00Z")
        assertEquals(stable, choose(listOf(snapshot, stable), UpdateChannel.BETA, "0.2.1")?.release)
        val newerSnapshot = snapshot.copy(assets = listOf(asset("magpie-usage-snapshot-7654321.apk", "2026-10-25T00:00:00Z")))
        assertEquals(newerSnapshot, choose(listOf(newerSnapshot, stable), UpdateChannel.BETA, "0.2.1")?.release)
    }

    @Test fun foreignAndNonApkAssetsAreNotDownloadCandidates() {
        val invalid = listOf(
            "https://evil.example/app.apk", "https://github.com/other/repo/releases/download/v1/app.apk",
            "https://github.com:443/jianyun8023/magpie-usage-app/releases/download/v1/app.apk",
            "https://user@github.com/jianyun8023/magpie-usage-app/releases/download/v1/app.apk",
            "$GITHUB_PROJECT_URL/releases/download/../../../../other/repo/app.apk",
            "$GITHUB_PROJECT_URL/releases/download/%2e%2e/other/app.apk",
            "$GITHUB_PROJECT_URL/releases/download/%5c..%5cother/app.apk",
        )
        invalid.forEach { url ->
            assertNull(choose(listOf(release("v1.0.0").copy(assets = listOf(asset("app.apk").copy(downloadUrl = url)))), UpdateChannel.STABLE, "0.2.1"))
        }
        assertNull(choose(listOf(release("v1.0.0").copy(assets = listOf(asset("SHA256SUMS")))), UpdateChannel.STABLE, "0.2.1"))
    }

    @Test fun systemUpdateCheckLeavesDisconnectedGatewayRouteBlocked() = runTest {
        var gatewayRequests = 0
        var updateRequests = 0
        val router = AppRequestRouter(AppNetworkMode.TAILSCALE,
            HttpTransport { _, _, _ -> gatewayRequests++; HttpResponse(200, releaseJson) }) { _, _, _, _ ->
            gatewayRequests++
            HttpResponse(200, releaseJson)
        }
        val original = MagpieTransport.current
        val route = router.route
        try {
            MagpieTransport.current = router
            val found = checkForAppUpdate(UpdatePreferences(), "0.2.1", currentRevision, currentTime,
                HttpTransport { _, authorization, _ ->
                    updateRequests++
                    assertEquals("", authorization)
                    HttpResponse(200, releaseJson)
                })
            assertEquals("v0.3.0", found?.release?.tagName)
            assertEquals(route, router.route)
            try {
                MagpieTransport.current.request("https://gateway.test/quotas", "Bearer secret", "GET")
                fail("a disconnected selection must still block gateway requests")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("未连接"))
            }
            assertEquals(1, updateRequests)
            assertEquals(0, gatewayRequests)
        } finally {
            MagpieTransport.current = original
        }
    }

    @Test fun failedAndMalformedResponsesCannotReportUpToDate() = runTest {
        for (response in listOf(HttpResponse(429, "{}"), HttpResponse(302, ""), HttpResponse(200, "<html>proxy failure</html>"))) {
            try {
                checkForAppUpdate(UpdatePreferences(), "0.2.1", currentRevision, currentTime,
                    HttpTransport { _, _, _ -> response })
                fail("bad response must fail")
            } catch (_: Exception) { }
        }
    }

    @Test fun stableSourceDoesNotAcceptUnexpectedPrereleaseResponse() = runTest {
        val source = GitHubUpdateSource("owner", "repo", request = {
            releaseJson.replace("\"prerelease\":false", "\"prerelease\":true")
        })
        assertTrue(source.fetchReleases().isEmpty())
    }

    private fun choose(releases: List<ReleaseInfo>, channel: UpdateChannel, version: String) =
        selectAppUpdate(releases, channel, version, currentRevision, currentTime)

    private fun release(tag: String, beta: Boolean = false, published: String = "2026-10-23T00:00:00Z") =
        ReleaseInfo(tag, tag.removePrefix("v"), tag, "notes", published,
            "$GITHUB_PROJECT_URL/releases/tag/$tag", beta, listOf(asset("app.apk")))

    private fun asset(name: String, updated: String = "2026-10-23T00:00:00Z") =
        ReleaseAsset(name, "$GITHUB_PROJECT_URL/releases/download/v0.3.0/$name", 1234, updated)

    private val releaseJson = """{
        "tag_name":"v0.3.0","name":"Magpie v0.3.0","published_at":"2026-10-23T00:00:00Z",
        "draft":false,"prerelease":false,"unknown":"ignored",
        "assets":[{"name":"app.apk","browser_download_url":"$GITHUB_PROJECT_URL/releases/download/v0.3.0/app.apk"}]
    }"""
}
