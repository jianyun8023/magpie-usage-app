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
        assertEquals(1, quotaColumns(419.dp))
        assertEquals(2, quotaColumns(420.dp))
        assertEquals(3, quotaColumns(720.dp))
        assertEquals(4, quotaColumns(1000.dp))
    }

    @Test
    fun quotaGridKeepsMultipleRowsInShortLandscapeHeights() {
        assertEquals(1, quotaRows(419.dp))
        assertEquals(2, quotaRows(420.dp))
        assertEquals(3, quotaRows(700.dp))
    }

    @Test
    fun quotaManagerUsesCompactRowsAndMultiColumnsInLandscape() {
        assertEquals(4, quotaManagerRows(419.dp))
        assertEquals(6, quotaManagerRows(420.dp))
        assertEquals(8, quotaManagerRows(620.dp))
        assertEquals(1, quotaManagerColumns(559.dp))
        assertEquals(2, quotaManagerColumns(560.dp))
        assertEquals(3, quotaManagerColumns(900.dp))
    }
}
