package com.jianyun.x2app.ui

import com.jianyun.x2app.data.BalanceSplit
import com.jianyun.x2app.data.Holds
import com.jianyun.x2app.data.QuotaCard
import com.jianyun.x2app.data.QuotaResetTime
import com.jianyun.x2app.data.QuotaResets
import com.jianyun.x2app.data.QuotaWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuotaSummaryDetailsTest {
    @Test
    fun duplicateNumericDisplayKeepsOnlyReadableLabeledAmount() {
        val window = QuotaWindow(amount = 80269119.0, limit = 200000000.0,
            display = "80269119 / 200000000")
        val expected = listOf("已用 80,269,119 / 200,000,000")
        assertEquals(expected, windowAmountDetails(window))
        assertEquals(expected, windowAmountDetails(window.copy(display = "80,269,119 / 200,000,000")))
        assertEquals(listOf("已用 2,369,424 / 300,000,000"), windowAmountDetails(
            QuotaWindow(amount = 2369424.0, limit = 300000000.0, display = "2369424 / 300000000")))
        val raw = quotaDetailText(QuotaCard(provider = "test", windows = listOf(window)))
        assertTrue(raw.contains("80269119 / 200000000"))
    }

    @Test
    fun distinctDescriptionAndDifferentNumbersAreRetained() {
        val window = QuotaWindow(amount = 1234.5, limit = 5000.0, display = "本月额度")
        assertEquals(listOf("本月额度", "已用 1,234.50 / 5,000"), windowAmountDetails(window))
        assertEquals(2, windowAmountDetails(window.copy(display = "1234 / 5000")).size)
        assertEquals(listOf("本月额度"), windowAmountDetails(window.copy(amount = null, limit = null)))
    }

    @Test
    fun lowEstimateSampleIsNotLowRemainingQuota() {
        val window = QuotaWindow(used = 4.0, holdsWhy = "low")
        val text = capacityEstimateText(window)
        assertTrue(text.contains("估算样本不足"))
        assertFalse(text.contains("额度偏低"))
        assertFalse(text.contains("占用"))
        val diagnostic = quotaDetailText(QuotaCard(provider = "test", windows = listOf(window)))
        assertTrue(diagnostic.contains("剩余 96%"))
        assertTrue(diagnostic.contains("估算样本不足"))
        assertFalse(diagnostic.contains("额度偏低"))
        assertFalse(diagnostic.contains("占用"))
    }

    @Test
    fun wholeWindowCostIsShownOnlyWhenPriced() {
        val window = QuotaWindow(holds = Holds(tokens = 1200000.0, cost = 4.5, priced = false, used = 20.0))
        assertEquals("整窗约 1,200,000 Token", capacityEstimateText(window))
        assertTrue(capacityEstimateText(window.copy(holds = window.holds!!.copy(priced = true)))
            .contains("API 标价约"))
        val diagnostic = quotaDetailText(QuotaCard(provider = "test", windows = listOf(window)))
        assertTrue(diagnostic.contains("估算依据已用比例：20.0%"))
        assertFalse(diagnostic.contains("占用"))
    }

    @Test
    fun unavailableEstimatesExplainBackendReasonsAndPreserveUnknownReasons() {
        mapOf("clock" to "窗口起止时间未知", "log" to "请求日志未覆盖整个窗口",
            "none" to "窗口内暂无经 Magpie 转发的调用",
            "credits" to "部分调用可能使用额外余额支付", "future" to "future").forEach { (reason, label) ->
            assertTrue(capacityEstimateText(QuotaWindow(holdsWhy = reason)).contains(label))
        }
        assertEquals("", capacityEstimateText(QuotaWindow(holdsWhy = "-")))
        assertEquals("", capacityEstimateText(QuotaWindow()))
        val lowQuota = quotaDetailText(QuotaCard(provider = "test", windows = listOf(QuotaWindow(used = 99.0))))
        assertTrue(lowQuota.contains("额度偏低"))
    }
    @Test
    fun diagnosticsRetainOriginalNamesAndExplicitUnlimitedFlag() {
        val card = QuotaCard(provider = "provider-id", name = "", kind = "plan", icon = "icon-id",
            windows = listOf(QuotaWindow(name = "", pool = "shared", unlimited = false)))
        val text = quotaDetailText(card)
        listOf("提供方：provider-id", "名称：\n", "类型：plan", "图标：icon-id",
            "额度池：shared", "不限量：否").forEach { assertTrue(text.contains(it)) }
    }

    @Test
    fun summaryPrioritizesTwoLowestWindowsAndKeepsResetTimes() {
        val empty = QuotaWindow(name = "empty", used = 100.0, resetsAt = "2026-10-25T12:00:00Z")
        val low = QuotaWindow(name = "low", amount = 94.0, limit = 100.0, resetsAt = "2026-10-26T00:00:00Z")
        val card = QuotaCard(provider = "test", windows = listOf(
            QuotaWindow(name = "unknown"),
            QuotaWindow(name = "unlimited", unlimited = true, used = 100.0),
            QuotaWindow(name = "healthy", remaining = 90.0), low, empty,
        ))
        assertEquals(listOf(empty, low), summaryWindows(card))
    }

    @Test
    fun aliasesDoNotCrowdOutAnotherCriticalWindow() {
        val shared = QuotaWindow(name = "alias1", pool = "shared", remaining = 0.0)
        val second = QuotaWindow(name = "second", remaining = 2.0)
        val card = QuotaCard(provider = "test", windows = listOf(shared, shared.copy(name = "alias2"), second))
        assertEquals(listOf(shared, second), summaryWindows(card))
        val details = quotaDetailText(card)
        assertTrue(details.contains("alias1"))
        assertTrue(details.contains("alias2"))
        assertTrue(details.contains("second"))
    }

    @Test
    fun fullDetailsKeepHiddenWindowsAndExactValues() {
        val timestamp = "2026-10-25T12:34:56.789+08:00"
        val last = QuotaWindow(name = "third window", remaining = 80.123456,
            startsAt = timestamp, resetsAt = timestamp, display = "full description",
            holds = Holds(tokens = 123.456, cost = 0.000123, priced = true, used = 7.89),
            holdsWhy = "full hold reason")
        val card = QuotaCard(provider = "test", windows = listOf(
            QuotaWindow(name = "first", remaining = 0.0),
            QuotaWindow(name = "second", remaining = 1.0), last,
        ))
        assertEquals(2, summaryWindows(card).size)
        val text = quotaDetailText(card)
        listOf("third window", "80.123456", timestamp, "full description", "123.456",
            "${0.000123}", "API 标价估算可用：是", "7.89", "full hold reason").forEach {
            assertTrue("Missing detail: $it", text.contains(it))
        }
    }

    @Test
    fun errorDoesNotHideBalanceOrAccountDetails() {
        val card = QuotaCard(provider = "test", kind = "balance", user = "account",
            balance = "12.34", balanceSplit = listOf(BalanceSplit("credits", "5.67")),
            error = "Detailed error\nsecond line", until = "exact expiry", readAt = "exact read time")
        val text = quotaDetailText(card)
        listOf("account", "12.34", "credits：5.67", "Detailed error\nsecond line",
            "exact expiry", "exact read time").forEach { assertTrue(text.contains(it)) }
        assertTrue(summaryWindows(card).isEmpty())
    }

    @Test
    fun balanceSplitLabelsNormalizeKnownValuesAndKeepUnknownValues() {
        assertEquals("赠送", balanceSplitLabel("Granted"))
        assertEquals("充值", balanceSplitLabel("Topped up"))
        assertEquals("充值", balanceSplitLabel(" topped-up "))
        assertEquals("Future credit", balanceSplitLabel("Future credit"))
    }

    @Test
    fun resetSummaryKeepsZeroAndTreatsNegativeOrMissingAsUnknown() {
        assertEquals(listOf("可用重置 0 次"),
            quotaResetSummaryLines(QuotaCard(provider = "codex", resets = QuotaResets(count = 0))))
        assertEquals(listOf("重置额度最早到期 ${localStamp("2030-01-01T00:00:00Z")}"),
            quotaResetSummaryLines(QuotaCard(provider = "codex",
                resets = QuotaResets(count = -1, until = "2030-01-01T00:00:00Z"))))
        assertEquals(emptyList<String>(), quotaResetSummaryLines(QuotaCard(provider = "codex")))
    }

    @Test
    fun windowedResetsUseDedicatedCountsAndSkipGenericCount() {
        val card = QuotaCard(provider = "glm", resets = QuotaResets(
            count = 9, fiveHour = 0, weekly = 2, until = "2030-01-01T00:00:00Z", byWindow = true))
        assertEquals(listOf("5小时重置 0 次 / 每周重置 2 次",
            "重置额度最早到期 ${localStamp("2030-01-01T00:00:00Z")}"), quotaResetSummaryLines(card))
    }

    @Test
    fun resetEachLinesLabelWindowsAndErrorCardsHideStaleQuota() {
        val resets = QuotaResets(each = listOf(
            QuotaResetTime(until = "2030-01-01T00:00:00Z", window = "fiveHour"),
            QuotaResetTime(until = "2030-01-08T00:00:00Z", window = "weekly"),
            QuotaResetTime(until = "2030-02-01T00:00:00Z"),
            QuotaResetTime(window = "fiveHour"),
        ))
        assertEquals(listOf("5小时重置额度到期 ${localStamp("2030-01-01T00:00:00Z")}",
            "每周重置额度到期 ${localStamp("2030-01-08T00:00:00Z")}",
            "重置额度到期 ${localStamp("2030-02-01T00:00:00Z")}"),
            quotaResetEachLines(QuotaCard(provider = "codex", resets = resets)))
        assertEquals(emptyList<String>(), quotaResetEachLines(QuotaCard(provider = "codex", error = "down", resets = resets)))
    }

    @Test
    fun singleResetExpiryDoesNotRepeatSummaryButKeepsWindowAndDifferentExpiry() {
        val until = "2030-01-01T00:00:00Z"
        val resets = QuotaResets(count = 1, until = until, each = listOf(QuotaResetTime(until = until)))
        val card = QuotaCard(provider = "codex", resets = resets)
        assertTrue(quotaResetSummaryLines(card).any { it.contains(localStamp(until)) })
        assertTrue(quotaResetEachLines(card).isEmpty())
        assertEquals(listOf("每周重置额度到期 ${localStamp(until)}"),
            quotaResetEachLines(card.copy(resets = resets.copy(each = listOf(QuotaResetTime(until, "weekly"))))))
        val later = "2030-02-01T00:00:00Z"
        assertEquals(listOf("重置额度到期 ${localStamp(later)}"),
            quotaResetEachLines(card.copy(resets = resets.copy(each = listOf(QuotaResetTime(later))))))
    }

    @Test
    fun detailWindowTitleDistinguishesPoolMembersWithoutRepeatingSameName() {
        val window = QuotaWindow(name = "weekly", pool = "Gemini")
        assertEquals("Gemini · weekly", quotaWindowTitle(window))
        assertEquals("Gemini", quotaWindowTitle(window, summary = true))
        assertEquals("Gemini", quotaWindowTitle(window.copy(name = " gemini ")))
        assertEquals("Gemini", quotaWindowTitle(window.copy(name = "")))
        assertEquals("weekly", quotaWindowTitle(window.copy(pool = " ")))
        assertEquals("未命名窗口", quotaWindowTitle(QuotaWindow()))
    }

    private fun localStamp(iso: String): String = java.time.format.DateTimeFormatter
        .ofPattern("MM-dd HH:mm").format(java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()))
}
