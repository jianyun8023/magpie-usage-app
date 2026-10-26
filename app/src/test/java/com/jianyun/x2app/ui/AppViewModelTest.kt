package com.jianyun.x2app.ui

import androidx.lifecycle.viewModelScope
import com.jianyun.x2app.api.MagpieApi
import com.jianyun.x2app.data.ServerConfig
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModelTest {
    @Test
    fun cachedPagesReloadAfterUrlOrKeyChange() = runTest {
        withFixture { vm, config, first, second ->
            loadAll(vm)
            await { allReady(vm) }
            assertEquals("first", (vm.quotas.value as LoadState.Ready).data.single().provider)
            vm.ledgerPage.value = 3
            vm.ledgerAgent.value = "old-agent"
            vm.ledgerProvider.value = "old-provider"

            config.value = ServerConfig(second.url, "new-key")
            loadAll(vm)
            assertTrue(vm.quotas.value is LoadState.Loading)
            assertTrue(vm.ledger.value is LoadState.Loading)
            assertTrue(vm.usage.value is LoadState.Loading)
            assertEquals(0, vm.ledgerPage.value)
            assertEquals(null, vm.ledgerAgent.value)
            assertEquals(null, vm.ledgerProvider.value)
            await { allReady(vm) }
            assertEquals("second", (vm.quotas.value as LoadState.Ready).data.single().provider)
            assertTrue(second.authorizations.all { it.endsWith("Bearer new-key") })

            second.status = 401
            config.value = config.value.copy(webKey = "invalid-key")
            loadAll(vm)
            await {
                vm.quotas.value is LoadState.Failed && vm.ledger.value is LoadState.Failed &&
                    vm.usage.value is LoadState.Failed
            }
            assertEquals("鉴权失败，请检查网关 API Key", (vm.quotas.value as LoadState.Failed).message)
            assertEquals(3, first.authorizations.size)
            assertEquals(3, second.authorizations.count { it.endsWith("Bearer invalid-key") })
        }
    }

    @Test
    fun configObserverClearsAllPagesEvenWithoutPageLoad() = runTest {
        withFixture { vm, config, _, _ ->
            loadAll(vm)
            await { allReady(vm) }
            config.value = ServerConfig()
            runCurrent()
            assertTrue(vm.quotas.value is LoadState.Failed)
            assertTrue(vm.ledger.value is LoadState.Failed)
            assertTrue(vm.usage.value is LoadState.Failed)
        }
    }

    @Test
    fun lateRequestsCannotRestoreOldServerData() = runTest {
        withFixture { vm, config, first, second ->
            val observers = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
            val gate = first.block()
            loadAll(vm)
            await { first.authorizations.size == 3 }
            val oldRequests = vm.viewModelScope.coroutineContext[Job]!!.children.filterNot { it in observers }.toList()
            config.value = ServerConfig(second.url, "second-key")
            loadAll(vm)
            await { allReady(vm) }
            val expected = Triple(vm.quotas.value, vm.ledger.value, vm.usage.value)
            gate.countDown()
            await { first.completed.get() == 3 && oldRequests.all { it.isCompleted } }
            assertEquals(expected, Triple(vm.quotas.value, vm.ledger.value, vm.usage.value))
            assertTrue(first.authorizations.all { it.endsWith("Bearer first-key") })
            assertTrue(second.authorizations.all { it.endsWith("Bearer second-key") })
        }
    }

    @Test
    fun changedQueriesDropOldDataButManualRefreshRetainsIt() = runTest {
        withFixture { vm, _, server, _ ->
            loadAll(vm)
            await { allReady(vm) }
            server.status = 401
            vm.refreshUsage()
            assertTrue((vm.usage.value as LoadState.Ready).refreshing)
            await { (vm.usage.value as? LoadState.Ready)?.updateError != null }
            vm.setUsagePeriod("30d")
            assertTrue(vm.usage.value is LoadState.Loading)
            await { vm.usage.value is LoadState.Failed }

            val changes = listOf<() -> Unit>(
                { vm.setLedgerPeriod("30d") },
                { vm.setLedgerAgent("agent") },
                { vm.setLedgerProvider("provider") },
                { vm.toggleFailedOnly() },
                { vm.nextLedgerPage(100) },
                { vm.previousLedgerPage() },
            )
            for (change in changes) {
                server.status = 200
                vm.refreshLedger()
                await { (vm.ledger.value as? LoadState.Ready)?.refreshing == false }
                server.status = 401
                change()
                assertTrue(vm.ledger.value is LoadState.Loading)
                await { vm.ledger.value is LoadState.Failed }
            }
        }
    }

    @Test
    fun lateResponsesCannotOverwriteChangedQueries() = runTest {
        withFixture { vm, _, server, _ ->
            val observers = vm.viewModelScope.coroutineContext[Job]!!.children.toSet()
            val gate = server.block()
            vm.loadLedger()
            vm.loadUsage()
            await { server.authorizations.size == 2 }
            val oldRequests = vm.viewModelScope.coroutineContext[Job]!!.children.filterNot { it in observers }.toList()
            server.gate = null
            server.status = 401
            vm.setLedgerProvider("new-provider")
            vm.setUsagePeriod("30d")
            await { vm.ledger.value is LoadState.Failed && vm.usage.value is LoadState.Failed }
            gate.countDown()
            await { server.completed.get() == 4 && oldRequests.all { it.isCompleted } }
            assertTrue(vm.ledger.value is LoadState.Failed)
            assertTrue(vm.usage.value is LoadState.Failed)
        }
    }

    @Test
    fun providerFilterUsesApiParameterResetsPageAndCanBeCleared() = runTest {
        withFixture { vm, _, server, _ ->
            vm.loadLedger()
            await { vm.ledger.value is LoadState.Ready }
            assertEquals("upstream", (vm.ledger.value as LoadState.Ready).data.providers.single().id)
            vm.nextLedgerPage(100)
            await { (vm.ledger.value as? LoadState.Ready)?.refreshing == false }
            assertEquals(1, vm.ledgerPage.value)
            vm.setLedgerProvider("two/provider")
            assertEquals(0, vm.ledgerPage.value)
            assertEquals("two/provider", vm.ledgerProvider.value)
            assertTrue(vm.ledger.value is LoadState.Loading)
            await { vm.ledger.value is LoadState.Ready }
            assertTrue(server.requests.last().contains("provider=two%2Fprovider"))
            assertTrue(server.requests.last().contains("offset=0"))
            vm.setLedgerProvider(null)
            await { vm.ledger.value is LoadState.Ready }
            assertTrue(!server.requests.last().contains("provider="))
        }
    }

    @Test
    fun connectionTestNormalizesInputAndOnlyClaimsQuotaEndpointPassed() = runTest {
        withFixture { vm, _, server, _ ->
            assertEquals("连接成功，额度接口通过", vm.testConnection("  ${server.url}  ", "  test-key  "))
            assertEquals(listOf("/v1/magpie/quotas/cards"), server.requests.toList())
            assertTrue(server.authorizations.single().endsWith("Bearer test-key"))
            val invalid = vm.testConnection("ftp://127.0.0.1", "key")
            assertTrue(!invalid.startsWith("连接成功"))
            assertEquals(1, server.requests.size)
        }
    }

    @Test
    fun failedProviderQueryCanRecoverByResettingFilters() = runTest {
        withFixture { vm, _, server, _ ->
            vm.loadLedger()
            await { vm.ledger.value is LoadState.Ready }
            server.status = 401
            vm.ledgerPeriod.value = "30d"
            vm.ledgerAgent.value = "agent"
            vm.ledgerFailedOnly.value = true
            vm.setLedgerProvider("broken-provider")
            await { vm.ledger.value is LoadState.Failed }
            server.status = 200
            vm.resetLedgerFilters()
            assertTrue(vm.ledger.value is LoadState.Loading)
            assertEquals(0, vm.ledgerPage.value)
            assertEquals(null, vm.ledgerProvider.value)
            assertEquals(null, vm.ledgerAgent.value)
            assertEquals(false, vm.ledgerFailedOnly.value)
            assertEquals("today", vm.ledgerPeriod.value)
            await { vm.ledger.value is LoadState.Ready }
            val request = server.requests.last()
            assertTrue(request.startsWith("/v1/magpie/usage/requests?period=today&limit=20&offset=0&tz="))
            assertTrue(!request.contains("agent=") && !request.contains("provider=") && !request.contains("failed="))
        }
    }

    @Test
    fun shrinkingTotalCorrectsViewModelPageAndFetchesValidOffset() = runTest {
        withFixture { vm, _, server, _ ->
            vm.loadLedger()
            await { vm.ledger.value is LoadState.Ready }
            vm.ledgerPage.value = 3
            server.ledgerResponses.add(MockResponse().setBody("""{"total":5,"rows":[]}"""))
            val rows = (1..5).joinToString(",") { """{"route_id":$it}""" }
            server.ledgerResponses.add(MockResponse().setBody("""{"total":5,"rows":[$rows]}"""))
            vm.refreshLedger()
            await { (vm.ledger.value as? LoadState.Ready)?.refreshing == false }
            assertEquals(0, vm.ledgerPage.value)
            assertEquals(5, (vm.ledger.value as LoadState.Ready).data.rows.size)
            assertTrue(server.requests.toList().takeLast(2)[0].contains("offset=60"))
            assertTrue(server.requests.last().contains("offset=0"))
            assertEquals(3, server.requests.size)
        }
    }

    @Test
    fun failedCorrectedPageDoesNotRestorePreviousPageData() = runTest {
        withFixture { vm, _, server, _ ->
            vm.loadLedger()
            await { vm.ledger.value is LoadState.Ready }
            vm.ledgerPage.value = 3
            server.ledgerResponses.add(MockResponse().setBody("""{"total":5}"""))
            server.ledgerResponses.add(MockResponse().setResponseCode(500))
            vm.refreshLedger()
            await { vm.ledger.value is LoadState.Failed }
            assertEquals(0, vm.ledgerPage.value)
            vm.refreshLedger()
            await { vm.ledger.value is LoadState.Ready }
            assertTrue(server.requests.last().contains("offset=0"))
        }
    }

    @Test
    fun continuallyShrinkingTotalStopsAfterOneCorrectionRequest() = runTest {
        withFixture { vm, _, server, _ ->
            vm.loadLedger()
            await { vm.ledger.value is LoadState.Ready }
            vm.ledgerPage.value = 3
            server.ledgerResponses.add(MockResponse().setBody("""{"total":41}"""))
            server.ledgerResponses.add(MockResponse().setBody("""{"total":21}"""))
            vm.refreshLedger()
            await { vm.ledger.value is LoadState.Failed }
            assertEquals(1, vm.ledgerPage.value)
            assertEquals(3, server.requests.size)
            assertTrue(server.requests.last().contains("offset=40"))
        }
    }

    @Test
    fun jumpPageClampsBoundsAndClearsPreviousResult() = runTest {
        withFixture { vm, _, server, _ ->
            vm.loadLedger()
            await { vm.ledger.value is LoadState.Ready }
            vm.setLedgerPage(Int.MAX_VALUE, 100)
            assertEquals(4, vm.ledgerPage.value)
            assertTrue(vm.ledger.value is LoadState.Loading)
            await { vm.ledger.value is LoadState.Ready }
            assertTrue(server.requests.last().contains("limit=20&offset=80"))
            val count = server.requests.size
            vm.setLedgerPage(4, 100)
            runCurrent()
            assertEquals(count, server.requests.size)
            vm.setLedgerPage(-1, 100)
            assertEquals(0, vm.ledgerPage.value)
            await { vm.ledger.value is LoadState.Ready }
            assertTrue(server.requests.last().contains("offset=0"))
        }
    }

    @Test
    fun ledgerPassesReaderTimeZoneToBackend() = runTest {
        withFixture { vm, _, server, _ ->
            MagpieApi(server.url, "key").requests(period = "today", timeZone = "Asia/Singapore")
            assertTrue(server.requests.last().contains("tz=Asia%2FSingapore"))
            vm.loadLedger()
            await { vm.ledger.value is LoadState.Ready }
            val zone = java.net.URLEncoder.encode(java.time.ZoneId.systemDefault().id, "UTF-8")
            assertTrue(server.requests.last().contains("tz=$zone"))
        }
    }

    @Test
    fun nextPageDoesNotOverflowWithExtremePageAndTotal() = runTest {
        withFixture { vm, _, server, _ ->
            loadAll(vm)
            await { allReady(vm) }
            val requestCount = server.requests.size
            vm.ledgerPage.value = Int.MAX_VALUE - 1
            vm.nextLedgerPage(Int.MAX_VALUE)
            runCurrent()
            assertEquals(Int.MAX_VALUE - 1, vm.ledgerPage.value)
            assertEquals(requestCount, server.requests.size)
        }
    }

    @Test
    fun explicitQuotaRefreshIsAskedEvenDuringAutomaticLoad() = runTest {
        withFixture { vm, _, server, _ ->
            val gate = server.block()
            vm.loadQuotas()
            await { server.requests.size == 1 }
            assertEquals("/v1/magpie/quotas/cards", server.requests.single())
            server.gate = null
            vm.refreshQuotas()
            await { vm.quotas.value is LoadState.Ready }
            assertEquals("/v1/magpie/quotas/refresh", server.requests.last())
            gate.countDown()
            await { server.completed.get() == 2 }
        }
    }

    @Test
    fun usagePassesSameReaderTimeZoneAsLedger() = runTest {
        withFixture { vm, _, server, _ ->
            MagpieApi(server.url, "key").usage(period = "today", timeZone = "Asia/Singapore")
            assertEquals("/v1/magpie/usage?period=today&tz=Asia%2FSingapore", server.requests.last())
            vm.loadUsage()
            await { vm.usage.value is LoadState.Ready }
            val zone = java.net.URLEncoder.encode(java.time.ZoneId.systemDefault().id, "UTF-8")
            assertEquals("/v1/magpie/usage?period=today&tz=$zone", server.requests.last())
        }
    }

    @Test
    fun cardRefreshPassesItsProviderAndAccount() = runTest {
        withFixture { vm, _, server, _ ->
            vm.loadQuotas()
            await { vm.quotas.value is LoadState.Ready }
            vm.refreshQuota(com.jianyun.x2app.data.QuotaCard(provider = "deepseek", user = "account+1@example.com"))
            await { server.requests.size == 2 && (vm.quotas.value as? LoadState.Ready)?.refreshing == false }
            assertEquals("/v1/magpie/quotas/refresh?provider=deepseek&user=account%2B1%40example.com", server.requests.last())
        }
    }

    private suspend fun TestScope.withFixture(
        test: suspend TestScope.(AppViewModel, MutableStateFlow<ServerConfig>, Server, Server) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val first = Server("first")
        val second = Server("second")
        val config = MutableStateFlow(ServerConfig(first.url, "first-key"))
        val vm = AppViewModel(config)
        try {
            test(vm, config, first, second)
        } finally {
            vm.viewModelScope.cancel()
            first.close()
            second.close()
            runCurrent()
            Dispatchers.resetMain()
        }
    }

    private fun loadAll(vm: AppViewModel) {
        vm.loadQuotas()
        vm.loadLedger()
        vm.loadUsage()
    }

    private fun allReady(vm: AppViewModel) = vm.quotas.value is LoadState.Ready &&
        vm.ledger.value is LoadState.Ready && vm.usage.value is LoadState.Ready

    private suspend fun TestScope.await(predicate: () -> Boolean) {
        repeat(500) {
            runCurrent()
            if (predicate()) return
            withContext(Dispatchers.IO) { Thread.sleep(10) }
        }
        error("Timed out waiting for HTTP response and ViewModel state")
    }

    private class Server(private val marker: String) : AutoCloseable {
        private val server = MockWebServer()
        val url get() = server.url("/").toString().trimEnd('/')
        val authorizations = ConcurrentLinkedQueue<String>()
        val requests = ConcurrentLinkedQueue<String>()
        val ledgerResponses = ConcurrentLinkedQueue<MockResponse>()
        val completed = AtomicInteger()
        private val gates = mutableListOf<CountDownLatch>()
        @Volatile var status = 200
        @Volatile var gate: CountDownLatch? = null

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val responseStatus = status
                    val responseGate = gate
                    requests.add(request.path.orEmpty())
                    authorizations.add(request.getHeader("Authorization").orEmpty())
                    try {
                        check(responseGate?.await(10, TimeUnit.SECONDS) != false)
                        if (request.requestUrl?.encodedPath == "/v1/magpie/usage/requests") {
                            ledgerResponses.poll()?.let { return it }
                        }
                        val body = when (request.requestUrl?.encodedPath) {
                            "/v1/magpie/quotas/cards", "/v1/magpie/quotas/refresh" -> """{"object":"list","data":[{"provider":"$marker"}]}"""
                            "/v1/magpie/usage/requests" -> """{"period":"$marker","total":100,"providers":[{"id":"upstream","name":"上游"}]}"""
                            else -> """{"period":"$marker"}"""
                        }
                        return MockResponse().setResponseCode(responseStatus).setBody(body)
                    } finally {
                        completed.incrementAndGet()
                    }
                }
            }
            server.start()
        }

        fun block(): CountDownLatch = CountDownLatch(1).also { gates.add(it); gate = it }

        override fun close() {
            gates.forEach { it.countDown() }
            server.shutdown()
        }
    }
}
