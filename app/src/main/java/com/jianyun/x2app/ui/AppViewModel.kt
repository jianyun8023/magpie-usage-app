package com.jianyun.x2app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.jianyun.x2app.api.MagpieApi
import com.jianyun.x2app.api.friendlyMessage
import com.jianyun.x2app.data.Ledger
import com.jianyun.x2app.data.QuotaCard
import com.jianyun.x2app.data.SettingsStore
import com.jianyun.x2app.data.UsageSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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

class AppViewModel(private val settings: SettingsStore) : ViewModel() {
    private val api = MagpieApi("", "")

    val quotas = MutableStateFlow<LoadState<List<QuotaCard>>>(LoadState.Loading)
    val ledger = MutableStateFlow<LoadState<Ledger>>(LoadState.Loading)
    val usage = MutableStateFlow<LoadState<UsageSummary>>(LoadState.Loading)

    val ledgerPeriod = MutableStateFlow("7d")
    val ledgerFailedOnly = MutableStateFlow(false)
    val ledgerAgent = MutableStateFlow<String?>(null)
    val ledgerPage = MutableStateFlow(0)
    val usagePeriod = MutableStateFlow("7d")

    private var loadedConfig = ""
    private var quotasJob: Job? = null
    private var ledgerJob: Job? = null
    private var usageJob: Job? = null
    private var quotasVersion = 0
    private var ledgerVersion = 0
    private var usageVersion = 0

    private fun ensureConfig(): Boolean {
        val config = settings.config.value
        if (!config.isSet) return false
        val signature = config.baseUrl + "|" + config.webKey
        if (signature != loadedConfig) {
            loadedConfig = signature
            api.updateConfig(config.baseUrl, config.webKey)
            quotasJob?.cancel()
            ledgerJob?.cancel()
            usageJob?.cancel()
            quotasVersion += 1
            ledgerVersion += 1
            usageVersion += 1
            ledgerPage.value = 0
            quotas.value = LoadState.Loading
            ledger.value = LoadState.Loading
            usage.value = LoadState.Loading
        }
        return true
    }

    fun loadQuotas() {
        if (quotas.value is LoadState.Loading) refreshQuotas()
    }

    fun loadLedger() {
        if (ledger.value is LoadState.Loading) refreshLedger()
    }

    fun loadUsage() {
        if (usage.value is LoadState.Loading) refreshUsage()
    }

    fun refreshQuotas() {
        if (!ensureConfig() || quotasJob?.isActive == true) return
        val version = ++quotasVersion
        val previous = quotas.value as? LoadState.Ready
        quotas.value = previous?.copy(refreshing = true, updateError = null) ?: LoadState.Loading
        quotasJob = viewModelScope.launch {
            try {
                val data = api.quotas()
                if (version == quotasVersion) {
                    quotas.value = LoadState.Ready(data, updatedAt = System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == quotasVersion) {
                    quotas.value = previous?.copy(refreshing = false, updateError = e.friendlyMessage())
                        ?: LoadState.Failed(e.friendlyMessage())
                }
            }
        }
    }

    fun refreshLedger() {
        if (!ensureConfig() || ledgerJob?.isActive == true) return
        val version = ++ledgerVersion
        val previous = ledger.value as? LoadState.Ready
        ledger.value = previous?.copy(refreshing = true, updateError = null) ?: LoadState.Loading
        ledgerJob = viewModelScope.launch {
            try {
                val data = api.requests(
                    period = ledgerPeriod.value,
                    limit = LEDGER_PAGE_SIZE,
                    offset = ledgerPage.value * LEDGER_PAGE_SIZE,
                    failed = ledgerFailedOnly.value,
                    agent = ledgerAgent.value,
                )
                if (version == ledgerVersion) {
                    ledger.value = LoadState.Ready(data, updatedAt = System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == ledgerVersion) {
                    ledger.value = previous?.copy(refreshing = false, updateError = e.friendlyMessage())
                        ?: LoadState.Failed(e.friendlyMessage())
                }
            }
        }
    }

    fun refreshUsage() {
        if (!ensureConfig() || usageJob?.isActive == true) return
        val version = ++usageVersion
        val previous = usage.value as? LoadState.Ready
        usage.value = previous?.copy(refreshing = true, updateError = null) ?: LoadState.Loading
        usageJob = viewModelScope.launch {
            try {
                val data = api.usage(usagePeriod.value)
                if (version == usageVersion) {
                    usage.value = LoadState.Ready(data, updatedAt = System.currentTimeMillis())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (version == usageVersion) {
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

    private fun restartLedger() {
        ledgerPage.value = ledgerPage.value.coerceAtLeast(0)
        ledgerJob?.cancel()
        refreshLedger()
    }

    fun setUsagePeriod(period: String) {
        usagePeriod.value = period
        usageJob?.cancel()
        refreshUsage()
    }

    suspend fun testConnection(baseUrl: String, webKey: String): String {
        if (baseUrl.isBlank()) return "请填写服务器地址"
        return try {
            MagpieApi(baseUrl, webKey).quotas()
            "连接成功，Magpie 数据可读取"
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
        const val LEDGER_PAGE_SIZE = 5
    }
}
