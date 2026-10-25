package com.jianyun.x2app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.data.UsageSummary
import kotlin.math.ceil

@Composable
fun UsageScreen(
    state: LoadState<UsageSummary>,
    period: String,
    onPeriod: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    when (state) {
        is LoadState.Loading -> LoadingBox()
        is LoadState.Failed -> ErrorBox(state.message, onRefresh)
        is LoadState.Ready -> UsageBody(state, period, onPeriod)
    }
}

@Composable
private fun UsageBody(
    state: LoadState.Ready<UsageSummary>,
    period: String,
    onPeriod: (String) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf("overview") }
    Column(Modifier.fillMaxSize()) {
        DataStatus(state)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("today" to "今天", "7d" to "7 天", "30d" to "30 天").forEach { (value, label) ->
                InkButton(label, { onPeriod(value) }, Modifier.weight(1f), selected = period == value)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InkButton("概览", { section = "overview" }, Modifier.weight(1f), selected = section == "overview")
            InkButton("分组", { section = "groups" }, Modifier.weight(1f), selected = section == "groups")
        }
        if (section == "overview") {
            UsageOverview(state.data, Modifier.weight(1f))
        } else {
            UsageGroups(state.data, Modifier.weight(1f))
        }
    }
}

@Composable
private fun UsageOverview(usage: UsageSummary, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SummaryGrid(usage)
        if (usage.series.isNotEmpty()) {
            SectionTitle("每日调用")
            DayBars(usage.series)
        } else {
            Text("所选周期没有每日调用数据", color = Ink.fgSoft, fontSize = 15.sp)
        }
    }
}

@Composable
private fun SummaryGrid(usage: UsageSummary) {
    val cells = listOf(
        Triple("调用", usage.calls.toString(), false),
        Triple("费用", "$%.2f".format(usage.cost), false),
        Triple("错误", usage.errors.toString(), usage.errors > 0),
        Triple("输入 Token", fmtNum(usage.input), false),
        Triple("输出 Token", fmtNum(usage.output), false),
        Triple("缓存读取", fmtNum(usage.cacheRead), false),
    )
    Column(Modifier.fillMaxWidth().border(1.dp, Ink.fg).padding(16.dp)) {
        cells.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value, emphasize) ->
                    SummaryCell(label, value, emphasize, Modifier.weight(1f))
                }
            }
        }
        Text(
            "重试 ${usage.retried} 次 · 未计价 ${usage.unpriced} 次",
            Modifier.padding(top = 12.dp),
            fontSize = 13.sp,
            color = Ink.fgSoft,
        )
    }
}

@Composable
private fun SummaryCell(label: String, value: String, emphasize: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (emphasize) "! $value" else value,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = Ink.fg,
            maxLines = 1,
        )
        Text(label, fontSize = 13.sp, color = Ink.fgSoft, maxLines = 1)
    }
}

@Composable
private fun DayBars(series: List<UsageSummary.DayPoint>) {
    val maxCalls = series.maxOf { it.calls }.coerceAtLeast(1)
    Column(Modifier.fillMaxWidth().border(1.dp, Ink.fg).padding(16.dp)) {
        Canvas(Modifier.fillMaxWidth().height(200.dp)) {
            val count = series.size.coerceAtLeast(1)
            val gap = 4.dp.toPx()
            val width = (size.width - gap * (count - 1)) / count
            series.forEachIndexed { index, day ->
                val height = size.height * (day.calls.toFloat() / maxCalls)
                drawRect(
                    color = Ink.fg,
                    topLeft = Offset(index * (width + gap), size.height - height),
                    size = Size(width.coerceAtLeast(1f), height),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(series.firstOrNull()?.label ?: "", fontSize = 12.sp, color = Ink.fgSoft)
            Spacer(Modifier.weight(1f))
            Text(series.lastOrNull()?.label ?: "", fontSize = 12.sp, color = Ink.fgSoft)
        }
        Text("峰值 $maxCalls 次/天", fontSize = 13.sp, color = Ink.fg)
    }
}

private data class GroupItem(val name: String, val calls: Long, val cost: Double, val errors: Long)

@Composable
private fun UsageGroups(usage: UsageSummary, modifier: Modifier = Modifier) {
    var group by rememberSaveable { mutableStateOf("agents") }
    var page by rememberSaveable(group) { mutableStateOf(0) }
    val items = when (group) {
        "models" -> usage.models.sortedByDescending { it.cost }.map {
            GroupItem((it.model ?: it.id) + (it.host?.let { host -> " @$host" } ?: ""), it.calls, it.cost, it.errors)
        }
        "accounts" -> usage.accounts.map { GroupItem(it.name ?: it.id, it.calls, it.cost, it.errors) }
        else -> usage.agents.map { GroupItem(it.name ?: it.id, it.calls, it.cost, it.errors) }
    }
    val perPage = 8
    val pageCount = ceil(items.size.toDouble() / perPage).toInt().coerceAtLeast(1)
    val safePage = page.coerceIn(0, pageCount - 1)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("agents" to "Agent", "models" to "模型", "accounts" to "账号").forEach { (value, label) ->
                InkButton(label, { group = value }, Modifier.weight(1f), selected = group == value)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("此分组暂无数据", color = Ink.fgSoft, fontSize = 16.sp)
                }
            } else {
                items.drop(safePage * perPage).take(perPage).forEach { item -> GroupRow(item) }
            }
        }
        PageControls(safePage, pageCount, { page = it })
    }
}

@Composable
private fun GroupRow(item: GroupItem) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, Ink.line).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.name,
            fontSize = 16.sp,
            color = Ink.fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 16.dp),
        )
        Column(horizontalAlignment = Alignment.End) {
            Text("${item.calls} 次 · $%.2f".format(item.cost), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            if (item.errors > 0) Text("! 错误 ${item.errors}", fontSize = 13.sp, color = Ink.fg)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
}
