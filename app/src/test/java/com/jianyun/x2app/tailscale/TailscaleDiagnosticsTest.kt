package com.jianyun.x2app.tailscale

import org.junit.Assert.*
import org.junit.Test

class TailscaleDiagnosticsTest {
    @Test fun keepsKnownFailureButNeverRawSuffix() {
        val message = "Tailscale 引擎已关闭 https://login.example/a/secret-token privateKey=secret-key"
        val diagnostic = TailscaleDiagnostics.error(message)
        assertTrue(diagnostic.startsWith("ENGINE_CLOSED:"))
        assertFalse(diagnostic.contains("https://"))
        assertFalse(diagnostic.contains("secret"))
    }
    @Test fun unlabelledAndEscapedSecretsAreNotPrintable() {
        listOf("sensitivevalue", "https:\\/\\/example/a/token", "Bearer tiny", "privateKey=abc").forEach {
            assertEquals("UNCLASSIFIED:[message redacted]", TailscaleDiagnostics.error(it))
        }
    }
    @Test fun stateDoesNotPrintAuthUrlAddressesOrUnknownHealth() {
        val result = TailscaleDiagnostics.state(TailscaleState(authUrl = "https://example/secret", addresses = listOf("100.64.0.9"), health = listOf("secret-health")))
        assertTrue(result.contains("authPresent=true"))
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("100.64.0.9"))
    }
    @Test fun classifiesNetworkFailureWithoutServerOrCredentials() {
        val result = TailscaleDiagnostics.error("Post https://example/key: TLS handshake timeout")
        assertEquals("TLS:TLS handshake timeout", result)
    }
}
