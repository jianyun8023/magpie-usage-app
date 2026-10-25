package com.jianyun.x2app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.data.Ledger
import com.jianyun.x2app.data.RequestRow
import kotlin.math.ceil

@Composable
fun LedgerScreen(
    state: LoadState<Ledger>,
    period: String,
    failedOnly: Boolean,
    agent: String?,
    page: Int,
    onPeriod: (String) -> Unit,
    onToggleFailed: () -> Unit,
    onAgent: (String?) -> Unit,
    onPrevious: () -> Unit,
    onNext: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    when (state) {
        is LoadState.Loading -> LoadingBox()
        is LoadState.Failed -> ErrorBox(state.message, onRefresh)
        is LoadState.Ready -> LedgerBody(
            state, period, failedOnly, agent, page,
            onPeriod, onToggleFailed, onAgent, onPrevious, onNext,
        )
    }
}

@Composable
private fun LedgerBody(
    state: LoadState.Ready<Ledger>,
    period: String,
    failedOnly: Boolean,
    agent: String?,
    page: Int,
    onPeriod: (String) -> Unit,
    onToggleFailed: () -> Unit,
    onAgent: (String?) -> Unit,
    onPrevious: () -> Unit,
    onNext: (Int) -> Unit,
) {
    var showAgents by rememberSaveable { mutableStateOf(false) }
    if (showAgents) {
        AgentPicker(state.data.agents, agent, { onAgent(it); showAgents = false }, { showAgents = false })
        return
    }
    val ledger = state.data
    val pageCount = ceil(ledger.total.toDouble() / AppViewModel.LEDGER_PAGE_SIZE).toInt().coerceAtLeast(1)
    val safePage = page.coerceIn(0, pageCount - 1)
    val first = if (ledger.total == 0) 0 else safePage * AppViewModel.LEDGER_PAGE_SIZE + 1
    val last = safePage * AppViewModel.LEDGER_PAGE_SIZE + ledger.rows.size
    val selectedAgent = ledger.agents.find { it.id == agent }?.let { it.name.ifBlank { it.id } } ?: "全部"
    Column(Modifier.fillMaxSize()) {
        DataStatus(state)
        FilterBar(period, failedOnly, selectedAgent, onPeriod, onToggleFailed, { showAgents = true })
        Text(
            "$first–$last / 共 ${ledger.total} 条",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 13.sp,
            color = Ink.fgSoft,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            if (ledger.rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("当前筛选没有请求记录", color = Ink.fgSoft, fontSize = 16.sp)
                }
            } else {
                ledger.rows.forEach { row ->
                    RequestRowItem(row)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        PageControls(safePage, pageCount, { target -> if (target > page) onNext(ledger.total) else onPrevious() })
    }
}

@Composable
private fun FilterBar(
    period: String,
    failedOnly: Boolean,
    selectedAgent: String,
    onPeriod: (String) -> Unit,
    onToggleFailed: () -> Unit,
    onChooseAgent: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("today" to "今天", "7d" to "7 天", "30d" to "30 天").forEach { (value, label) ->
                InkButton(label, { onPeriod(value) }, Modifier.weight(1f), selected = period == value)
            }
            InkButton(if (failedOnly) "■ 仅失败" else "仅失败", onToggleFailed, Modifier.weight(1f), selected = failedOnly)
        }
        Spacer(Modifier.height(8.dp))
        InkButton("Agent：$selectedAgent", onChooseAgent, Modifier.fillMaxWidth())
    }
}

@Composable
private fun AgentPicker(
    agents: List<Ledger.Facet>,
    selected: String?,
    onAgent: (String?) -> Unit,
    onClose: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(0) }
    val all = listOf<Ledger.Facet?>(null) + agents
    val perPage = 10
    val pageCount = ceil(all.size.toDouble() / perPage).toInt().coerceAtLeast(1)
    val safePage = page.coerceIn(0, pageCount - 1)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选择 Agent", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            InkButton("返回请求", onClose)
        }
        Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            all.drop(safePage * perPage).take(perPage).forEach { facet ->
                val id = facet?.id
                val label = facet?.let { it.name.ifBlank { it.id } } ?: "全部 Agent"
                InkButton(
                    if (selected == id) "■ $label" else label,
                    { onAgent(id) },
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    selected = selected == id,
                )
            }
        }
        PageControls(safePage, pageCount, { page = it })
    }
}

@Composable
fun RequestRowItem(row: RequestRow) {
    Column(
        Modifier.fillMaxWidth().border(if (row.ok) 1.dp else 2.dp, Ink.fg).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(fmtTime(row.t), fontSize = 13.sp, color = Ink.fgSoft)
            Spacer(Modifier.weight(1f))
            Text(
                if (row.ok) "成功" else "! 失败 HTTP ${row.status}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.fg,
            )
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.agentName ?: row.agent,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            row.cost?.takeIf { it > 0 }?.let {
                Text("$%.4f".format(it), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(
            "${row.providerName ?: row.provider} · ${row.model}",
            Modifier.padding(top = 6.dp),
            fontSize = 14.sp,
            color = Ink.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 6.dp)) {
            Text(
                "入 ${fmtNum(row.`in`)} · 出 ${fmtNum(row.out)}" +
                    if (row.cacheRead > 0) " · 缓存 ${fmtNum(row.cacheRead)}" else "",
                fontSize = 13.sp,
                color = Ink.fgSoft,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(fmtMs(row.ms), fontSize = 13.sp, color = Ink.fgSoft)
        }
        row.err?.let {
            Text("! $it", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Ink.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun fmtNum(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

fun fmtMs(ms: Long): String = when {
    ms >= 60_000 -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
    ms >= 1000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms}ms"
}

fun fmtTime(iso: String): String {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    val zone = java.time.ZoneId.systemDefault()
    val parsed = runCatching { java.time.Instant.parse(iso).atZone(zone) }
        .recoverCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone) }
        .recoverCatching { java.time.LocalDateTime.parse(iso).atZone(zone) }
        .getOrNull() ?: return iso.take(19).replace("T", " ")
    return formatter.format(parsed)
}
