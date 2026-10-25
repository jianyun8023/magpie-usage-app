package com.jianyun.x2app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jianyun.x2app.api.MagpieApi
import com.jianyun.x2app.api.friendlyMessage
import com.jianyun.x2app.data.Ledger
import com.jianyun.x2app.data.QuotaCard
import com.jianyun.x2app.data.ServerConfig
import com.jianyun.x2app.data.SettingsStore
import com.jianyun.x2app.data.UsageSummary
import com.jianyun.x2app.data.normalizedServerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Ready<T>(
        val data: T,
        val refreshing: Boolean = false,
        val updatedAt: Long? = null,
        val updateError: String? = null,
    ) : LoadState<T>
    data class Failed(val message: String) : LoadState<Nothing>
}

class AppViewModel internal constructor(private val config: StateFlow<ServerConfig>) : ViewModel() {
    constructor(settings: SettingsStore) : this(settings.config)

    val quotas = MutableStateFlow<LoadState<List<QuotaCard>>>(LoadState.Loading)
    val ledger = MutableStateFlow<LoadState<Ledger>>(LoadState.Loading)
    val usage = MutableStateFlow<LoadState<UsageSummary>>(LoadState.Loading)

    val ledgerPeriod = MutableStateFlow("7d")
    val ledgerFailedOnly = MutableStateFlow(false)
    val ledgerAgent = MutableStateFlow<String?>(null)
    val ledgerProvider = MutableStateFlow<String?>(null)
    val ledgerPage = MutableStateFlow(0)
    val usagePeriod = MutableStateFlow("7d")

    private var loadedConfig: ServerConfig? = null
    private var quotasJob: Job? = null
    private var ledgerJob: Job? = null
    private var usageJob: Job? = null
    private var quotasVersion = 0
    private var ledgerVersion = 0
    private var usageVersion = 0

    init {
        viewModelScope.launch {
            config.collect { ensureConfig() }
        }
    }

    private fun ensureConfig(): Boolean {
        val current = config.value
        if (current != loadedConfig) {
            loadedConfig = current
            quotasJob?.cancel()
            ledgerJob?.cancel()
            usageJob?.cancel()
            quotasVersion += 1
            ledgerVersion += 1
            usageVersion += 1
            ledgerPage.value = 0
            ledgerAgent.value = null
            ledgerProvider.value = null
            val emptyState = if (current.isSet) LoadState.Loading else LoadState.Failed("请填写服务器地址")
            quotas.value = emptyState
            ledger.value = emptyState
            usage.value = emptyState
        }
        return current.isSet
    }

    fun loadQuotas() {
        if (ensureConfig() && quotas.value is LoadState.Loading) fetchQuotas(asked = false)
    }

    fun loadLedger() {
        if (ensureConfig() && ledger.value is LoadState.Loading) refreshLedger()
    }

    fun loadUsage() {
        if (ensureConfig() && usage.value is LoadState.Loading) refreshUsage()
    }

    fun refreshQuotas() = fetchQuotas(asked = true)

    fun refreshQuota(card: QuotaCard) = fetchQuotas(asked = true, provider = card.provider, user = card.user)

    private fun fetchQuotas(asked: Boolean, provider: String? = null, user: String? = null) {
        if (!ensureConfig()) return
        if (quotasJob?.isActive == true) {
            if (!asked) return
            quotasJob?.cancel()
        }
        val requestConfig = config.value
        val api = MagpieApi(requestConfig.baseUrl, requestConfig.webKey)
        val version = ++quotasVersion
        val previous = quotas.value as? LoadState.Ready
        quotas.value = previous?.copy(refreshing = true, updateError = null) ?: LoadState.Loading
        quotasJob = viewModelScope.launch {
            try {
                val data = api.quotas(asked = asked, provider = provider, user = user)
                if (ensureConfig() && requestConfig == loadedConfig && version == quotasVersion) {
                    quotas.value = LoadState.Ready(data, updatedAt = System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ensureConfig() && requestConfig == loadedConfig && version == quotasVersion) {
                    quotas.value = previous?.copy(refreshing = false, updateError = e.friendlyMessage())
                        ?: LoadState.Failed(e.friendlyMessage())
                }
            }
        }
    }

    fun refreshLedger() {
        if (!ensureConfig() || ledgerJob?.isActive == true) return
        val requestConfig = config.value
        val api = MagpieApi(requestConfig.baseUrl, requestConfig.webKey)
        val version = ++ledgerVersion
        val previous = ledger.value as? LoadState.Ready
        val period = ledgerPeriod.value
        val failed = ledgerFailedOnly.value
        val agent = ledgerAgent.value
        val provider = ledgerProvider.value
        var page = ledgerPage.value
        ledger.value = previous?.copy(refreshing = true, updateError = null) ?: LoadState.Loading
        ledgerJob = viewModelScope.launch {
            var correctedPage = false
            try {
                repeat(2) { attempt ->
                    val data = api.requests(
                        period = period,
                        limit = LEDGER_PAGE_SIZE,
                        offset = page * LEDGER_PAGE_SIZE,
                        failed = failed,
                        agent = agent,
                        provider = provider,
                    )
                    if (!ensureConfig() || requestConfig != loadedConfig || version != ledgerVersion) return@launch
                    val lastPage = (data.total.coerceAtLeast(1) - 1) / LEDGER_PAGE_SIZE
                    if (page > lastPage) {
                        page = lastPage
                        ledgerPage.value = page
                        correctedPage = true
                        ledger.value = if (attempt == 0) LoadState.Loading
                            else LoadState.Failed("请求记录数量持续变化，请刷新重试")
                        return@repeat
                    }
                    ledger.value = LoadState.Ready(data, updatedAt = System.currentTimeMillis())
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ensureConfig() && requestConfig == loadedConfig && version == ledgerVersion) {
                    ledger.value = previous?.takeUnless { correctedPage }
                        ?.copy(refreshing = false, updateError = e.friendlyMessage())
                        ?: LoadState.Failed(e.friendlyMessage())
                }
            }
        }
    }

    fun refreshUsage() {
        if (!ensureConfig() || usageJob?.isActive == true) return
        val requestConfig = config.value
        val api = MagpieApi(requestConfig.baseUrl, requestConfig.webKey)
        val version = ++usageVersion
        val previous = usage.value as? LoadState.Ready
        usage.value = previous?.copy(refreshing = true, updateError = null) ?: LoadState.Loading
        usageJob = viewModelScope.launch {
            try {
                val data = api.usage(usagePeriod.value)
                if (ensureConfig() && requestConfig == loadedConfig && version == usageVersion) {
                    usage.value = LoadState.Ready(data, updatedAt = System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (ensureConfig() && requestConfig == loadedConfig && version == usageVersion) {
                    usage.value = previous?.copy(refreshing = false, updateError = e.friendlyMessage())
                        ?: LoadState.Failed(e.friendlyMessage())
                }
            }
        }
    }

    fun setLedgerPeriod(period: String) {
        ledgerPeriod.value = period
        ledgerPage.value = 0
        restartLedger()
    }

    fun toggleFailedOnly() {
        ledgerFailedOnly.value = !ledgerFailedOnly.value
        ledgerPage.value = 0
        restartLedger()
    }

    fun setLedgerAgent(agent: String?) {
        ledgerAgent.value = agent
        ledgerPage.value = 0
        restartLedger()
    }

    fun setLedgerProvider(provider: String?) {
        ledgerProvider.value = provider
        ledgerPage.value = 0
        restartLedger()
    }

    fun resetLedgerFilters() {
        ledgerPeriod.value = "7d"
        ledgerFailedOnly.value = false
        ledgerAgent.value = null
        ledgerProvider.value = null
        ledgerPage.value = 0
        restartLedger()
    }

    fun previousLedgerPage() {
        if (ledgerPage.value == 0) return
        ledgerPage.value -= 1
        restartLedger()
    }

    fun nextLedgerPage(total: Int) {
        if ((ledgerPage.value + 1) * LEDGER_PAGE_SIZE >= total) return
        ledgerPage.value += 1
        restartLedger()
    }

    fun setLedgerPage(page: Int, total: Int) {
        val target = page.coerceIn(0, (total.coerceAtLeast(1) - 1) / LEDGER_PAGE_SIZE)
        if (target == ledgerPage.value) return
        ledgerPage.value = target
        restartLedger()
    }

    private fun restartLedger() {
        ledgerPage.value = ledgerPage.value.coerceAtLeast(0)
        ledgerVersion += 1
        ledgerJob?.cancel()
        ledger.value = LoadState.Loading
        refreshLedger()
    }

    fun setUsagePeriod(period: String) {
        usagePeriod.value = period
        usageVersion += 1
        usageJob?.cancel()
        usage.value = LoadState.Loading
        refreshUsage()
    }

    suspend fun testConnection(baseUrl: String, webKey: String): String {
        return try {
            val config = normalizedServerConfig(baseUrl, webKey)
            MagpieApi(config.baseUrl, config.webKey).quotas()
            "连接成功，额度接口通过"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.friendlyMessage()
        }
    }

    class Factory(private val settings: SettingsStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(settings) as T
    }

    companion object {
        const val LEDGER_PAGE_SIZE = 20
    }
}
