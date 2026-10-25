package com.jianyun.x2app.tailscale

import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class TailscaleBuilderTest {
    @Test fun newBuilderDoesNotThrowDuringShutdownOrRequireAttachedContext() {
        val service = TailscaleVpnService()
        // Simulate the shutdown/pending Go route-update interleaving without starting a VPN.
        val field = TailscaleVpnService::class.java.getDeclaredField("closing").apply { isAccessible = true }
        (field.get(service) as AtomicBoolean).set(true)
        assertNotNull(service.newBuilder())
    }
}
