package com.jianyun.x2app.ui

import com.jianyun.x2app.tailscale.TailscaleState
import org.junit.Assert.assertEquals
import org.junit.Test

class TailscaleSettingsTest {
    @Test fun restoredAccountWithoutServiceAlwaysOffersManualConnection() {
        listOf("Starting", "Running", "Stopped", "NeedsMachineAuth").forEach { backend ->
            assertEquals(TailscalePrimaryAction.CONNECT, tailscalePrimaryAction(
                TailscaleState(backendState = backend, engineActive = false, addresses = listOf("100.127.250.3"))
            ))
        }
    }

    @Test fun activeEngineOffersDisconnectWhileConnectingOrConnected() {
        listOf("Starting", "Running", "NeedsMachineAuth").forEach { backend ->
            assertEquals(TailscalePrimaryAction.DISCONNECT, tailscalePrimaryAction(
                TailscaleState(backendState = backend, engineActive = true)
            ))
        }
    }

    @Test fun unknownRestoredStateConnectsBeforeAssumingLoginIsRequired() {
        assertEquals(TailscalePrimaryAction.CONNECT, tailscalePrimaryAction(TailscaleState()))
    }

    @Test fun authorizationRequiresLiveEngineAndMissingAccountStillOffersLogin() {
        val inactive = TailscaleState(backendState = "NeedsLogin", authUrl = "https://example.test/login")
        assertEquals(TailscalePrimaryAction.LOGIN, tailscalePrimaryAction(inactive))
        assertEquals(TailscalePrimaryAction.LOGIN, tailscalePrimaryAction(inactive.copy(authUrl = null, engineActive = true)))
        assertEquals(TailscalePrimaryAction.AUTHORIZE, tailscalePrimaryAction(inactive.copy(engineActive = true)))
    }
}
