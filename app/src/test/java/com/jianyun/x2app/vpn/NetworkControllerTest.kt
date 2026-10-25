package com.jianyun.x2app.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NetworkControllerTest {
    @Test fun wireGuardWaitsForTailscaleShutdownBarrier() = runTest {
        val events = mutableListOf<String>()
        val stopped = CompletableDeferred<Unit>()
        val network = controller(events, stopTs = {
            events += "stop-ts"
            stopped.await()
            Result.success(Unit)
        })
        val job = launch { network.execute(NetworkAction.WG_CONNECT) }
        runCurrent()
        assertEquals(listOf("stop-ts"), events)
        assertTrue(network.state.value.busy)
        stopped.complete(Unit)
        job.join()
        assertEquals(listOf("stop-ts", "start-wg"), events)
        assertFalse(network.state.value.busy)
    }

    @Test fun failedShutdownNeverStartsOtherVpn() = runTest {
        val events = mutableListOf<String>()
        val network = controller(events, stopWg = { Result.failure(IllegalStateException("stop failed")) })
        network.execute(NetworkAction.TS_LOGIN)
        assertTrue(events.isEmpty())
        assertEquals("stop failed", network.state.value.error)
        assertFalse(network.state.value.busy)
    }

    @Test fun oppositeConnectionRequestsRemainSerialized() = runTest {
        val events = mutableListOf<String>()
        val stopped = CompletableDeferred<Unit>()
        val network = controller(events, stopTs = {
            events += "stop-ts"
            stopped.await()
            Result.success(Unit)
        })
        val first = launch { network.execute(NetworkAction.WG_CONNECT) }
        runCurrent()
        val second = launch { network.execute(NetworkAction.TS_CONNECT) }
        runCurrent()
        assertEquals(listOf("stop-ts"), events)
        stopped.complete(Unit)
        first.join(); second.join()
        assertEquals(listOf("stop-ts", "start-wg", "stop-wg", "start-ts"), events)
    }

    @Test fun loginStopsWireGuardAndExplicitDisconnectsStayScoped() = runTest {
        val events = mutableListOf<String>()
        val network = controller(events)
        network.execute(NetworkAction.TS_LOGIN)
        network.execute(NetworkAction.WG_DISCONNECT)
        network.execute(NetworkAction.TS_DISCONNECT)
        network.execute(NetworkAction.TS_LOGOUT)
        assertEquals(listOf("stop-wg", "login-ts", "stop-wg", "stop-ts", "logout-ts"), events)
    }

    @Test fun controlServerChangeWaitsForInFlightVpnSwitch() = runTest {
        val events = mutableListOf<String>()
        val stopped = CompletableDeferred<Unit>()
        val network = controller(events, stopTs = {
            events += "stop-ts"
            stopped.await()
            Result.success(Unit)
        })
        val connect = launch { network.execute(NetworkAction.WG_CONNECT) }
        runCurrent()
        val save = launch { network.executeControlUrl("http://headscale.test:8080") }
        runCurrent()
        assertEquals(listOf("stop-ts"), events)
        stopped.complete(Unit)
        connect.join(); save.join()
        assertEquals(listOf("stop-ts", "start-wg", "save-control"), events)
    }

    @Test fun failedControlServerChangeRemainsAnError() = runTest {
        val network = controller(mutableListOf(), saveControl = { Result.failure(IllegalStateException("save failed")) })
        network.executeControlUrl("invalid")
        assertEquals("save failed", network.state.value.error)
        assertFalse(network.state.value.busy)
    }

    private fun controller(
        events: MutableList<String>,
        stopWg: suspend () -> Result<Unit> = { events += "stop-wg"; Result.success(Unit) },
        stopTs: suspend () -> Result<Unit> = { events += "stop-ts"; Result.success(Unit) },
        saveControl: suspend (String) -> Result<Unit> = { events += "save-control"; Result.success(Unit) },
    ) = NetworkController(
        stopWg = stopWg,
        startWg = { events += "start-wg"; Result.success(Unit) },
        stopTs = stopTs,
        startTs = { events += "start-ts"; Result.success(Unit) },
        loginTs = { events += "login-ts"; Result.success(Unit) },
        logoutTs = { events += "logout-ts"; Result.success(Unit) },
        saveTsControl = saveControl,
    )
}
