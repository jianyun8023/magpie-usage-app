package com.jianyun.x2app.tailscale

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class TailscaleAuthorizationIntentTest {
    private val url = "https://login.example/authorize"
    private val status = Json.parseToJsonElement("""{"BackendState":"NeedsLogin","AuthURL":"$url"}""").jsonObject
    private val notification = Json.parseToJsonElement("""{"BrowseToURL":"$url"}""").jsonObject

    @Test fun cancelBlocksBothStatusAndLateNotificationUntilNewIntent() {
        val intent = TailscaleAuthorizationIntent()
        var state = TailscaleState()
        fun set(active: Boolean) = intent.set(active) { state = state.copy(authUrl = null) }
        fun status() = intent.apply({ state = it(state) }) { TailscaleMessages.status(it, status) }
        fun notify() = intent.apply({ state = it(state) }) { TailscaleMessages.notification(it, notification) }
        set(true)
        notify()
        assertEquals(url, state.authUrl)
        set(false)
        status()
        assertNull(state.authUrl)
        notify()
        assertNull(state.authUrl)
        // A fresh explicit login/connect may receive an authorization URL again.
        set(true)
        status()
        assertEquals(url, state.authUrl)
    }

    @Test fun coldRefreshDoesNotRestoreCachedAuthorization() {
        val intent = TailscaleAuthorizationIntent()
        var state = TailscaleState()
        intent.apply({ state = it(state) }) { TailscaleMessages.status(it, status) }
        assertEquals("NeedsLogin", state.backendState)
        assertNull(state.authUrl)
    }

    @Test fun cancelSerializedWithInFlightNotificationWins() {
        val intent = TailscaleAuthorizationIntent()
        var state = TailscaleState()
        intent.set(true) {}
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val update = Thread {
            intent.apply({ state = it(state) }) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                TailscaleMessages.notification(it, notification)
            }
        }
        update.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val cancel = Thread {
            intent.set(false) { state = state.copy(authUrl = null) }
            cancelled.countDown()
        }
        cancel.start()
        release.countDown()
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        update.join(5_000)
        cancel.join(5_000)
        assertNull(state.authUrl)
        intent.apply({ state = it(state) }) { TailscaleMessages.notification(it, notification) }
        assertNull(state.authUrl)
    }
}
