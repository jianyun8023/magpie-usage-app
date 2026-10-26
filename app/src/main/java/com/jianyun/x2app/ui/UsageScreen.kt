package com.jianyun.x2app.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jianyun.x2app.data.UsageSummary
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.MonthDay
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

@Composable
fun UsageScreen(
    state: LoadState<UsageSummary>,
    period: String,
    onPeriod: (String) -> Unit,
    onRefresh: () -> Unit,
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (state) {
                is LoadState.Loading -> LoadingBox()
                is LoadState.Failed -> ErrorBox(state.message, onRefresh)
                is LoadState.Ready -> if (section == "overview") {
                    UsageOverview(state.data, Modifier.fillMaxSize())
                } else {
                    UsageGroups(state.data, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun UsageOverview(usage: UsageSummary, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        // 横屏（宽大于高）时左栏指标+图表、右栏明细；竖屏单列
        val twoPane = maxWidth >= 600.dp && maxWidth > maxHeight
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (twoPane) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        Modifier.weight(1.2f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        SummaryGrid(usage)
                        if (usage.series.isNotEmpty()) {
                            SectionTitle(if (usage.period == "today") "每小时调用" else "每日调用")
                            DayBars(usage.series, usage.period)
                        }
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (usage.series.isNotEmpty()) {
                            DailyDetails(usage)
                        }
                    }
                }
            } else {
                SummaryGrid(usage)
                if (usage.series.isNotEmpty()) {
                    SectionTitle(if (usage.period == "today") "每小时调用" else "每日调用")
                    DayBars(usage.series, usage.period)
                    DailyDetails(usage)
                } else {
                    Text("所选周期没有调用数据", color = Ink.fgSoft, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun SummaryGrid(usage: UsageSummary, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().border(1.dp, Ink.fg).padding(16.dp)) {
        MetricGrid(overviewMetrics(usage))
        Text(
            "重试 ${usage.retried} 次 · 未计价 ${usage.unpriced} 次",
            Modifier.padding(top = 12.dp),
            fontSize = 13.sp,
            color = Ink.fgSoft,
        )
    }
}

internal data class UsageMetric(val label: String, val value: String, val emphasize: Boolean = false)

internal fun formatUsageCost(value: Double): String =
    "$" + if (value != 0.0 && kotlin.math.abs(value) < 0.01) {
        BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    } else String.format(Locale.ROOT, "%.2f", value)

internal fun overviewMetrics(usage: UsageSummary): List<UsageMetric> = listOf(
    UsageMetric("调用", usage.calls.toString()),
    UsageMetric("费用", formatUsageCost(usage.cost)),
    UsageMetric("错误", usage.errors.toString(), usage.errors > 0),
    UsageMetric("总计 Token", fmtNum(usage.input + usage.output + usage.cacheRead + usage.cacheWrite)),
    UsageMetric("输入 Token", fmtNum(usage.input)),
    UsageMetric("输出 Token", fmtNum(usage.output)),
    UsageMetric("缓存读取 Token", fmtNum(usage.cacheRead)),
    UsageMetric("缓存写入 Token", fmtNum(usage.cacheWrite)),
    UsageMetric("推理 Token", fmtNum(usage.reasoning)),
)

@Composable
private fun MetricGrid(metrics: List<UsageMetric>) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth / fontScale >= 620.dp -> 4
            maxWidth / fontScale >= 420.dp -> 3
            maxWidth / fontScale >= 240.dp -> 2
            else -> 1
        }
        Column {
            metrics.chunked(columns).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { metric ->
                        SummaryCell(metric.label, metric.value, metric.emphasize, Modifier.weight(1f))
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun SummaryCell(label: String, value: String, emphasize: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 8.dp)) {
        Text(
            if (emphasize) "! $value" else value,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Ink.fg,
        )
        Text(label, fontSize = 13.sp, color = Ink.fgSoft)
    }
}

internal fun dailyPeakCalls(series: List<UsageSummary.DayPoint>): Long =
    series.maxOfOrNull { it.calls } ?: 0L

internal fun dailyCallFraction(calls: Long, peakCalls: Long): Float =
    calls.toFloat() / peakCalls.coerceAtLeast(1)

private const val DAILY_DETAILS_PAGE_SIZE = 7

internal fun dailyDetailsPageCount(series: List<UsageSummary.DayPoint>): Int =
    (series.size - 1).coerceAtLeast(0) / DAILY_DETAILS_PAGE_SIZE + 1

internal fun detailSeries(
    series: List<UsageSummary.DayPoint>,
    period: String,
    now: Instant = Instant.now(),
): List<UsageSummary.DayPoint> = if (period != "today") series else series.filterNot { day ->
    // Without an explicit offset, the bucket cannot safely be compared with the current instant.
    val start = runCatching { OffsetDateTime.parse(day.time).toInstant() }.getOrNull()
    start != null && start.isAfter(now) && day.calls == 0L && day.errors == 0L &&
        day.input == 0L && day.output == 0L && day.cost == 0.0
}

internal fun dailyDetailsPage(series: List<UsageSummary.DayPoint>, page: Int): List<UsageSummary.DayPoint> {
    val lastPage = dailyDetailsPageCount(series) - 1
    return chronologicalDays(series).asReversed()
        .drop(page.coerceIn(0, lastPage) * DAILY_DETAILS_PAGE_SIZE).take(DAILY_DETAILS_PAGE_SIZE)
}

private fun localBucketTime(time: String): LocalDateTime? =
    runCatching { OffsetDateTime.parse(time).toLocalDateTime() }.getOrNull()
        ?: runCatching { LocalDateTime.parse(time) }.getOrNull()
        ?: runCatching { LocalDate.parse(time).atStartOfDay() }.getOrNull()

internal fun chronologicalDays(series: List<UsageSummary.DayPoint>): List<UsageSummary.DayPoint> {
    val dated = series.map { day ->
        day to (runCatching { OffsetDateTime.parse(day.time).toInstant() }.getOrNull()
            ?: localBucketTime(day.time)?.toInstant(ZoneOffset.UTC))
    }
    // Older responses omit timestamps; their server-provided bucket order is chronological.
    return if (dated.all { it.second != null }) dated.sortedBy { it.second }.map { it.first } else series
}

internal fun dailyDetailDate(day: UsageSummary.DayPoint, period: String = ""): String {
    val time = localBucketTime(day.time)
    if (period == "today") {
        val hour = time?.hour ?: day.label.toIntOrNull()?.takeIf { it in 0..23 }
        if (hour != null) return "${hour}时"
    }
    if (time != null) return "${time.monthValue}月${time.dayOfMonth}日"
    val monthDay = listOf("MMM d", "M-d").firstNotNullOfOrNull { pattern ->
        runCatching { MonthDay.parse(day.label, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)) }.getOrNull()
    }
    return monthDay?.let { "${it.monthValue}月${it.dayOfMonth}日" }
        ?: day.label.ifBlank { day.time.ifBlank { "日期未知" } }
}

internal fun dailyMetrics(day: UsageSummary.DayPoint): List<UsageMetric> = listOf(
    UsageMetric("调用", day.calls.toString()),
    UsageMetric("费用", formatUsageCost(day.cost)),
    UsageMetric("错误", day.errors.toString(), day.errors > 0),
    UsageMetric("输入 Token", fmtNum(day.input)),
    UsageMetric("输出 Token", fmtNum(day.output)),
)

@Composable
private fun DailyDetails(usage: UsageSummary, modifier: Modifier = Modifier) {
    val series = detailSeries(usage.series, usage.period)
    var expanded by rememberSaveable(usage.period) { mutableStateOf(usage.series.size > 7) }
    var page by rememberSaveable(usage.period, usage.since, series.size) { mutableStateOf(0) }
    val pageCount = dailyDetailsPageCount(series)
    val safePage = page.coerceIn(0, pageCount - 1)
    val title = if (usage.period == "today") "每小时明细" else "每日明细"
    Column(modifier.fillMaxWidth()) {
        InkButton(
            if (expanded) "收起$title" else "查看$title",
            { expanded = !expanded },
            Modifier.fillMaxWidth(),
        )
        if (expanded) {
            Column(Modifier.fillMaxWidth().border(1.dp, Ink.line).padding(12.dp)) {
                dailyDetailsPage(series, safePage).forEach { day ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        SectionTitle(dailyDetailDate(day, usage.period))
                        MetricGrid(dailyMetrics(day))
                    }
                }
            }
            if (pageCount > 1) PageControls(safePage, pageCount, { page = it })
        }
    }
}

@Composable
private fun DayBars(series: List<UsageSummary.DayPoint>, period: String) {
    val days = chronologicalDays(series)
    val maxCalls = dailyPeakCalls(days)
    Column(Modifier.fillMaxWidth().border(1.dp, Ink.fg).padding(16.dp)) {
        if (days.size <= 7) {
            days.forEach { day ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(dailyDetailDate(day, period), Modifier.weight(1f), color = Ink.fg, fontSize = 14.sp)
                    Text("${day.calls} 次", Modifier.weight(1f), textAlign = TextAlign.End,
                        color = Ink.fg, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Canvas(Modifier.fillMaxWidth().height(8.dp)) {
                    drawRect(Ink.fg, size = Size(size.width * dailyCallFraction(day.calls, maxCalls), size.height))
                }
            }
        } else {
        Canvas(Modifier.fillMaxWidth().height(120.dp)) {
            val count = days.size
            val gap = minOf(4.dp.toPx(), size.width / (count * 3))
            val width = (size.width - gap * (count - 1)) / count
            days.forEachIndexed { index, day ->
                val height = size.height * dailyCallFraction(day.calls, maxCalls)
                drawRect(
                    color = Ink.fg,
                    topLeft = Offset(index * (width + gap), size.height - height),
                    size = Size(width.coerceAtLeast(1f), height),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(dailyDetailDate(days.first(), period), fontSize = 12.sp, color = Ink.fgSoft)
            Spacer(Modifier.weight(1f))
            Text(dailyDetailDate(days.last(), period), fontSize = 12.sp, color = Ink.fgSoft)
        }
        }
        val unit = if (period == "today") "小时" else "天"
        Text("峰值 $maxCalls 次/$unit", Modifier.padding(top = 8.dp), fontSize = 13.sp, color = Ink.fg)
    }
}

internal fun groupTitle(item: UsageSummary.UsageGroup): String =
    listOf(item.model, item.name, item.id).firstOrNull { !it.isNullOrBlank() } ?: "未命名"

internal fun groupDescription(item: UsageSummary.UsageGroup): String {
    val title = groupTitle(item).trim()
    val sub = item.sub?.trim()?.takeIf { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
    if (sub != null) return sub
    val fields = if (!item.model.isNullOrBlank()) listOf(item.provider, item.host) else listOf(item.provider)
    return fields.filterNotNull().map { it.trim() }
        .filter { it.isNotEmpty() && !it.equals(title, ignoreCase = true) }
        .distinctBy { it.lowercase(Locale.ROOT) }.joinToString(" · ")
}

internal fun groupMetrics(item: UsageSummary.UsageGroup): List<UsageMetric> = listOf(
    UsageMetric("调用", item.calls.toString()),
    UsageMetric("费用", formatUsageCost(item.cost)),
    UsageMetric("错误", item.errors.toString(), item.errors > 0),
    UsageMetric("总计 Token", fmtNum(item.input + item.output + item.cacheRead + item.cacheWrite)),
    UsageMetric("输入 Token", fmtNum(item.input)),
    UsageMetric("输出 Token", fmtNum(item.output)),
    UsageMetric("缓存读取 Token", fmtNum(item.cacheRead)),
    UsageMetric("缓存写入 Token", fmtNum(item.cacheWrite)),
)

@Composable
private fun UsageGroups(usage: UsageSummary, modifier: Modifier = Modifier) {
    var group by rememberSaveable { mutableStateOf("agents") }
    var page by rememberSaveable(group, usage.period, usage.since) { mutableStateOf(0) }
    val items = when (group) {
        "models" -> usage.models.sortedByDescending { it.cost }
        "accounts" -> usage.accounts
        else -> usage.agents
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val landscape = maxWidth > maxHeight
        val perPage = when {
            landscape && maxWidth >= 840.dp -> 8
            landscape -> 6
            else -> 4
        }
        val pageCount = ceil(items.size.toDouble() / perPage).toInt().coerceAtLeast(1)
        val safePage = page.coerceIn(0, pageCount - 1)
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf("agents" to "Agent", "models" to "模型", "accounts" to "账号").forEach { (value, label) ->
                    InkButton(label, { group = value }, Modifier.weight(1f), selected = group == value)
                }
            }
            key(group, usage.period, usage.since, safePage) { Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("此分组暂无数据", color = Ink.fgSoft, fontSize = 16.sp)
                    }
                } else {
                    items.drop(safePage * perPage).take(perPage).forEach { item -> GroupRow(item) }
                }
            } }
            PageControls(safePage, pageCount, { page = it })
        }
    }
}


@Composable
private fun GroupRow(item: UsageSummary.UsageGroup) {
    Column(
        Modifier.fillMaxWidth().border(1.dp, Ink.line).padding(16.dp),
    ) {
        Text(
            groupTitle(item),
            fontSize = 16.sp,
            color = Ink.fg,
            fontWeight = FontWeight.Bold,
        )
        val description = groupDescription(item)
        if (description.isNotBlank()) {
            Text(description, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = Ink.fgSoft)
        }
        MetricGrid(groupMetrics(item))
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.fg)
}
