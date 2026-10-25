package com.jianyun.x2app.vpn

import android.os.IBinder
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class WireGuardIpcLifetimeTest {
    @Test fun lateBindCallbackCannotSendAfterTimeoutOrCancellation() {
        val request = WgRequest(1).apply { bound = true }
        assertTrue(request.canSend(request))
        request.finish()
        assertTrue(request.result.isCancelled)
        assertFalse(request.canSend(request))
    }

    @Test fun oldCallbackCannotSendForNewRequest() {
        val previous = WgRequest(1).apply { bound = true }
        val current = WgRequest(2).apply { bound = true }
        assertFalse(previous.canSend(current))
        assertTrue(current.canSend(current))
    }

    @Test fun oldCachedProcessDeathDoesNotFailNewBinding() {
        val oldBinder = binder()
        val newBinder = binder()
        val previous = WgRequest(1).apply { bound = true; binder = oldBinder }
        previous.finish()
        val current = WgRequest(2).apply { bound = true }
        assertFalse(current.affectedByDeath(oldBinder, current))
        current.binder = newBinder
        assertFalse(current.affectedByDeath(oldBinder, current))
        assertTrue(current.affectedByDeath(newBinder, current))
        assertFalse(previous.affectedByDeath(oldBinder, current))
    }

    @Test fun unbindStillWaitsForDestroyReceiptOrSameProcessDeath() {
        val remote = binder()
        val request = WgRequest(1).apply { bound = true; binder = remote }
        request.bound = false // First phase DOWN receipt releases binding, not the operation.
        assertFalse(request.canSend(request))
        assertTrue(request.affectedByDeath(remote, request))
        assertFalse(request.result.isCompleted)
        request.result.complete(WireGuardStatus(serviceAlive = false))
        assertFalse(request.affectedByDeath(remote, request))
    }

    private fun binder() = Proxy.newProxyInstance(IBinder::class.java.classLoader, arrayOf(IBinder::class.java)) { _, _, _ -> null } as IBinder
}
