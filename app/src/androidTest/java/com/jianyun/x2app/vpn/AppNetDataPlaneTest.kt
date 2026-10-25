package com.jianyun.x2app.vpn

import android.content.Intent
import android.Manifest.permission
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import appnet.Appnet
import appnet.Client
import appnet.StateStore
import com.wireguard.config.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Real-ARM JNI data-plane tests. All state is process-local; no app configuration is read.
 *
 * Runner arguments:
 * - wgConfigBase64: a complete WireGuard fixture used by the WireGuard test.
 * - headscaleControlURL: the control URL used by the Tailscale test.
 */
class AppNetDataPlaneTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun wireGuardCarriesEncryptedHttpAndStopsWithoutFallback() {
        val encoded = InstrumentationRegistry.getArguments().getString("wgConfigBase64")
        assumeTrue("Requires runner argument wgConfigBase64", !encoded.isNullOrBlank())

        val bytes = try {
            Base64.decode(encoded, Base64.DEFAULT)
        } catch (_: IllegalArgumentException) {
            throw AssertionError("WireGuard fixture is not valid Base64")
        }
        assumeTrue("WireGuard fixture exceeds 64 KB", bytes.size <= MAX_CONFIG_BYTES)
        val stateDir = temporaryStateDir("wireguard")
        val client = try {
            newIsolatedClient(stateDir)
        } catch (failure: Throwable) {
            stateDir.deleteRecursively()
            throw failure
        }
        var primaryFailure: Throwable? = null
        try {
            val config = try {
                Config.parse(ByteArrayInputStream(bytes)).also { parsed ->
                    assertTrue("WireGuard fixture needs an interface address",
                        parsed.`interface`.addresses.isNotEmpty())
                    assertTrue("WireGuard fixture needs a peer", parsed.peers.isNotEmpty())
                }
            } catch (_: Exception) {
                throw AssertionError("WireGuard fixture is invalid")
            }
            val uapi = try {
                config.toWgUserspaceString().also {
                    assertTrue("WireGuard fixture endpoint could not be resolved",
                        it.lineSequence().count { line -> line.startsWith("endpoint=") } ==
                            config.peers.count { peer -> peer.endpoint.isPresent })
                    assertTrue("WireGuard fixture needs a peer endpoint", config.peers.all { it.endpoint.isPresent })
                }
            } catch (_: Exception) {
                throw AssertionError("WireGuard fixture cannot produce a userspace configuration")
            }
            val iface = config.`interface`
            try {
                client.startWireGuard(
                    uapi,
                    JSONArray(iface.addresses.mapNotNull { it.address.hostAddress }).toString(),
                    JSONArray(iface.dnsServers.mapNotNull { it.hostAddress }).toString(),
                    iface.mtu.orElse(DEFAULT_MTU).toLong(),
                )
            } catch (_: Exception) {
                // Native failures can embed the parsed UAPI, which contains the private key.
                throw AssertionError("WireGuard native start failed")
            } catch (_: LinkageError) {
                throw AssertionError("appnet native library is unavailable")
            }

            val response = client.httpGet(WIREGUARD_HTTP_URL, "")
            assertTrue("WireGuard HTTP status was ${response.statusCode.toInt()}",
                response.statusCode.toInt() in 200..299)
            assertTrue("WireGuard HTTP response did not contain the expected marker",
                response.body.contains(WIREGUARD_MARKER))

            client.stopWireGuard()
            assertHttpGetFails(client, WIREGUARD_HTTP_URL)
            LocalConnectionProbe().use { probe ->
                assertHttpGetFails(client, probe.url)
                probe.assertNoAcceptedConnections()
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            bytes.fill(0)
            cleanup(client, stateDir, primaryFailure) { it.stopWireGuard() }
        }
    }

    @Test fun tailscaleCarriesEncryptedHttpAfterAuthorization() = runBlocking {
        val controlURL = InstrumentationRegistry.getArguments().getString("headscaleControlURL")?.trim()
        assumeTrue("Requires runner argument headscaleControlURL", !controlURL.isNullOrBlank())

        val stateDir = temporaryStateDir("tailscale")
        val client = try {
            newIsolatedClient(stateDir)
        } catch (failure: Throwable) {
            stateDir.deleteRecursively()
            throw failure
        }
        var primaryFailure: Throwable? = null
        try {
            client.startTailscale(controlURL)
            client.loginTailscale()
            val status = withTimeout(TAILSCALE_RUNNING_TIMEOUT_MS) { awaitTailscaleRunning(client) }
            assertTrue("Tailscale did not report an address", status.addresses.isNotEmpty())
            assertTrue("Tailscale did not report connected", status.connected)

            TAILSCALE_HTTP_URLS.forEach { url ->
                val response = client.httpGet(url, "")
                assertTrue("Tailscale HTTP status for $url was ${response.statusCode.toInt()}",
                    response.statusCode.toInt() in 200..299)
                assertTrue("Tailscale HTTP response for $url did not contain the expected marker",
                    response.body.contains(TAILSCALE_MARKER))
            }
            LocalConnectionProbe().use { probe ->
                assertHttpGetFails(client, probe.url)
                probe.assertNoAcceptedConnections()
            }
            client.stopTailscale()
            client.startTailscale(controlURL)
            withTimeout(TAILSCALE_RUNNING_TIMEOUT_MS) {
                awaitTailscaleRunning(client, requireAuthorization = false)
            }
            TAILSCALE_HTTP_URLS.forEach { url ->
                val response = client.httpGet(url, "")
                assertTrue("Tailscale HTTP status after reconnect for $url was ${response.statusCode.toInt()}",
                    response.statusCode.toInt() in 200..299)
                assertTrue("Tailscale HTTP response after reconnect for $url did not contain the expected marker",
                    response.body.contains(TAILSCALE_MARKER))
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            cleanup(client, stateDir, primaryFailure) {
                it.stopTailscale()
                it.logoutTailscale(controlURL)
            }
        }
    }

    @Test fun targetPackageDeclaresNoVpnServiceBindings() {
        @Suppress("DEPRECATION")
        val services = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services
            .orEmpty()
        assertTrue("The target package still declares a BIND_VPN_SERVICE service",
            services.none { it.permission == permission.BIND_VPN_SERVICE })
        assertTrue("The target package still declares a VpnService intent handler",
            context.packageManager.queryIntentServices(
                Intent(VpnService.SERVICE_INTERFACE).setPackage(context.packageName), 0
            ).isEmpty())
    }

    private fun newIsolatedClient(stateDir: File): Client {
        check(stateDir.isDirectory || stateDir.mkdirs())
        initializeAppnet(context)
        val values = ConcurrentHashMap<String, String>()
        return Appnet.newClient(
            stateDir.absolutePath,
            object : StateStore {
                override fun read(key: String): String = values[key].orEmpty()
                override fun write(key: String, value: String) {
                    values[key] = value
                }
            },
        )
    }

    private fun temporaryStateDir(name: String): File =
        File(context.cacheDir, "appnet-data-plane-test/$name-${System.nanoTime()}")

    private fun assertHttpGetFails(client: Client, url: String) {
        try {
            client.httpGet(url, "")
        } catch (_: Exception) {
            return
        }
        throw AssertionError("httpGet unexpectedly succeeded for $url")
    }

    private suspend fun awaitTailscaleRunning(
        client: Client,
        requireAuthorization: Boolean = true,
    ): TailscaleStatus {
        var authUrlSent = false
        while (true) {
            val status = parseTailscaleStatus(client.tailscaleStatusJSON())
            if (!authUrlSent && status.authUrl.isNotBlank()) {
                // The parent run captures the complete instrumentation stream and parses only this key.
                instrumentation.sendStatus(STATUS_APPNET, Bundle().apply {
                    putString(AUTH_URL_STATUS_KEY, status.authUrl)
                })
                authUrlSent = true
            }
            if (status.backendState == "Running" && status.connected) {
                if (requireAuthorization && !authUrlSent) {
                    throw AssertionError("Tailscale reached Running without exposing an authorization URL")
                }
                return status
            }
            delay(TAILSCALE_POLL_INTERVAL_MS)
        }
    }

    private fun parseTailscaleStatus(value: String): TailscaleStatus {
        val json = JSONObject(value)
        return TailscaleStatus(
            backendState = json.optString("backendState"),
            authUrl = json.optString("authUrl"),
            addresses = json.optJSONArray("addresses")?.let { addresses ->
                List(addresses.length()) { index -> addresses.optString(index) }
            }.orEmpty(),
            health = json.optJSONArray("health")?.let { health ->
                List(health.length()) { index -> health.optString(index) }
            }.orEmpty(),
            connected = json.optBoolean("connected", false),
        )
    }

    private inline fun cleanup(
        client: Client,
        stateDir: File,
        primaryFailure: Throwable?,
        stop: (Client) -> Unit,
    ) {
        var cleanupFailure: Throwable? = null
        try {
            try { stop(client) } catch (_: Throwable) {
                // Native cleanup errors can embed private configuration; keep them out of test output.
                cleanupFailure = AssertionError("appnet cleanup stop failed")
            }
            try { client.close() } catch (_: Throwable) {
                if (cleanupFailure == null) cleanupFailure = AssertionError("appnet cleanup close failed")
            }
        } finally {
            try {
                assertTrue("Temporary appnet state was not removed",
                    !stateDir.exists() || stateDir.deleteRecursively())
            } catch (deletionFailure: Throwable) {
                if (cleanupFailure != null) cleanupFailure.addSuppressed(deletionFailure)
                else cleanupFailure = deletionFailure
            }
        }
        cleanupFailure?.let { failure ->
            if (primaryFailure != null) primaryFailure.addSuppressed(failure) else throw failure
        }
    }

    private class LocalConnectionProbe : AutoCloseable {
        private companion object {
            const val ACCEPT_POLL_MS = 100
            const val CONNECTION_GRACE_MS = 500L
        }

        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
            soTimeout = ACCEPT_POLL_MS
        }
        private val acceptedConnections = AtomicInteger()
        @Volatile private var closed = false
        private val thread = Thread {
            while (!closed) {
                try {
                    server.accept().use { acceptedConnections.incrementAndGet() }
                } catch (_: SocketTimeoutException) {
                } catch (_: SocketException) {
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val url = "http://127.0.0.1:${server.localPort}/"

        fun assertNoAcceptedConnections() {
            Thread.sleep(CONNECTION_GRACE_MS)
            assertTrue("httpGet attempted a direct localhost connection",
                acceptedConnections.get() == 0)
        }

        override fun close() {
            closed = true
            try { server.close() } finally { thread.join(1_000) }
        }
    }

    private data class TailscaleStatus(
        val backendState: String,
        val authUrl: String,
        val addresses: List<String>,
        val health: List<String>,
        val connected: Boolean,
    )

    private companion object {
        const val MAX_CONFIG_BYTES = 64 * 1024
        const val DEFAULT_MTU = 1280
        const val STATUS_APPNET = 2
        const val AUTH_URL_STATUS_KEY = "appnetTestAuthUrl"
        const val WIREGUARD_HTTP_URL = "http://192.0.2.1:18080"
        const val WIREGUARD_MARKER = "x2-wireguard-encrypted-data-plane-ok"
        const val TAILSCALE_MARKER = "x2-headscale-encrypted-http-ok"
        const val TAILSCALE_RUNNING_TIMEOUT_MS = 180_000L
        const val TAILSCALE_POLL_INTERVAL_MS = 1_000L
        val TAILSCALE_HTTP_URLS = listOf(
            "http://100.127.250.1:18082/",
            "http://x2-test-peer.x2-headscale.test:18082/",
        )
    }
}
