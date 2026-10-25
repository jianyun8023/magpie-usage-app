package com.jianyun.x2app.tailscale

import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class TailscaleStoreTest {
    @Test fun stateSurvivesReloadWithoutPlaintextAndDeletionPersists() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.noBackupFilesDir, "tailscale-store-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val name = "statestore-test-${UUID.randomUUID()}"
        val value = "private-session-${UUID.randomUUID()}"
        val store = TailscaleStore(context)
        try {
            store.write(name, value)
            assertEquals(value, TailscaleStore(context).read(name))
            assertFalse(File(context.noBackupFilesDir, "tailscale-state.enc").readBytes().decodeToString().contains(value))
            assertTrue(store.keys().contains(name.removePrefix("statestore-")))
            store.write(name, "")
            assertEquals("", TailscaleStore(context).read(name))
            assertFalse(store.keys().contains(name.removePrefix("statestore-")))
        } finally { directory.deleteRecursively() }
    }
}
