package com.jianyun.x2app.ui

import com.jianyun.x2app.tailscale.TailscaleState
import org.junit.Assert.assertEquals
import org.junit.Test

class TailscaleSettingsTest {
    @Test fun restoredAccountWithoutServiceAlwaysOffersManualConnection() {
        listOf("Starting", "Running", "Stopped", "NeedsMachineAuth").forEach { backend ->
            assertEquals(TailscalePrimaryAction.CONNECT, tailscalePrimaryAction(
                TailscaleState(backendState = backend, serviceActive = false, addresses = listOf("100.127.250.3"))
            ))
        }
    }

    @Test fun activeServiceOffersDisconnectWhileConnectingOrConnected() {
        listOf("Starting", "Running", "NeedsMachineAuth", "NeedsLogin").forEach { backend ->
            assertEquals(TailscalePrimaryAction.DISCONNECT, tailscalePrimaryAction(
                TailscaleState(backendState = backend, serviceActive = true)
            ))
        }
    }

    @Test fun authorizationRequiresLiveServiceAndMissingAccountStillOffersLogin() {
        val inactive = TailscaleState(backendState = "NeedsLogin", authUrl = "https://example.test/login")
        assertEquals(TailscalePrimaryAction.LOGIN, tailscalePrimaryAction(inactive))
        assertEquals(TailscalePrimaryAction.AUTHORIZE, tailscalePrimaryAction(inactive.copy(serviceActive = true)))
    }
}
