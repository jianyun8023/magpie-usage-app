package com.jianyun.x2app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    provider: String?,
    page: Int,
    onPeriod: (String) -> Unit,
    onToggleFailed: () -> Unit,
    onAgent: (String?) -> Unit,
    onProvider: (String?) -> Unit,
    onPrevious: () -> Unit,
    onNext: (Int) -> Unit,
    onRefresh: () -> Unit,
    onResetFilters: () -> Unit,
    onPage: ((Int, Int) -> Unit)? = null,
) {
    var pendingPicker by rememberSaveable { mutableStateOf<String?>(null) }
    if (pendingPicker != null) {
        val choosingAgent = pendingPicker == "agent"
        val selected = if (choosingAgent) agent else provider
        val data = (state as? LoadState.Ready)?.data
        val choices = if (choosingAgent) data?.agents else data?.providers
        FacetPicker(
            if (choosingAgent) "Agent" else "上游",
            choices ?: listOfNotNull(selected?.let { Ledger.Facet(it) }),
            selected,
            { if (choosingAgent) onAgent(it) else onProvider(it); pendingPicker = null },
            { pendingPicker = null },
        )
        return
    }
    when (state) {
        is LoadState.Ready -> LedgerBody(
            state, period, failedOnly, agent, provider, page,
            onPeriod, onToggleFailed, onAgent, onProvider, onPrevious, onNext, onResetFilters, onPage,
        )
        else -> Column(Modifier.fillMaxSize()) {
            DataStatus(state)
            FilterBar(
                period, failedOnly, agent ?: "全部", provider ?: "全部", onPeriod, onToggleFailed,
                { pendingPicker = "agent" }, { pendingPicker = "provider" },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state is LoadState.Failed) ErrorBox(state.message, onRefresh) else LoadingBox()
            }
            InkButton("重置筛选", onResetFilters, Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}

@Composable
private fun LedgerBody(
    state: LoadState.Ready<Ledger>,
    period: String,
    failedOnly: Boolean,
    agent: String?,
    provider: String?,
    page: Int,
    onPeriod: (String) -> Unit,
    onToggleFailed: () -> Unit,
    onAgent: (String?) -> Unit,
    onProvider: (String?) -> Unit,
    onPrevious: () -> Unit,
    onNext: (Int) -> Unit,
    onResetFilters: () -> Unit,
    onPage: ((Int, Int) -> Unit)?,
) {
    var showAgents by rememberSaveable { mutableStateOf(false) }
    var showProviders by rememberSaveable { mutableStateOf(false) }
    var detail by remember(state.data, period, failedOnly, agent, provider, page) { mutableStateOf<RequestRow?>(null) }
    val listScroll = key(period, failedOnly, agent, provider, page) { rememberScrollState() }
    detail?.let { row ->
        RequestDetails(row) { detail = null }
        return
    }
    if (showAgents) {
        FacetPicker("Agent", state.data.agents, agent, { onAgent(it); showAgents = false }, { showAgents = false })
        return
    }
    if (showProviders) {
        FacetPicker("上游", state.data.providers, provider, { onProvider(it); showProviders = false }, { showProviders = false })
        return
    }
    val ledger = state.data
    val pageCount = ceil(ledger.total.toDouble() / AppViewModel.LEDGER_PAGE_SIZE).toInt().coerceAtLeast(1)
    val safePage = page.coerceIn(0, pageCount - 1)
    val first = if (ledger.rows.isEmpty()) 0 else safePage * AppViewModel.LEDGER_PAGE_SIZE + 1
    val last = if (ledger.rows.isEmpty()) 0 else safePage * AppViewModel.LEDGER_PAGE_SIZE + ledger.rows.size
    val selectedAgent = ledger.agents.find { it.id == agent }?.let { it.name.ifBlank { it.id } } ?: agent ?: "全部"
    val selectedProvider = ledger.providers.find { it.id == provider }?.let { it.name.ifBlank { it.id } } ?: provider ?: "全部"
    Column(Modifier.fillMaxSize()) {
        DataStatus(state)
        FilterBar(period, failedOnly, selectedAgent, selectedProvider, onPeriod, onToggleFailed,
            { showAgents = true }, { showProviders = true })
        Text(
            "$first–$last / 共 ${ledger.total} 条",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            fontSize = 13.sp,
            color = Ink.fgSoft,
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(listScroll).padding(horizontal = 16.dp),
        ) {
            if (ledger.rows.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("当前筛选没有请求记录", color = Ink.fgSoft, fontSize = 16.sp)
                    Spacer(Modifier.height(12.dp))
                    InkButton("重置筛选", onResetFilters)
                }
            } else {
                ledger.rows.forEach { row ->
                    RequestRowItem(row, { detail = row })
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        LedgerPages(safePage, pageCount, ledger.total, onPrevious, onNext, onPage)
    }
}

internal fun ledgerPageInput(value: String, pageCount: Int): Int? =
    value.trim().toIntOrNull()?.takeIf { it in 1..pageCount }?.minus(1)

@Composable
private fun LedgerPages(
    page: Int,
    pageCount: Int,
    total: Int,
    onPrevious: () -> Unit,
    onNext: (Int) -> Unit,
    onPage: ((Int, Int) -> Unit)?,
) {
    var jumping by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        InkButton("上一页", onPrevious, enabled = page > 0)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (onPage == null) {
                Text("${page + 1} / $pageCount 页", fontWeight = FontWeight.Bold)
            } else {
                InkButton("${page + 1} / $pageCount 页", { input = (page + 1).toString(); jumping = true })
            }
        }
        InkButton("下一页", { onNext(total) }, enabled = page < pageCount - 1)
    }
    if (jumping && onPage != null) {
        val target = ledgerPageInput(input, pageCount)
        AlertDialog(
            onDismissRequest = { jumping = false },
            title = { Text("跳转到页") },
            text = {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("页码（1–$pageCount）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = input.isNotEmpty() && target == null,
                )
            },
            confirmButton = { InkButton("跳转", { target?.let { onPage(it, total) }; jumping = false }, enabled = target != null) },
            dismissButton = { InkButton("取消", { jumping = false }) },
            containerColor = Ink.bg,
        )
    }
}

@Composable
private fun FilterBar(
    period: String,
    failedOnly: Boolean,
    selectedAgent: String,
    selectedProvider: String,
    onPeriod: (String) -> Unit,
    onToggleFailed: () -> Unit,
    onChooseAgent: () -> Unit,
    onChooseProvider: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("today" to "今天", "7d" to "7 天", "30d" to "30 天").forEach { (value, label) ->
                InkButton(label, { onPeriod(value) }, Modifier.weight(1f), selected = period == value)
            }
            InkButton(if (failedOnly) "■ 仅失败" else "仅失败", onToggleFailed, Modifier.weight(1f), selected = failedOnly)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InkButton("Agent：$selectedAgent", onChooseAgent, Modifier.weight(1f))
            InkButton("上游：$selectedProvider", onChooseProvider, Modifier.weight(1f))
        }
    }
}

@Composable
private fun FacetPicker(
    title: String,
    facets: List<Ledger.Facet>,
    selected: String?,
    onSelect: (String?) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    var page by rememberSaveable { mutableStateOf(0) }
    val all = listOf<Ledger.Facet?>(null) + facets
    val perPage = 10
    val pageCount = ceil(all.size.toDouble() / perPage).toInt().coerceAtLeast(1)
    val safePage = page.coerceIn(0, pageCount - 1)
    val scroll = key(safePage) { rememberScrollState() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("选择$title", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            InkButton("返回流水", onClose)
        }
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(horizontal = 16.dp)) {
            all.drop(safePage * perPage).take(perPage).forEach { facet ->
                val id = facet?.id
                val label = facet?.let { it.name.ifBlank { it.id } } ?: "全部$title"
                InkButton(
                    if (selected == id) "■ $label" else label,
                    { onSelect(id) },
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    selected = selected == id,
                )
            }
        }
        PageControls(safePage, pageCount, { page = it })
    }
}

@Composable
private fun RequestDetails(row: RequestRow, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("请求详情", Modifier.weight(1f), fontSize = 20.sp, fontWeight = FontWeight.Bold)
            InkButton("返回流水", onClose)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            SelectionContainer {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    requestDetailSections(row).forEach { (title, fields) ->
                        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                        detailFieldRows(fields, wide).forEach { cells ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                cells.forEach { (label, value) ->
                                    Column(Modifier.weight(1f)) {
                                        Text(label, fontSize = 13.sp, color = Ink.fgSoft)
                                        Text(value, fontSize = 16.sp, color = Ink.fg)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

internal fun requestDetails(row: RequestRow): List<Pair<String, String>> = listOf(
    "时间" to row.t,
    "结果" to requestStatusText(row),
    "HTTP 状态" to row.status.takeIf { it > 0 }?.toString(),
    "错误" to row.err,
    "路由 ID" to row.routeId.toString(),
    "Agent" to row.agentName,
    "Agent ID" to row.agent,
    "上游" to row.providerName,
    "上游 ID" to row.provider,
    "上游账号" to row.providerAccount,
    "主机" to row.host,
    "模型" to row.model,
    "请求模型" to row.req,
    "实际服务模型" to row.served,
    "输入 Token" to row.`in`.toString(),
    "输出 Token" to row.out.toString(),
    "缓存读取 Token" to row.cacheRead.toString(),
    "缓存写入 Token" to row.cacheWrite.toString(),
    "推理 Token" to row.reasoning?.toString(),
    "推理强度" to row.effort,
    "总耗时" to "${row.ms} ms",
    "首 Token 耗时（TTFT）" to row.ttftMs?.let { "$it ms" },
    "费用" to row.cost?.let { "$$it" },
    "计价状态" to when (row.priced) {
        true -> "已计价"
        false -> "未计价"
        null -> "未知"
    },
    "端点" to row.ep,
    "会话" to row.session,
    "访问类型" to row.access,
    "记录来源" to when (row.source) {
        "log" -> "本地会话"
        "reported" -> "客户端上报"
        null, "" -> "网关"
        else -> row.source
    },
    "尝试次数" to row.tries?.toString(),
    "已转交重试" to if (row.handedOn) "是" else "否",
).map { (label, value) -> label to (value?.takeIf { it.isNotBlank() } ?: "—") }

internal fun requestDetailSections(row: RequestRow): List<Pair<String, List<Pair<String, String>>>> {
    val fields = requestDetails(row).toMap()
    return listOf(
        "概况" to listOf("结果", "时间", "Agent", "上游", "模型", "上游账号", "请求模型", "实际服务模型", "总耗时", "首 Token 耗时（TTFT）"),
        "Token 与费用" to listOf("费用", "计价状态", "输入 Token", "输出 Token", "缓存读取 Token", "缓存写入 Token", "推理 Token", "推理强度"),
        "诊断" to listOf("错误", "HTTP 状态", "记录来源", "尝试次数", "已转交重试", "主机", "端点", "路由 ID", "Agent ID", "上游 ID", "会话", "访问类型"),
    ).map { (title, labels) -> title to labels.map { it to fields.getValue(it) } }
}

internal fun detailFieldRows(fields: List<Pair<String, String>>, wide: Boolean): List<List<Pair<String, String>>> {
    fun fullWidth(field: Pair<String, String>) = field.first == "错误" || field.second.length > 48 || '\n' in field.second
    return buildList {
        var index = 0
        while (index < fields.size) {
            val count = if (wide && index + 1 < fields.size && !fullWidth(fields[index]) && !fullWidth(fields[index + 1])) 2 else 1
            add(fields.subList(index, index + count))
            index += count
        }
    }
}

internal fun requestStatusText(row: RequestRow): String {
    val status = when {
        row.status == 499 -> "已取消 · HTTP 499"
        row.failed -> if (row.status > 0) "失败 · HTTP ${row.status}" else "失败"
        row.ok -> "成功"
        row.status > 0 -> "HTTP ${row.status}"
        else -> "状态未记录"
    }
    return status + if (row.handedOn) " · 已转交重试" else ""
}

@Composable
fun RequestRowItem(row: RequestRow, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().border(if (row.failed && row.status != 499) 2.dp else 1.dp, Ink.fg)
            .inkClickable(enabled = onClick != null, onClick = { onClick?.invoke() })
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${row.agentName?.ifBlank { null } ?: row.agent} · ${fmtTime(row.t)}",
                Modifier.weight(1f).padding(end = 8.dp), fontSize = 14.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                requestStatusText(row),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Ink.fg,
            )
        }
        Text(
            "${row.providerName ?: row.provider} · ${row.model}",
            Modifier.padding(top = 4.dp),
            fontSize = 14.sp,
            color = Ink.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(Modifier.padding(top = 4.dp)) {
            Text(
                "入 ${fmtNum(row.`in`)} · 出 ${fmtNum(row.out)}" +
                    if (row.cacheRead > 0) " · 缓存 ${fmtNum(row.cacheRead)}" else "",
                fontSize = 13.sp,
                color = Ink.fgSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            val cost = when {
                row.priced == false -> "未计价"
                row.cost != null -> "$%.4f".format(row.cost)
                else -> ""
            }
            Text(listOf(cost, fmtMs(row.ms)).filter { it.isNotEmpty() }.joinToString(" · "), fontSize = 13.sp, color = Ink.fgSoft)
        }
        row.err?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Ink.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun fmtNum(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
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
