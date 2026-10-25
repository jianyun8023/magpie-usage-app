package com.jianyun.x2app.ui

import com.jianyun.x2app.data.Holds
import com.jianyun.x2app.data.QuotaCard
import com.jianyun.x2app.data.QuotaWindow
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
