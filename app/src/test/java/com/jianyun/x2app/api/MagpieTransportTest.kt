package com.jianyun.x2app.api

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MagpieTransportTest {
    @Test fun apiUsesTheInstalledTransportAndCookie() = runTest {
        val expectedUrl = "http://magpie.test:3430/api/usage/quotas"
        MagpieTransport.current = HttpTransport { url, cookie ->
            assertEquals(expectedUrl, url)
            assertEquals("magpie_web_3430=secret", cookie)
            HttpResponse(200, """[{"provider":"ok"}]""")
        }
        try {
            assertEquals(listOf("ok"), MagpieApi("http://magpie.test:3430", "secret").quotas().map { it.provider })
        } finally {
            MagpieTransport.current = DirectHttpTransport
        }
    }
}
