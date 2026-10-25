package com.jianyun.x2app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerConfigTest {
    @Test fun trimsBothFieldsAndAddsDefaultScheme() {
        assertEquals(ServerConfig("http://192.0.2.10:3430/magpie", "key"),
            normalizedServerConfig(" 192.0.2.10:3430/magpie/ ", " key \n"))
    }

    @Test fun keepsPrivateHostsIpv6PortsAndEncodedSubpaths() {
        listOf("http://magpie:3430", "https://host.internal/api%20root", "http://[fd00::1]:3430/path").forEach {
            assertEquals(it, normalizedServerConfig(it, "").baseUrl)
        }
        assertEquals("https://host/path", normalizedServerConfig("HTTPS://host/path/", "").baseUrl)
    }

    @Test fun rejectsInvalidAddressesBeforeSaving() {
        listOf("", "  ", "ftp://host", "https://", "http://a b", "http://host:0", "http://host:65536",
            "http://host:", "http://host:abc", "http://user:pass@host", "http://host?query", "http://host#part", "http://host/%broken").forEach {
            assertThrows(IllegalArgumentException::class.java) { normalizedServerConfig(it, "key") }
        }
    }
}
