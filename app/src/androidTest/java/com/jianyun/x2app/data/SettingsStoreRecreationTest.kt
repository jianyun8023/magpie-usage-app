package com.jianyun.x2app.data

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.jianyun.x2app.MainActivity
import com.jianyun.x2app.ui.AppViewModel
import com.jianyun.x2app.ui.LoadState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class SettingsStoreRecreationTest {
    @Test fun recreatedActivityUpdatesRetainedViewModelRequestConfiguration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("x2app", Context.MODE_PRIVATE)
        val keys = setOf("base_url", "web_key", "ignored_quota_ids")
        val original = prefs.all.filterKeys { it in keys }.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        }
        val settings = SettingsStore.get(context)
        try {
            QuotaServer("before-recreate").use { oldServer ->
                QuotaServer("after-recreate").use { newServer ->
                    val oldKey = UUID.randomUUID().toString()
                    val newKey = UUID.randomUUID().toString()
                    instrumentation.runOnMainSync { settings.save(oldServer.url, oldKey) }
                    val intent = Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                        lateinit var oldActivity: MainActivity
                        lateinit var retained: AppViewModel
                        scenario.onActivity { activity ->
                            oldActivity = activity
                            retained = ViewModelProvider(activity, AppViewModel.Factory(SettingsStore.get(activity)))[AppViewModel::class.java]
                            retained.refreshQuotas()
                        }
                        assertTrue("Initial VM request did not use the original fixture and Cookie",
                            oldServer.receivedCookie(oldKey))
                        awaitQuota(retained, "before-recreate")

                        scenario.recreate()
                        scenario.onActivity { activity ->
                            assertNotSame("ActivityScenario must create a new Activity", oldActivity, activity)
                            val newSettings = SettingsStore.get(activity)
                            val current = ViewModelProvider(activity, AppViewModel.Factory(newSettings))[AppViewModel::class.java]
                            assertSame("Configuration change must retain the same ViewModel", retained, current)
                            assertSame("Both Activities must share the configuration flow owner", settings, newSettings)
                            newSettings.save(newServer.url, newKey)
                            current.refreshQuotas()
                        }
                        // A request reaching this second listener proves the new address was used.
                        // Boolean assertions deliberately never print Cookie values or credentials.
                        assertTrue("Retained VM did not reach the new server with the new Cookie",
                            newServer.receivedCookie(newKey))
                        awaitQuota(retained, "after-recreate")
                    }
                }
            }
        } finally {
            // Close ActivityScenario before restoring: no live UI should contact the real server.
            // Preserve missing keys as missing and leave every other preference untouched.
            instrumentation.runOnMainSync {
                val editor = prefs.edit()
                for (name in listOf("base_url", "web_key")) {
                    if (original.containsKey(name)) editor.putString(name, original[name] as String?)
                    else editor.remove(name)
                }
                @Suppress("UNCHECKED_CAST")
                val ignored = (original["ignored_quota_ids"] as? Set<String>)?.toSet()
                if (original.containsKey("ignored_quota_ids")) editor.putStringSet("ignored_quota_ids", ignored)
                else editor.remove("ignored_quota_ids")
                val restored = editor.commit()
                // Restore the shared in-memory flows too, including an originally unset URL.
                // Using save() here would normalize the backup or reject an empty original URL.
                (settings.config as MutableStateFlow<ServerConfig>).value = ServerConfig(
                    original["base_url"] as? String ?: "", original["web_key"] as? String ?: "",
                )
                (settings.ignoredQuotaIds as MutableStateFlow<Set<String>>).value = ignored ?: emptySet()
                assertTrue("Original app preferences could not be restored", restored)
            }
        }
    }

    private fun awaitQuota(vm: AppViewModel, provider: String) = runBlocking {
        withTimeout(10_000) {
            vm.quotas.first { state -> state is LoadState.Ready && state.data.any { it.provider == provider } }
        }
    }

    /** Loopback only; records headers in memory and never logs requests. */
    private class QuotaServer(provider: String) : Closeable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"
        private val cookies = LinkedBlockingQueue<String>()
        private val body = """[{"provider":"$provider"}]""".toByteArray(Charsets.UTF_8)
        private val worker = thread(name = "settings-recreation-http", isDaemon = true) {
            while (!server.isClosed) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 3_000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val request = reader.readLine().orEmpty()
                        var cookie = ""
                        var host = ""
                        var count = 0
                        while (count++ < 64) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            when (line.substringBefore(':').lowercase()) {
                                "cookie" -> cookie = line.substringAfter(':').trim()
                                "host" -> host = line.substringAfter(':').trim()
                            }
                        }
                        if (request.startsWith("GET /api/usage/quotas") && host == "127.0.0.1:${server.localPort}") {
                            cookies.offer(cookie)
                        }
                        socket.getOutputStream().apply {
                            write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                            write(body)
                            flush()
                        }
                    }
                } catch (_: java.io.IOException) {
                    // Config changes may cancel a request; the next request must still be served.
                }
            }
        }

        fun receivedCookie(key: String): Boolean {
            val expected = "magpie_web_${server.localPort}=$key"
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return false
                val actual = cookies.poll(remaining, TimeUnit.NANOSECONDS) ?: return false
                if (actual == expected) return true
            }
        }

        override fun close() {
            server.close()
            worker.join(5_000)
        }
    }
}
