package com.jianyun.x2app.vpn

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.jianyun.x2app.tailscale.TailscaleController
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Run methods separately in fresh target processes to verify both actual load orders. No VPN starts. */
class NativeRuntimeIsolationTest {
    @Test fun tailscaleThenRemoteWireGuard() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ts = TailscaleController.get(context)
        mark("TS_REFRESH_BEGIN")
        ts.refresh().getOrThrow()
        mark("TS_REFRESH_DONE")
        verifyRemoteNative()
        ts.refresh().getOrThrow()
        delay(1_000) // The original native crash happened after both calls returned.
        assertMainHasNoWireGuard()
        mark("TS_THEN_REMOTE_WG_PASS")
    }

    @Test fun remoteWireGuardThenTailscale() = runBlocking {
        verifyRemoteNative()
        val ts = TailscaleController.get(InstrumentationRegistry.getInstrumentation().targetContext)
        mark("TS_REFRESH_BEGIN")
        ts.refresh().getOrThrow()
        mark("TS_REFRESH_DONE")
        verifyRemoteNative()
        delay(1_000)
        assertMainHasNoWireGuard()
        mark("REMOTE_WG_THEN_TS_PASS")
    }

    private suspend fun verifyRemoteNative() {
        val controller = VpnController.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val remote = controller.debugRemote(WgProtocol.DEBUG_NATIVE)
        assertTrue("Remote native wgVersion must execute", !remote.nativeVersion.isNullOrBlank())
        assertTrue(remote.pid > 0)
        assertNotEquals("WG native must be outside the UI/TS process", Process.myPid(), remote.pid)
        mark("REMOTE_WG_CALL_DONE remotePid=${remote.pid}")
        assertMainHasNoWireGuard()
    }

    private fun assertMainHasNoWireGuard() {
        assertFalse("WG library leaked into the main process", File("/proc/self/maps").useLines { lines ->
            lines.any { it.contains("libwg-go.so") }
        })
    }
    private fun mark(stage: String) { Log.i("X2Isolation", "mainPid=${Process.myPid()} $stage") }
}
