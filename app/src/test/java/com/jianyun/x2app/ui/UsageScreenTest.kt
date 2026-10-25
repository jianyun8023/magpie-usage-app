package com.jianyun.x2app.ui

import com.jianyun.x2app.data.UsageSummary
import java.time.Instant
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageScreenTest {
    @Test
    fun dailyPagesPreserveEveryDateAndExactCallCount() {
        val series = List(30) { index ->
            UsageSummary.DayPoint(label = "10-${index + 1}", calls = if (index == 0) 0L else 1_234_567L + index)
        }
        val pages = (0..4).map { dailyDetailsPage(series, it) }
        assertEquals(listOf(7, 7, 7, 7, 2), pages.map { it.size })
        assertEquals(series.reversed(), pages.flatten())
    }

    @Test
    fun dailyPagesHandleEmptySingleDayAndShrinkingSeries() {
        assertEquals(emptyList<UsageSummary.DayPoint>(), dailyDetailsPage(emptyList(), 4))
        val singleDay = listOf(UsageSummary.DayPoint(label = "10-25", calls = 0))
        assertEquals(singleDay, dailyDetailsPage(singleDay, 4))
        assertEquals(singleDay, dailyDetailsPage(singleDay, -1))
        val eightDays = List(8) { UsageSummary.DayPoint(label = "day $it") }
        assertEquals(eightDays.take(1), dailyDetailsPage(eightDays, Int.MAX_VALUE))
    }

    @Test
    fun dailyDateUsesChineseServerDateWithoutTimezoneShift() {
        assertEquals("10月25日", dailyDetailDate(UsageSummary.DayPoint(label = "Oct 25", time = "2026-10-25T00:00:00+08:00")))
        assertEquals("10月25日", dailyDetailDate(UsageSummary.DayPoint(label = " ", time = "2026-10-25")))
        assertEquals("10月25日", dailyDetailDate(UsageSummary.DayPoint(label = "Oct 25")))
        assertEquals("10月25日", dailyDetailDate(UsageSummary.DayPoint(label = "10-25")))
        assertEquals("0时", dailyDetailDate(UsageSummary.DayPoint(time = "2026-10-25T00:00:00+08:00"), "today"))
        assertEquals("14时", dailyDetailDate(UsageSummary.DayPoint(label = "14"), "today"))
        assertEquals("日期未知", dailyDetailDate(UsageSummary.DayPoint()))
    }

    @Test
    fun chartAscendingAndDetailsDescendingAcrossYearBoundary() {
        val oldest = UsageSummary.DayPoint(time = "2025-12-31T00:00:00+08:00", calls = 0)
        val middle = UsageSummary.DayPoint(time = "2026-01-01T00:00:00+08:00", calls = 10)
        val newest = UsageSummary.DayPoint(time = "2026-01-02T00:00:00+08:00", calls = 20)
        val shuffled = listOf(middle, newest, oldest)
        assertEquals(listOf(oldest, middle, newest), chronologicalDays(shuffled))
        assertEquals(listOf(newest, middle, oldest), dailyDetailsPage(shuffled, 0))
    }

    @Test
    fun dailyMetricsIncludeAllAvailableFields() {
        val day = UsageSummary.DayPoint(calls = 1_234_567, errors = 2, input = 300, output = 40, cost = 0.001)
        assertEquals(listOf(
            UsageMetric("调用", "1234567"), UsageMetric("费用", "$0.001"),
            UsageMetric("错误", "2", true), UsageMetric("输入 Token", "300"),
            UsageMetric("输出 Token", "40"),
        ), dailyMetrics(day))
    }

    @Test
    fun overviewAndGroupsUseSharedTokenFormattingAndKeepZeroMetrics() {
        val usage = UsageSummary(input = 1_454_300_000, cacheWrite = 123, reasoning = 45)
        val totals = overviewMetrics(usage).associate { it.label to it.value }
        assertEquals(9, totals.size)
        assertEquals(fmtNum(usage.input), totals["输入 Token"])
        assertEquals("123", totals["缓存写入 Token"])
        assertEquals("45", totals["推理 Token"])
        val group = UsageSummary.UsageGroup(input = usage.input, cacheRead = 200)
        val metrics = groupMetrics(group).associate { it.label to it.value }
        assertEquals(8, metrics.size)
        assertEquals(fmtNum(group.input), metrics["输入 Token"])
        assertEquals("200", metrics["缓存读取 Token"])
        assertEquals("0", metrics["缓存写入 Token"])
        assertEquals("0", metrics["错误"])
        assertEquals("$0.00", metrics["费用"])
    }

    @Test
    fun totalTokensIncludeCacheReadAndWriteWithoutDoubleCountingReasoning() {
        val usage = UsageSummary(input = 120, output = 30, cacheRead = 400, cacheWrite = 50, reasoning = 20)
        assertEquals("600", overviewMetrics(usage).single { it.label == "总计 Token" }.value)
        val group = UsageSummary.UsageGroup(input = 120, output = 30, cacheRead = 400, cacheWrite = 50)
        assertEquals("600", groupMetrics(group).single { it.label == "总计 Token" }.value)
        assertEquals("0", overviewMetrics(UsageSummary()).single { it.label == "总计 Token" }.value)
        assertEquals("0", groupMetrics(UsageSummary.UsageGroup()).single { it.label == "总计 Token" }.value)
    }

    @Test
    fun groupCacheWriteIsDecodedAndOlderResponsesRemainCompatible() {
        val group = Json.decodeFromString<UsageSummary.UsageGroup>("""{"input":120,"output":30,"cache_read":400,"cache_write":50}""")
        assertEquals("600", groupMetrics(group).single { it.label == "总计 Token" }.value)
        val older = Json.decodeFromString<UsageSummary.UsageGroup>("""{"input":120,"output":30,"cache_read":400}""")
        assertEquals("550", groupMetrics(older).single { it.label == "总计 Token" }.value)
    }

    @Test
    fun groupDescriptionPrefersSubAndOmitsCompositeId() {
        val group = UsageSummary.UsageGroup(id = "account-1", name = "账号", sub = "订阅", provider = "供应商", model = "完整模型名", host = "host")
        assertEquals("完整模型名", groupTitle(group))
        assertEquals("订阅", groupDescription(group))
        assertEquals("供应商 · host", groupDescription(group.copy(sub = null)))
        assertEquals("id", groupTitle(UsageSummary.UsageGroup(id = "id", name = " ")))
        assertEquals("", groupDescription(UsageSummary.UsageGroup(id = "same", name = "same")))
        assertEquals("", groupDescription(UsageSummary.UsageGroup(id = "codex", name = "Codex", sub = "codex", provider = "CODEX")))
        assertEquals("", groupDescription(UsageSummary.UsageGroup(id = "pi", name = "Pi")))
        assertEquals("chatgpt.com", groupDescription(group.copy(sub = null, provider = "chatgpt.com", host = "CHATGPT.COM")))
        assertEquals("供应商", groupDescription(group.copy(model = null, sub = null)))
    }

    @Test
    fun todayDetailsOmitFutureEmptyHoursAndPaginateFilteredSeries() {
        val now = Instant.parse("2026-10-25T06:42:00Z")
        val hours = List(24) { hour ->
            UsageSummary.DayPoint(time = "2026-10-25T${hour.toString().padStart(2, '0')}:00:00+08:00")
        }
        val visible = detailSeries(hours, "today", now)
        assertEquals(hours.take(15), visible)
        assertEquals(3, dailyDetailsPageCount(visible))
        assertEquals(hours[14], dailyDetailsPage(visible, 0).first())
        assertEquals(listOf(7, 7, 1), (0..2).map { dailyDetailsPage(visible, it).size })
        assertEquals(hours, detailSeries(hours, "7d", now))
        assertEquals(hours, detailSeries(hours, "30d", now))
        assertEquals(hours.take(15), detailSeries(hours, "today", Instant.parse("2026-10-25T06:00:00Z")))
    }

    @Test
    fun futureHoursWithAnyDataOrUnknownTimestampArePreserved() {
        val future = UsageSummary.DayPoint(time = "2026-10-25T23:00:00+08:00")
        val retained = listOf(
            future.copy(calls = 1), future.copy(errors = 1), future.copy(input = 1),
            future.copy(output = 1), future.copy(cost = 0.000001),
            future.copy(time = "", label = "23"), future.copy(time = "invalid"),
            future.copy(time = "2026-10-25T23:00:00"),
        )
        assertEquals(retained, detailSeries(retained + future, "today", Instant.parse("2026-10-25T06:42:00Z")))
    }

    @Test
    fun todayFilteringUsesTimestampInstantRatherThanLabelOrDeviceHour() {
        val past = UsageSummary.DayPoint(label = "23", time = "2026-10-25T20:00:00+14:00")
        val future = UsageSummary.DayPoint(label = "01", time = "2026-10-25T01:00:00-08:00")
        assertEquals(listOf(past), detailSeries(listOf(past, future), "today", Instant.parse("2026-10-25T06:42:00Z")))
    }

    @Test
    fun zeroSeriesHasZeroPeakAndZeroBarHeight() {
        val series = List(7) { UsageSummary.DayPoint(calls = 0) }
        val peak = dailyPeakCalls(series)
        assertEquals(0L, peak)
        series.forEach { assertEquals(0f, dailyCallFraction(it.calls, peak), 0f) }
        assertEquals(0L, dailyPeakCalls(emptyList()))
    }

    @Test
    fun nonzeroSeriesKeepsActualPeakAndProportionalHeights() {
        val peak = dailyPeakCalls(listOf(0L, 2L, 4L).map { UsageSummary.DayPoint(calls = it) })
        assertEquals(4L, peak)
        assertEquals(0f, dailyCallFraction(0, peak), 0f)
        assertEquals(0.5f, dailyCallFraction(2, peak), 0f)
        assertEquals(1f, dailyCallFraction(4, peak), 0f)
    }
}
