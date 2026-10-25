package com.jianyun.x2app.tailscale

import android.app.ActivityManager
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in only: run this method alone in a fresh target process, with VPN already authorized
 * and no Tailscale account. Never opens the browser, approves an account, or prints auth data.
 * Runner argument: -e tailscaleLoginSmoke true
 */
class TailscaleLoginSmokeTest {
    @Test fun loginProducesAuthorizationUrlAndStopsService() = runBlocking {
        assumeTrue("Requires explicit tailscaleLoginSmoke opt-in",
            InstrumentationRegistry.getArguments().getString("tailscaleLoginSmoke") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val controller = TailscaleController.get(context)
        assumeFalse("Run alone in a fresh process", controller.initialized)
        assumeTrue("System VPN authorization must already be granted", controller.prepareVpn() == null)
        val activity = instrumentation.startActivitySync(
            Intent().setClassName(context.packageName, "com.jianyun.x2app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        var loginAttempted = false
        var primaryFailure: Throwable? = null
        try {
            withTimeout(10_000) {
                while (true) {
                    var foreground = false
                    instrumentation.runOnMainSync { foreground = activity.hasWindowFocus() && !activity.isFinishing }
                    if (foreground) break
                    delay(100)
                }
            }
            mark("FOREGROUND_READY")
            val refresh = controller.refresh()
            diagnose("REFRESH_RESULT", controller, refresh.exceptionOrNull())
            assertTrue("Tailscale status refresh failed", refresh.isSuccess)
            assumeTrue("Requires an unauthenticated Tailscale profile",
                controller.state.value.backendState == "NeedsLogin" && controller.state.value.addresses.isEmpty())
            loginAttempted = true
            mark("LOGIN_BEGIN")
            val login = withTimeout(45_000) { controller.login() }
            diagnose("LOGIN_RESULT", controller, login.exceptionOrNull())
            assertTrue("Tailscale login command failed", login.isSuccess)
            mark("LOGIN_COMMAND_RETURNED")
            val state = withTimeout(45_000) {
                // Keep observing after an error so diagnostics distinguish recovery from a
                // terminal failure. The final error assertion remains mandatory.
                controller.state.onEach { mark("AUTH_WAIT_STATE ${TailscaleDiagnostics.state(it)}") }
                    .first { it.authUrl != null || it.connected }
            }
            mark("AUTH_WAIT_RESULT ${TailscaleDiagnostics.state(state)}")
            assertTrue("Engine error: ${TailscaleDiagnostics.error(state.error)}", state.error == null)
            assertFalse("Unauthenticated login must not report connected", state.connected)
            val validUrl = TailscaleControlServer.authorizationUrl(state.authUrl, state.controlUrl) != null
            assertTrue("Engine must return an authorization URL allowed by the selected control server", validUrl)
            assertFalse("Login must not become connected without account authorization", controller.state.value.connected)
            mark("AUTHORIZATION_URL_VALIDATED")
        } catch (error: Throwable) {
            diagnose("TEST_FAILURE", controller, error)
            primaryFailure = error
            throw error
        } finally {
            try {
                if (loginAttempted) withContext(NonCancellable) {
                    mark("DISCONNECT_BEGIN")
                    val disconnect = withTimeout(30_000) { controller.disconnect() }
                    diagnose("DISCONNECT_RESULT", controller, disconnect.exceptionOrNull())
                    assertTrue("Tailscale cleanup failed", disconnect.isSuccess)
                    withTimeout(15_000) {
                        while (serviceRunning(context.getSystemService(ActivityManager::class.java))) delay(100)
                    }
                    assertFalse("Tailscale VPN remained active after cleanup", controller.state.value.vpnActive)
                    mark("SERVICE_EXIT_CONFIRMED")
                }
            } catch (cleanup: Throwable) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanup) else throw cleanup
            } finally {
                instrumentation.runOnMainSync { activity.finish() }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(manager: ActivityManager): Boolean = manager.getRunningServices(100).any {
        it.service.className == TailscaleVpnService::class.java.name && it.pid != 0
    }

    private fun mark(stage: String) { Log.i("X2TailscaleSmoke", "pid=${Process.myPid()} $stage") }

    private fun diagnose(stage: String, controller: TailscaleController, error: Throwable?) {
        mark("$stage ${TailscaleDiagnostics.state(controller.state.value)}")
        if (error != null) {
            // Do not pass Throwable to Log: its message, causes and stack can contain auth data.
            mark("${stage}_EXCEPTION type=${error.javaClass.simpleName} detail=${TailscaleDiagnostics.error(error.message)}")
        }
    }
}
