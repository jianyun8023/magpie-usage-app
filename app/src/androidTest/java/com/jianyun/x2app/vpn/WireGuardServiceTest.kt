package com.jianyun.x2app.vpn

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.platform.app.InstrumentationRegistry
import com.jianyun.x2app.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Run only after granting VPN permission and disconnecting in the app. No peer is contacted. */
class WireGuardServiceTest {
    @Test fun foregroundNotificationIsRemovedAndServiceCanRestart() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = VpnController.get(context)
        controller.refresh()
        assumeTrue("Grant VPN permission in the app first", VpnService.prepare(context) == null)
        assumeTrue("Disconnect existing tunnel first", controller.state.value.known && !controller.state.value.up)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            repeat(2) {
                val remote = controller.debugRemote(WgProtocol.DEBUG_FOREGROUND)
                assertNotEquals("VPN service must run in :wireguard", Process.myPid(), remote.pid)
                assertTrue(remote.foreground)
                val notification = awaitNotifications(manager, "VPN 前台通知应出现") {
                    it.count { notification -> notification.id == 51820 } == 1
                }.single { it.id == 51820 }.notification
                assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                assertTrue(notification.actions.any { it.title == "断开" })
                controller.disconnectAndWait().getOrThrow()
                assertTrue(controller.state.value.known)
                assertFalse(controller.state.value.up)
                assertFalse(controller.state.value.remoteServiceAlive)
                val remaining = awaitNotifications(manager, "VPN 停止后通知应清除") {
                    it.none { notification -> notification.id == 51820 }
                }
                assertFalse(remaining.any { it.id == 51820 })
            }
        } finally {
            try { controller.disconnectAndWait().getOrThrow() }
            finally { instrumentation.runOnMainSync { activity.finish() } }
        }
    }

    // Service readiness and notification publication/removal are separate system operations.
    private suspend fun awaitNotifications(
        manager: NotificationManager,
        expectation: String,
        condition: (Array<StatusBarNotification>) -> Boolean,
    ): Array<StatusBarNotification> {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (true) {
            val notifications = manager.activeNotifications
            if (condition(notifications)) return notifications
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError("$expectation（15 秒超时）；当前通知 id/flags：" +
                    notifications.joinToString { "${it.id}/${it.notification.flags}" })
            }
            delay(100)
        }
    }
}
