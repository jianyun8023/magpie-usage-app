package com.jianyun.x2app.tailscale

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class TailscaleControlServerTest {
    @Test fun defaultsAndCanonicalizesExplicitChoice() {
        assertEquals(TailscaleControlServer.DEFAULT, TailscaleControlServer.normalize(""))
        assertEquals("http://example.test:18081", TailscaleControlServer.normalize(" HTTP://EXAMPLE.TEST:18081/ "))
        assertEquals("http://example.test", TailscaleControlServer.normalize("http://example.test:80/"))
    }
    @Test fun invalidConfigurationsNeverEchoSecrets() {
        listOf("ftp://example.test", "http://user:secret@example.test", "https://example.test/a", "https://example.test/?key=secret", "https://example.test/#secret", "http://example.test:0", "http://example.test:65536").forEach {
            val failure = runCatching { TailscaleControlServer.normalize(it) }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure!!.message.orEmpty().contains("secret"))
        }
    }
    @Test fun officialAndHttpsControlNeverAllowHttpAuthorization() {
        assertNull(TailscaleControlServer.authorizationUrl("http://controlplane.tailscale.com/register/test", TailscaleControlServer.DEFAULT))
        assertNull(TailscaleControlServer.authorizationUrl("http://example.test/register/test", "https://example.test"))
        assertNotNull(TailscaleControlServer.authorizationUrl("https://login.tailscale.com/a/test", TailscaleControlServer.DEFAULT))
    }
    @Test fun explicitHttpAllowsOnlySameHostAndEffectivePort() {
        val control = "http://192.0.2.10:18081"
        assertNotNull(TailscaleControlServer.authorizationUrl("$control/register/test", control))
        listOf("http://192.0.2.10/register/test", "http://192.0.2.10:18082/register/test", "http://192.0.2.10.attacker.test:18081/register/test", "http://user:secret@192.0.2.10:18081/register/test", "javascript:alert(1)").forEach {
            assertNull(TailscaleControlServer.authorizationUrl(it, control))
        }
        assertNotNull(TailscaleControlServer.authorizationUrl("http://example.test:80/register/test", "http://example.test"))
    }
    @Test fun statusAndNotificationUseSelectedControlServerPolicy() {
        val url = "http://192.0.2.10:18081/register/test"
        val status = Json.parseToJsonElement("""{"BackendState":"NeedsLogin","AuthURL":"$url"}""").jsonObject
        val notify = Json.parseToJsonElement("""{"BrowseToURL":"$url"}""").jsonObject
        val selected = TailscaleState(controlUrl = "http://192.0.2.10:18081")
        assertEquals(url, TailscaleMessages.status(selected, status).authUrl)
        assertEquals(url, TailscaleMessages.notification(selected, notify).authUrl)
        assertNull(TailscaleMessages.status(TailscaleState(), status).authUrl)
        assertNull(TailscaleMessages.notification(TailscaleState(), notify).authUrl)
    }
    @Test fun cancellationAlsoSuppressesCustomServerAuthorization() {
        val intent = TailscaleAuthorizationIntent()
        var state = TailscaleState(controlUrl = "http://example.test:18081")
        val notify = Json.parseToJsonElement("""{"BrowseToURL":"http://example.test:18081/register/test"}""").jsonObject
        intent.set(true) {}
        intent.apply({ state = it(state) }) { TailscaleMessages.notification(it, notify) }
        assertNotNull(state.authUrl)
        intent.set(false) { state = state.copy(authUrl = null) }
        intent.apply({ state = it(state) }) { TailscaleMessages.notification(it, notify) }
        assertNull(state.authUrl)
    }
}
