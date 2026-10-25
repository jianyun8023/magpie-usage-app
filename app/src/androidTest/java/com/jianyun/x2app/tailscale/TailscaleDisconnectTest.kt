package com.jianyun.x2app.tailscale

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test

/** Run this class alone in a fresh instrumentation process on a supported native ABI. */
class TailscaleDisconnectTest {
    @Test fun coldDisconnectLoadsEngineAndConfirmsStoppedIntent() = runBlocking {
        val controller = TailscaleController.get(InstrumentationRegistry.getInstrumentation().targetContext)
        assumeFalse("Requires a fresh process before any Tailscale initialization", controller.initialized)
        val result = controller.disconnect()
        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        // The previous implementation returned success without ever reading persisted prefs.
        assertTrue(controller.initialized)
        assertFalse(controller.state.value.vpnActive)
        assertFalse(controller.state.value.connected)
        assertTrue(controller.disconnect().isSuccess)
    }
}
