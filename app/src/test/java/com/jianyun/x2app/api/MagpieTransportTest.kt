package com.jianyun.x2app.api

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MagpieTransportTest {
    @Test fun directTransportRejectsOversizedResponses() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(4 * 1024 * 1024 + 1)))
            try {
                DirectHttpTransport.request(server.url("/releases").toString(), "", "GET")
                fail("oversized response must fail")
            } catch (e: IllegalStateException) {
                assertTrue(e.message.orEmpty().contains("响应数据过大"))
            }
        }
    }

    @Test fun publicRequestsDoNotSendAuthorizationOrCookies() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))
            assertEquals(200, DirectHttpTransport.request(server.url("/releases").toString(), "", "GET").statusCode)
            val request = server.takeRequest()
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
        }
    }

    @Test fun apiUsesGatewayAuthenticationAndUnwrapsCards() = runTest {
        MagpieTransport.current = HttpTransport { url, authorization, method ->
            assertEquals("http://magpie.test:3425/v1/magpie/quotas/cards", url)
            assertEquals("Bearer secret", authorization)
            assertEquals("GET", method)
            HttpResponse(200, """{"object":"list","data":[{"provider":"ok"}]}""")
        }
        try {
            assertEquals(listOf("ok"), MagpieApi("http://magpie.test:3425", "secret").quotas().map { it.provider })
        } finally {
            MagpieTransport.current = DirectHttpTransport
        }
    }

    @Test fun manualRefreshUsesPostAndReturnsTheRefreshedCards() = runTest {
        val api = MagpieApi("http://magpie.test", "secret", HttpTransport { url, authorization, method ->
            assertEquals("http://magpie.test/v1/magpie/quotas/refresh", url)
            assertEquals("Bearer secret", authorization)
            assertEquals("POST", method)
            HttpResponse(200, """{"data":[{"provider":"fresh"}]}""")
        })
        assertEquals("fresh", api.quotas(asked = true).single().provider)
    }

    @Test fun permissionFailureIsNotShownAsEmptyQuotas() = runTest {
        val api = MagpieApi("http://magpie.test", "secret", HttpTransport { _, _, _ ->
            HttpResponse(403, """{"error":{"code":"gateway_key_held"}}""")
        })
        try {
            api.quotas()
            fail("restricted key must fail")
        } catch (e: ApiException) {
            assertEquals(403, e.code)
            assertEquals("网关 API Key 无权读取这些数据，请检查共享与密钥权限", e.friendlyMessage())
        }
    }

    @Test fun cardRefreshScopesTheProviderAndAccount() = runTest {
        val api = MagpieApi("http://magpie.test:3425", "secret", HttpTransport { url, authorization, method ->
            assertEquals("http://magpie.test:3425/v1/magpie/quotas/refresh?provider=deepseek&user=account%2B1%40example.com", url)
            assertEquals("Bearer secret", authorization)
            assertEquals("POST", method)
            HttpResponse(200, """{"data":[{"provider":"deepseek","balance":"¥5.29"}]}""")
        })
        assertEquals("¥5.29", api.quotas(asked = true, provider = "deepseek", user = "account+1@example.com").single().balance)
    }

    @Test fun directTransportSendsBearerAndPostWithoutFollowingRedirects() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/other"))
            val response = DirectHttpTransport.request(server.url("/refresh").toString(), "Bearer secret", "POST")
            assertEquals(302, response.statusCode)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("Bearer secret", request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertEquals(1, server.requestCount)
        }
    }
}
