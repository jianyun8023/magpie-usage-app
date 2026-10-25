package com.jianyun.x2app.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class AppNetworkModeTest {
    @Test fun restoresOnlyKnownPersistedSelections() {
        assertEquals(AppNetworkMode.DIRECT, AppNetworkMode.restored(null))
        assertEquals(AppNetworkMode.WIREGUARD, AppNetworkMode.restored("WIREGUARD"))
        assertEquals(AppNetworkMode.TAILSCALE, AppNetworkMode.restored("TAILSCALE"))
        assertEquals(AppNetworkMode.DIRECT, AppNetworkMode.restored("DIRECT"))
        assertEquals(AppNetworkMode.UNAVAILABLE, AppNetworkMode.restored("legacy-value"))
    }
}
