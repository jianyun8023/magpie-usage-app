package com.jianyun.x2app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.data.SettingsStore
import com.jianyun.x2app.ui.AppViewModel
import com.jianyun.x2app.ui.Ink
import com.jianyun.x2app.ui.LoadState
import com.jianyun.x2app.ui.InkButton
import com.jianyun.x2app.ui.LedgerScreen
import com.jianyun.x2app.ui.QuotasScreen
import com.jianyun.x2app.ui.SettingsScreen
import com.jianyun.x2app.ui.UsageScreen
import com.jianyun.x2app.ui.inkClickable

enum class Tab(val label: String) { QUOTAS("额度"), LEDGER("流水"), USAGE("用量") }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.jianyun.x2app.vpn.AppNetworkEngine.get(applicationContext)
        val settings = SettingsStore.get(this)
        setContent { App(settings) }
    }
}

@Composable
fun App(settings: SettingsStore) {
    val vm: AppViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        factory = AppViewModel.Factory(settings),
    )
    var tabName by rememberSaveable { mutableStateOf(Tab.QUOTAS.name) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val tab = Tab.valueOf(tabName)
    val config by settings.config.collectAsState()
    val quotasState by vm.quotas.collectAsState()
    val ignoredQuotaIds by settings.ignoredQuotaIds.collectAsState()
    val ledgerState by vm.ledger.collectAsState()
    val usageState by vm.usage.collectAsState()
    val ledgerPeriod by vm.ledgerPeriod.collectAsState()
    val ledgerFailedOnly by vm.ledgerFailedOnly.collectAsState()
    val ledgerAgent by vm.ledgerAgent.collectAsState()
    val ledgerProvider by vm.ledgerProvider.collectAsState()
    val ledgerPage by vm.ledgerPage.collectAsState()
    val usagePeriod by vm.usagePeriod.collectAsState()
    val context = LocalContext.current
    val engine = remember { com.jianyun.x2app.vpn.AppNetworkEngine.get(context) }
    val tailscaleState by engine.tailscale.collectAsState()

    LaunchedEffect(config, tab, showSettings, tailscaleState.connected) {
        if (config.isSet && !showSettings) {
            when (tab) {
                Tab.QUOTAS -> if (tailscaleState.connected && quotasState is LoadState.Failed) {
                    vm.refreshQuotas()
                } else vm.loadQuotas()
                Tab.LEDGER -> if (tailscaleState.connected && ledgerState is LoadState.Failed) {
                    vm.refreshLedger()
                } else vm.loadLedger()
                Tab.USAGE -> if (tailscaleState.connected && usageState is LoadState.Failed) {
                    vm.refreshUsage()
                } else vm.loadUsage()
            }
        }
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Ink.accent,
            onPrimary = Ink.bg,
            background = Ink.bg,
            surface = Ink.bg,
            onSurface = Ink.fg,
            outline = Ink.line,
            error = Ink.danger,
        ),
    ) {
        Scaffold(
            containerColor = Ink.bg,
            topBar = {
                Column {
                    Row(
                        Modifier.fillMaxWidth().defaultMinSize(minHeight = 64.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (showSettings || !config.isSet) "设置" else "Magpie ${tab.label}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Ink.fg,
                            modifier = Modifier.weight(1f),
                        )
                        if (!showSettings) {
                            InkButton("刷新", {
                                when (tab) {
                                    Tab.QUOTAS -> vm.refreshQuotas()
                                    Tab.LEDGER -> vm.refreshLedger()
                                    Tab.USAGE -> vm.refreshUsage()
                                }
                            })
                            Spacer(Modifier.width(8.dp))
                        }
                        InkButton(if (showSettings) "返回" else "设置", { showSettings = !showSettings })
                    }
                    HorizontalDivider(color = Ink.fg, thickness = 1.dp)
                }
            },
            bottomBar = {
                if (!showSettings && config.isSet) {
                    Column {
                        HorizontalDivider(color = Ink.fg, thickness = 1.dp)
                        Row(Modifier.fillMaxWidth().selectableGroup().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Tab.entries.forEach { item ->
                                Box(
                                    Modifier.weight(1f).defaultMinSize(minHeight = 64.dp)
                                        .semantics {
                                            role = Role.Tab
                                            selected = tab == item
                                        }
                                        .inkClickable { tabName = item.name },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        if (tab == item) "■ ${item.label}" else item.label,
                                        fontSize = 17.sp,
                                        fontWeight = if (tab == item) FontWeight.Bold else FontWeight.Normal,
                                        color = Ink.fg,
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    !config.isSet || showSettings -> SettingsScreen(
                        config = config,
                        onSave = { url, key ->
                            settings.save(url, key)
                            showSettings = false
                        },
                        onTest = vm::testConnection,
                    )
                    tab == Tab.QUOTAS -> QuotasScreen(
                        state = quotasState,
                        ignoredIds = ignoredQuotaIds,
                        onToggleIgnored = settings::setQuotaIgnored,
                        onRefresh = vm::refreshQuotas,
                        onRefreshCard = vm::refreshQuota,
                    )
                    tab == Tab.LEDGER -> LedgerScreen(
                        state = ledgerState,
                        period = ledgerPeriod,
                        failedOnly = ledgerFailedOnly,
                        agent = ledgerAgent,
                        provider = ledgerProvider,
                        page = ledgerPage,
                        onPeriod = vm::setLedgerPeriod,
                        onToggleFailed = vm::toggleFailedOnly,
                        onAgent = vm::setLedgerAgent,
                        onProvider = vm::setLedgerProvider,
                        onResetFilters = vm::resetLedgerFilters,
                        onPrevious = vm::previousLedgerPage,
                        onNext = vm::nextLedgerPage,
                        onPage = vm::setLedgerPage,
                        onRefresh = vm::refreshLedger,
                    )
                    tab == Tab.USAGE -> UsageScreen(
                        state = usageState,
                        period = usagePeriod,
                        onPeriod = vm::setUsagePeriod,
                        onRefresh = vm::refreshUsage,
                    )
                }
            }
        }
    }
}
