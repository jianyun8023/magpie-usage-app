package com.jianyun.x2app

import org.junit.Assert.assertEquals
import org.junit.Test

class TabTest {
    @Test fun restoredFallsBackToQuotasForUnknownOrMissingValues() {
        assertEquals(Tab.QUOTAS, Tab.restored(null))
        assertEquals(Tab.QUOTAS, Tab.restored(""))
        assertEquals(Tab.QUOTAS, Tab.restored("REMOVED_IN_NEW_VERSION"))
        assertEquals(Tab.LEDGER, Tab.restored("LEDGER"))
        assertEquals(Tab.USAGE, Tab.restored("USAGE"))
    }
}
