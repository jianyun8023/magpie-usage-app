package com.jianyun.x2app.ui

import com.jianyun.x2app.data.Holds
import com.jianyun.x2app.data.QuotaCard
import com.jianyun.x2app.data.QuotaWindow
import org.junit.Assert.assertEquals
import org.junit.Test
import androidx.compose.ui.unit.dp

class QuotasScreenTest {
    @Test
    fun keepsWindowsWithDifferentHoldsOrReasons() {
        val window = QuotaWindow(name = "daily", used = 10.0)
        val windows = listOf(
            window,
            window.copy(holds = Holds(tokens = 1.0)),
            window.copy(holds = Holds(cost = 1.0)),
            window.copy(holds = Holds(priced = true)),
            window.copy(holds = Holds(used = 1.0)),
            window.copy(holdsWhy = "low"),
        )
        assertEquals(windows, windowsShown(QuotaCard(provider = "test", windows = windows + windows)))
    }

    @Test
    fun stillDeduplicatesAliasesOfTheSamePool() {
        val window = QuotaWindow(name = "alias1", pool = "shared", holds = Holds(tokens = 2.0))
        assertEquals(
            listOf(window),
            windowsShown(QuotaCard(provider = "test", windows = listOf(window, window.copy(name = "alias2")))),
        )
    }

    @Test
    fun quotaGridDensifiesForLandscapeAndTabletWidths() {
        // 竖屏（含平板竖屏）保持单列
        assertEquals(1, quotaColumns(419.dp, 800.dp))
        assertEquals(1, quotaColumns(768.dp, 1024.dp))
        // 横屏手机两列，更宽三列，超宽四列
        assertEquals(2, quotaColumns(740.dp, 360.dp))
        assertEquals(2, quotaColumns(899.dp, 400.dp))
        assertEquals(3, quotaColumns(900.dp, 400.dp))
        assertEquals(3, quotaColumns(1199.dp, 500.dp))
        assertEquals(4, quotaColumns(1200.dp, 585.dp))
    }

    @Test
    fun quotaGridKeepsMultipleRowsInShortLandscapeHeights() {
        // 横屏手机高度紧张单行，横屏平板两行，竖屏维持原有多行
        assertEquals(1, quotaRows(360.dp, 740.dp))
        assertEquals(2, quotaRows(585.dp, 780.dp))
        assertEquals(2, quotaRows(500.dp, 400.dp))
        assertEquals(2, quotaRows(699.dp, 600.dp))
        assertEquals(3, quotaRows(800.dp, 600.dp))
    }

    @Test
    fun quotaManagerUsesCompactRowsAndMultiColumnsInLandscape() {
        assertEquals(4, quotaManagerRows(419.dp))
        assertEquals(6, quotaManagerRows(420.dp))
        assertEquals(8, quotaManagerRows(620.dp))
        // 竖屏单列，横屏手机两列，更宽三列
        assertEquals(1, quotaManagerColumns(559.dp, 800.dp))
        assertEquals(2, quotaManagerColumns(740.dp, 360.dp))
        assertEquals(3, quotaManagerColumns(840.dp, 400.dp))
    }
}
