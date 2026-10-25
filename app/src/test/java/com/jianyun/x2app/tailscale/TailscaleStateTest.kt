package com.jianyun.x2app.tailscale

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TailscaleStateTest {
    @Test fun restoredBackendCannotInventAnAndroidService() {
        val status = Json.parseToJsonElement("""{"BackendState":"Running","TailscaleIPs":["100.64.0.2"]}""").jsonObject
        val restored = TailscaleMessages.status(TailscaleState(), status)
        assertEquals("Running", restored.backendState)
        assertFalse(restored.serviceActive)
        assertFalse(restored.connected)
        assertFalse(notification(restored, """{"State":5}""").serviceActive)
        assertFalse(notification(restored, """{"State":6}""").serviceActive)
    }

    @Test fun backendNotificationsPreserveActuallyAttachedService() {
        val starting = notification(TailscaleState(serviceActive = true), """{"State":5}""")
        assertTrue(starting.serviceActive)
        assertFalse(starting.connected)
        val status = Json.parseToJsonElement("""{"BackendState":"Running"}""").jsonObject
        assertTrue(TailscaleMessages.status(starting, status).serviceActive)
    }
    @Test fun unauthenticatedAndStoppedStatesCannotRegisterVpn() {
        listOf("NoState", "InUseOtherUser", "NeedsLogin", "NeedsMachineAuth", "Stopped", "Unknown").forEach {
            assertFalse(TailscaleState(backendState = it).readyForVpn)
        }
        assertTrue(TailscaleState(backendState = "Starting").readyForVpn)
        assertTrue(TailscaleState(backendState = "Running").readyForVpn)
        assertFalse(TailscaleState(authUrl = "https://example.com/authorize").readyForVpn)
    }
    private fun notification(state: TailscaleState, json: String) = TailscaleMessages.notification(state, Json.parseToJsonElement(json).jsonObject)

    @Test fun authorizationUrlDoesNotHideAnUnresolvedEngineFailure() {
        val state = notification(TailscaleState(error = "VPN failure"),
            """{"BrowseToURL":"https://example.com/authorize"}""")
        assertEquals("VPN failure", state.error)
        assertNotNull(state.authUrl)
        assertFalse(state.connected)
    }

    @Test fun authorizationUrlIsNotAConnection() {
        val state = notification(TailscaleState(), """{"State":2,"BrowseToURL":"https://login.tailscale.com/a/test"}""")
        assertFalse(state.connected)
        assertEquals("NeedsLogin", state.backendState)
        assertEquals("https://login.tailscale.com/a/test", state.authUrl)
    }

    @Test fun runningStillRequiresEstablishedVpn() {
        val state = notification(TailscaleState(), """{"State":6}""")
        assertFalse(state.connected)
        assertTrue(state.copy(vpnActive = true).connected)
        assertFalse(state.copy(backendState = "Stopped", vpnActive = true).connected)
    }

    @Test fun loginFinishedClearsLinkWithoutInventingRunningState() {
        val state = notification(TailscaleState(authUrl = "https://login.tailscale.com/a/test"), """{"LoginFinished":{}}""")
        assertNull(state.authUrl)
        assertFalse(state.connected)
    }

    @Test fun rejectsNonHttpsOrCredentialBearingAuthLinks() {
        listOf("intent://bad", "javascript:alert(1)", "http://login.tailscale.com", "https://user:secret@example.com").forEach { url ->
            val state = notification(TailscaleState(), """{"BrowseToURL":"$url"}""")
            assertNull(state.authUrl)
            assertNotNull(state.error)
        }
    }

    @Test fun netmapUpdatesSelfAddressesWithoutLosingVpnState() {
        val state = notification(TailscaleState(backendState = "Running", vpnActive = true),
            """{"NetMap":{"SelfNode":{"Addresses":["100.64.0.2/32","fd7a:115c:a1e0::2/128"]}}}""")
        assertEquals(listOf("100.64.0.2", "fd7a:115c:a1e0::2"), state.addresses)
        assertTrue(state.connected)
    }

    @Test fun expiredLoginClearsPreviousIdentity() {
        val state = notification(TailscaleState(addresses = listOf("100.64.0.2")), """{"State":2}""")
        assertTrue(state.addresses.isEmpty())
    }

    @Test fun healthWarningsAreReplacedAndCleared() {
        var state = notification(TailscaleState(), """{"Health":{"Warnings":{"network":{"Text":"offline"}}}}""")
        assertEquals(listOf("offline"), state.health)
        state = notification(state, """{"Health":{"Warnings":{}}}""")
        assertTrue(state.health.isEmpty())
    }

    @Test fun statusDoesNotClaimTunnelAndClearsStaleAddresses() {
        val state = TailscaleMessages.status(TailscaleState(addresses = listOf("100.64.0.2")),
            Json.parseToJsonElement("""{"BackendState":"NeedsLogin","AuthURL":"https://login.tailscale.com/a/test"}""").jsonObject)
        assertTrue(state.addresses.isEmpty())
        assertFalse(state.connected)
    }
}
