package com.jianyun.x2app.vpn

import android.content.Intent
import android.net.VpnService
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.jianyun.x2app.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class WireGuardProcessDeathTest {
    @Test fun remoteDeathIsUnknownUntilFreshDisconnectReceipt() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = VpnController.get(context)
        controller.refresh()
        assumeTrue(VpnService.prepare(context) == null)
        assumeTrue(controller.state.value.known && !controller.state.value.up)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            val remote = controller.debugRemote(WgProtocol.DEBUG_FOREGROUND)
            assertNotEquals(Process.myPid(), remote.pid)
            assertTrue(remote.foreground)
            // This test service has no TUN/config; kill only the isolated app-owned process.
            Process.killProcess(remote.pid)
            withTimeout(5_000) { while (controller.state.value.known) delay(25) }
            assertFalse(controller.state.value.up)
            assertNotNull(controller.state.value.error)
            controller.disconnectAndWait().getOrThrow()
            assertTrue(controller.state.value.known)
            assertFalse(controller.state.value.remoteServiceAlive)
            assertFalse(controller.state.value.up)
            assertNotEquals(remote.pid, controller.state.value.remotePid)
        } finally {
            try { controller.disconnectAndWait().getOrThrow() }
            finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }
}
