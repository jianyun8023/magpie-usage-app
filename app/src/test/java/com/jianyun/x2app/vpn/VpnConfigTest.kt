package com.jianyun.x2app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VpnConfigTest {
    // Synthetic test keys only.
    private val fixture = """
        [Interface]
        PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
        Address = 10.7.0.2/32
        [Peer]
        PublicKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        Endpoint = 192.0.2.1:51820
        AllowedIPs = 10.7.0.0/24
    """.trimIndent()

    @Test fun validConfigPreservesRoutesAndEndpoint() {
        val config = parseVpnConfig(fixture.toByteArray())
        assertEquals("10.7.0.0/24", config.peers.single().allowedIps.single().toString())
        assertEquals("192.0.2.1:51820", config.peers.single().endpoint.get().toString())
    }

    @Test fun rejectsMissingPeerOrAddressOrEndpointOrRoutes() {
        listOf(
            fixture.substringBefore("[Peer]"),
            fixture.replace("Address = 10.7.0.2/32", ""),
            fixture.replace("Endpoint = 192.0.2.1:51820", ""),
            fixture.replace("AllowedIPs = 10.7.0.0/24", ""),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { parseVpnConfig(invalid.toByteArray()) }
        }
    }

    @Test fun rejectsMalformedKeysAndUnsupportedScripts() {
        listOf(
            fixture.replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", "invalid-secret"),
            fixture.replace("Address =", "PostUp = echo secret\nAddress ="),
        ).forEach { invalid ->
            assertThrows(Exception::class.java) { parseVpnConfig(invalid.toByteArray()) }
        }
    }

    @Test fun importIsBounded() {
        assertEquals(MAX_CONFIG_BYTES, readVpnConfig(ByteArray(MAX_CONFIG_BYTES).inputStream()).size)
        assertThrows(IllegalArgumentException::class.java) {
            readVpnConfig(ByteArray(MAX_CONFIG_BYTES + 1).inputStream())
        }
        assertThrows(IllegalArgumentException::class.java) { parseVpnConfig(ByteArray(MAX_CONFIG_BYTES + 1)) }
    }

    @Test fun rejectsRulesThatExcludeThisApp() {
        listOf("ExcludedApplications = com.jianyun.x2app", "IncludedApplications = other.app").forEach { rule ->
            val text = fixture.replace("[Interface]", "[Interface]\n$rule")
            assertThrows(VpnRoutingException::class.java) { parseVpnConfig(text.toByteArray()) }
        }
    }

    @Test fun acceptsRulesThatRouteThisApp() {
        listOf("ExcludedApplications = other.app", "IncludedApplications = other.app, com.jianyun.x2app").forEach { rule ->
            val text = fixture.replace("[Interface]", "[Interface]\n$rule")
            assertEquals(1, parseVpnConfig(text.toByteArray()).peers.size)
        }
    }
}
