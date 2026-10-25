package com.jianyun.x2app.tailscale

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TailscaleStateTest {
    @Test fun connectedRequiresActiveEngineNativeLinkAndRunningBackend() {
        val state = TailscaleState(backendState = "Running", engineActive = true, nativeConnected = true)
        assertTrue(state.connected)
        assertFalse(state.copy(engineActive = false).connected)
        assertFalse(state.copy(nativeConnected = false).connected)
        assertFalse(state.copy(backendState = "Stopped").connected)
    }

    @Test fun restoredStatusCannotInventAnActiveEngine() {
        val status = Json.parseToJsonElement(
            """{"backendState":"Running","connected":true,"addresses":["100.64.0.2"]}""",
        ).jsonObject
        val restored = TailscaleMessages.status(TailscaleState(), status)
        assertEquals("Running", restored.backendState)
        assertTrue(restored.nativeConnected)
        assertTrue(restored.addresses.isEmpty())
        assertFalse(restored.connected)
    }

    @Test fun statusUpdatesAddressesWhileEngineIsActive() {
        val status = Json.parseToJsonElement(
            """{"backendState":"Running","connected":true,"addresses":["100.64.0.2"]}""",
        ).jsonObject
        val state = TailscaleMessages.status(TailscaleState(engineActive = true), status)
        assertEquals(listOf("100.64.0.2"), state.addresses)
        assertTrue(state.connected)
    }

    @Test fun loginClearsAddressAndRejectsInvalidAuthorizationUrl() {
        val status = Json.parseToJsonElement(
            """{"backendState":"NeedsLogin","authUrl":"javascript:alert(1)","addresses":["100.64.0.2"]}""",
        ).jsonObject
        val state = TailscaleMessages.status(TailscaleState(engineActive = true), status)
        assertNull(state.authUrl)
        assertTrue(state.addresses.isEmpty())
        assertEquals("引擎返回了无效的授权地址", state.error)
    }

    @Test fun runningAndStoppedHideAuthorizationUrl() {
        listOf("Running", "Stopped").forEach { backend ->
            val status = Json.parseToJsonElement(
                """{"backendState":"$backend","authUrl":"https://example.com/login"}""",
            ).jsonObject
            assertNull(TailscaleMessages.status(TailscaleState(engineActive = true), status).authUrl)
        }
    }
}
