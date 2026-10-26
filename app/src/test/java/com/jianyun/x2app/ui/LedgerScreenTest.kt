package com.jianyun.x2app.ui

import com.jianyun.x2app.data.RequestRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.Json
import org.junit.Test

class LedgerScreenTest {
    @Test
    fun statusesFollowBackendFailureRuleWithoutInventingSuccess() {
        val unknown = RequestRow(source = "log")
        assertFalse(unknown.failed)
        assertFalse(unknown.ok)
        assertEquals("状态未记录", requestStatusText(unknown))
        assertEquals("—", requestDetails(unknown).toMap()["HTTP 状态"])
        assertEquals("成功", requestStatusText(RequestRow(status = 200)))
        val streamedError = RequestRow(status = 200, err = "stream failed")
        assertTrue(streamedError.failed)
        assertFalse(streamedError.ok)
        assertEquals("失败 · HTTP 200", requestStatusText(streamedError))
        val canceled = Json.decodeFromString<RequestRow>("""{"status":499,"err":"the agent canceled the request","handed_on":false}""")
        assertTrue(canceled.failed)
        assertFalse(canceled.ok)
        assertEquals("已取消 · HTTP 499", requestStatusText(canceled))
        assertEquals("失败 · HTTP 503 · 已转交重试", requestStatusText(RequestRow(status = 503, handedOn = true)))
    }

    @Test
    fun detailSectionsKeepEveryFieldAndFullWidthLongValues() {
        val row = RequestRow(req = "asked-model", served = "served-model", err = "line one\nline two", session = "s".repeat(80))
        val sections = requestDetailSections(row)
        assertEquals(listOf("概况", "Token 与费用", "诊断"), sections.map { it.first })
        val fields = sections.flatMap { it.second }
        assertEquals(requestDetails(row).size, fields.size)
        assertEquals(requestDetails(row).toMap(), fields.toMap())
        assertEquals("asked-model", fields.toMap()["请求模型"])
        assertEquals("served-model", fields.toMap()["实际服务模型"])
        val layout = detailFieldRows(fields, wide = true)
        assertTrue(layout.any { it.size == 2 })
        assertTrue(layout.filter { cells -> cells.any { it.first == "错误" || it.first == "会话" } }.all { it.size == 1 })
        assertTrue(detailFieldRows(fields, wide = false).all { it.size == 1 })
    }

    @Test
    fun pageInputRejectsInvalidPagesAndConvertsToZeroBasedIndex() {
        assertEquals(0, ledgerPageInput("1", 800))
        assertEquals(799, ledgerPageInput("800", 800))
        listOf("", "0", "-1", "801", "1.5", "999999999999999").forEach {
            assertEquals(null, ledgerPageInput(it, 800))
        }
    }

    @Test
    fun costFormattingStableAcrossDeviceLocale() {
        val previous = java.util.Locale.getDefault()
        try {
            listOf(java.util.Locale.GERMANY, java.util.Locale.FRANCE, java.util.Locale.CHINA).forEach { locale ->
                java.util.Locale.setDefault(locale)
                assertEquals("$1.2345", formatCost(1.2345))
                assertEquals("$0.0000", formatCost(0.0))
            }
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun billionTokenCountsUseBillionUnit() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.US)
            assertEquals("1.5B", fmtNum(1_454_300_000))
            assertEquals("999.0M", fmtNum(999_000_000))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun detailsPreserveFullTextAndExactNumbers() {
        val error = "完整错误\n".repeat(100)
        val row = RequestRow(
            err = error,
            model = "provider/model-with-long-name",
            providerAccount = "full-upstream-account",
            `in` = 1234567,
            ttftMs = 1234,
            cost = 0.000001,
            priced = true,
        )
        val details = requestDetails(row).toMap()
        assertEquals(error, details["错误"])
        assertEquals(row.model, details["模型"])
        assertEquals(row.providerAccount, details["上游账号"])
        assertEquals("1234567", details["输入 Token"])
        assertEquals("1234 ms", details["首 Token 耗时（TTFT）"])
        assertEquals("$${row.cost}", details["费用"])
        assertEquals("已计价", details["计价状态"])
    }

    @Test
    fun missingMeasurementsAreNotPresentedAsZeroOrUnpriced() {
        val missing = requestDetails(RequestRow()).toMap()
        assertEquals("—", missing["首 Token 耗时（TTFT）"])
        assertEquals("—", missing["费用"])
        assertEquals("未知", missing["计价状态"])
        val zero = requestDetails(RequestRow(ttftMs = 0, cost = 0.0, priced = false)).toMap()
        assertEquals("0 ms", zero["首 Token 耗时（TTFT）"])
        assertEquals("$0.0", zero["费用"])
        assertEquals("未计价", zero["计价状态"])
    }
}
