package com.jianyun.x2app.vpn

import com.jianyun.x2app.api.HttpResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AppRequestRouterTest {
    @Test fun directModeUsesTheDirectTransport() = runTest {
        val router = AppRequestRouter(AppNetworkMode.DIRECT, direct = { _, authorization, method ->
            HttpResponse(200, "direct:$authorization:$method")
        }) { _, _, _, _ -> error("internal transport must not run") }
        assertEquals("direct:Bearer session:POST", router.request("http://example.test", "Bearer session", "POST").body)
    }

    @Test fun disconnectedSelectionDoesNotFallBackToDirect() = runTest {
        val router = AppRequestRouter(AppNetworkMode.WIREGUARD, direct = { _, _, _ ->
            HttpResponse(200, "direct")
        }) { _, _, _, _ -> error("not started") }
        router.update(AppNetworkMode.WIREGUARD, false)
        try {
            router.request("http://example.test", "", "GET")
            fail("disconnected route must not issue a request")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun switchRejectsAResponseFromThePreviousRoute() = runTest {
        var onInternal: (AppRoute) -> Unit = {}
        var seenSession = 0L
        val router = AppRequestRouter(AppNetworkMode.WIREGUARD, direct = { _, _, _ -> error("direct") }) { route, _, _, _ ->
            seenSession = route.nativeSessionID
            onInternal(route)
            HttpResponse(200, "old-wireguard")
        }
        router.update(AppNetworkMode.WIREGUARD, false)
        router.update(AppNetworkMode.WIREGUARD, true, 10)
        onInternal = { router.update(AppNetworkMode.TAILSCALE, true, 11) }
        try {
            router.request("http://example.test", "", "GET")
            fail("a route switch must reject the old response")
        } catch (_: IllegalStateException) {
        }
        assertEquals(10, seenSession)
    }

    @Test fun readinessChangeKeepsTheNativeSessionIdentity() {
        val router = AppRequestRouter(AppNetworkMode.TAILSCALE, direct = { _, _, _ -> error("direct") }) { _, _, _, _ ->
            error("not started")
        }
        router.update(AppNetworkMode.TAILSCALE, true, 27)
        router.update(AppNetworkMode.TAILSCALE, false)
        assertEquals(27, router.route.nativeSessionID)
        assertEquals(2, router.route.revision)
    }
}
