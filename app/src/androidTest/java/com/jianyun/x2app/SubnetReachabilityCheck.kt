package com.jianyun.x2app

// Temporary read-only network diagnostic. Never prints response bodies or credentials.
import android.os.Bundle
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.jianyun.x2app.api.MagpieTransport
import com.jianyun.x2app.tailscale.TailscaleDiagnostics
import com.jianyun.x2app.vpn.AppNetworkEngine
import com.jianyun.x2app.vpn.AppNetworkMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class SubnetReachabilityCheck {
    @Test fun probeApprovedSubnetThroughAppTailscale() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val engine = AppNetworkEngine.get(instrumentation.targetContext)
        assertEquals(AppNetworkMode.TAILSCALE, engine.mode.value)
        engine.connectTailscale().getOrThrow()
        try {
            withTimeout(90_000) {
                while (!engine.tailscale.value.connected) {
                    instrumentation.sendStatus(2, Bundle().apply {
                        putString("subnetState", TailscaleDiagnostics.state(engine.tailscale.value))
                    })
                    delay(5_000)
                }
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("subnetState", "Tailscale Running") })
            val clientField = AppNetworkEngine::class.java.getDeclaredField("client").apply { isAccessible = true }
            val diagnostics = (clientField.get(engine) as appnet.Client).tailscaleDiagnosticsJSON()
            instrumentation.sendStatus(2, Bundle().apply { putString("subnetDiagnostics", diagnostics) })
            for (url in listOf("http://192.168.2.1/", "http://192.168.2.1/cgi-bin/luci/", "http://192.168.2.174:3430/")) {
                val started = SystemClock.elapsedRealtime()
                val outcome = try {
                    val response = MagpieTransport.current.request(url, "", "GET")
                    "HTTP ${response.statusCode} (${response.body.length} characters)"
                } catch (failure: Exception) {
                    "failed ${failure.javaClass.simpleName}"
                }
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("subnetProbe", "$url: $outcome; ${SystemClock.elapsedRealtime()-started} ms")
                })
            }
        } finally {
            engine.disconnectTailscale().getOrThrow()
        }
    }
}
