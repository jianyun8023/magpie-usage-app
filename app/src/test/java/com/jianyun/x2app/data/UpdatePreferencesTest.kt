package com.jianyun.x2app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UpdatePreferencesTest {
    @Test fun presetMirrorsRestoreFromSavedPrefixAndKeepCustomAddresses() {
        UpdateMirror.entries.filter { it.prefix != null }.forEach { mirror ->
            val prefix = checkNotNull(mirror.prefix)
            assertEquals(mirror, UpdateMirror.forPrefix(prefix))
            if (prefix.isNotEmpty()) assertEquals(mirror, UpdateMirror.forPrefix("$prefix/"))
        }
        assertEquals(UpdateMirror.CUSTOM, UpdateMirror.forPrefix("https://proxy.example"))
    }

    @Test fun channelDefaultsToStableAndRecognizesBeta() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.restored(null))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.restored("unexpected"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.restored("BETA"))
    }

    @Test fun proxyPreservesSubpathAndPrefixesTheCompleteGitHubUrl() {
        val api = "https://api.github.com/repos/owner/repo/releases/latest"
        assertEquals("https://proxy.example/github", normalizedUpdateProxy(" HTTPS://proxy.example/github/ "))
        assertEquals(api, acceleratedGitHubUrl(api, ""))
        assertEquals("https://proxy.example/github/$api", acceleratedGitHubUrl(api, "https://proxy.example/github/"))
        assertEquals("", normalizedUpdateProxy("  "))
    }

    @Test fun proxyRejectsCredentialsUnsafeSchemesAndAmbiguousSuffixes() {
        listOf("http://proxy.example", "ftp://proxy.example", "https://", "https://user@proxy.example",
            "https://proxy.example?token=secret", "https://proxy.example#part", "https://proxy.example:0",
            "https://proxy.example:65536", "https://proxy.example:", "https://proxy.example/%broken").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { normalizedUpdateProxy(it) }
        }
    }
}
