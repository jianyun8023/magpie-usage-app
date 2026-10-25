package com.jianyun.x2app.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal object WgProtocol {
    const val CONTROL = "com.jianyun.x2app.vpn.CONTROL"
    const val CONNECT = 1
    const val DISCONNECT = 2
    const val STATUS = 3
    const val DEBUG_FOREGROUND = 4
    const val DEBUG_NATIVE = 5
    const val RESULT = 10
    const val RELEASE_BINDING = 11
    const val STATE = 12
}

internal class WgTransportException(message: String) : IllegalStateException(message)
internal class WgRemoteException(message: String) : IllegalStateException(message)

internal data class WireGuardStatus(
    val up: Boolean = false,
    val rx: Long = 0,
    val tx: Long = 0,
    val handshake: Long = 0,
    val pid: Int = 0,
    val serviceAlive: Boolean = true,
    val foreground: Boolean = false,
    val nativeVersion: String? = null,
    val error: String? = null,
) {
    fun bundle() = Bundle().apply {
        putBoolean("up", up); putLong("rx", rx); putLong("tx", tx); putLong("handshake", handshake)
        putInt("pid", pid); putBoolean("serviceAlive", serviceAlive); putBoolean("foreground", foreground)
        putString("nativeVersion", nativeVersion); putString("error", error)
    }
    companion object {
        fun from(data: Bundle) = WireGuardStatus(
            data.getBoolean("up"), data.getLong("rx"), data.getLong("tx"), data.getLong("handshake"),
            data.getInt("pid"), data.getBoolean("serviceAlive"), data.getBoolean("foreground"),
            data.getString("nativeVersion"), data.getString("error"),
        )
    }
}

/** One app request, including the unbound interval while waiting for the destroy receipt. */
internal class WgRequest(val id: Int) {
    val result = CompletableDeferred<WireGuardStatus>()
    var bound = false
    var binder: IBinder? = null
    private var active = true
    fun isCurrent(current: WgRequest?) = active && current === this && !result.isCompleted
    fun canSend(current: WgRequest?) = bound && isCurrent(current)
    fun affectedByDeath(dead: IBinder, current: WgRequest?) = isCurrent(current) && binder === dead
    fun finish() { active = false; result.cancel() }
}

/** Main-process proxy. No WireGuard backend or native-library references belong here. */
internal class WireGuardIpc(
    private val context: Context,
    private val onState: (WireGuardStatus) -> Unit,
    private val onDeath: () -> Unit,
) {
    private val mutex = Mutex()
    private var sequence = 0
    private var pending: WgRequest? = null
    private var unbind: (() -> Unit)? = null
    private var watchedBinder: IBinder? = null
    private var watchedRequestId = 0
    private var deathRecipient: IBinder.DeathRecipient? = null
    private var observedPid = 0
    private var deadPid = 0
    private val handler = Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != Process.myUid()) return@Handler true
        if (message.what == WgProtocol.STATE) {
            val snapshot = WireGuardStatus.from(message.data)
            if (message.arg1 == watchedRequestId && watchedBinder?.isBinderAlive == true && snapshot.pid != deadPid) {
                observedPid = snapshot.pid
                onState(snapshot)
                // A status-only binding may have been released before this normal onDestroy event.
                if (!snapshot.serviceAlive && pending == null) retireWatch()
            }
        } else {
            val request = pending
            if (request != null && message.arg1 == request.id && request.isCurrent(pending)) {
                when (message.what) {
                    WgProtocol.RELEASE_BINDING -> unbind?.invoke()
                    WgProtocol.RESULT -> {
                        val snapshot = WireGuardStatus.from(message.data)
                        observedPid = snapshot.pid
                        onState(snapshot)
                        if (!snapshot.serviceAlive && watchedRequestId == request.id) retireWatch()
                        if (message.arg2 == 1) request.result.complete(snapshot)
                        else request.result.completeExceptionally(WgRemoteException(snapshot.error ?: "WireGuard 操作失败。"))
                    }
                }
            }
        }
        true
    }
    private val replies = Messenger(handler)

    suspend fun request(command: Int): WireGuardStatus = withContext(Dispatchers.Main.immediate) {
        mutex.withLock {
            val request = WgRequest(++sequence)
            pending = request
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    // bind callbacks can arrive after timeout/cancellation or after a newer request.
                    if (!request.canSend(pending)) return
                    try {
                        request.binder = binder
                        watch(binder, request.id)
                        Messenger(binder).send(Message.obtain(null, command, request.id, 0).apply { replyTo = replies })
                    } catch (_: Exception) {
                        request.result.completeExceptionally(WgTransportException("无法联系 WireGuard 服务。"))
                    }
                }
                private fun disconnected() {
                    if (request.isCurrent(pending)) lostConnection(request)
                }
                override fun onServiceDisconnected(name: ComponentName) = disconnected()
                override fun onBindingDied(name: ComponentName) = disconnected()
                override fun onNullBinding(name: ComponentName) = disconnected()
            }
            unbind = {
                if (request.bound) {
                    request.bound = false
                    context.unbindService(connection)
                }
            }
            try {
                val intent = Intent(context, WireGuardVpnService::class.java).setAction(WgProtocol.CONTROL)
                request.bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                if (!request.bound) throw WgTransportException("无法绑定 WireGuard 服务。")
                withTimeout(25_000) { request.result.await() }
            } finally {
                request.finish() // Cancel the orphan deferred BEFORE any late callback could send CONNECT.
                unbind?.invoke()
                unbind = null
                if (pending === request) pending = null
            }
        }
    }

    private fun watch(binder: IBinder, requestId: Int) {
        watchedRequestId = requestId
        if (watchedBinder === binder) return
        retireWatch()
        watchedRequestId = requestId
        watchedBinder = binder
        val recipient = IBinder.DeathRecipient {
            handler.post {
                if (watchedBinder === binder) {
                    val affected = pending?.takeIf { it.affectedByDeath(binder, pending) }
                    retireWatch()
                    lostConnection(affected) // Never fail a newer request bound to another service/binder.
                }
            }
        }
        deathRecipient = recipient
        binder.linkToDeath(recipient, 0)
    }

    private fun retireWatch() {
        deathRecipient?.let { old -> runCatching { watchedBinder?.unlinkToDeath(old, 0) } }
        watchedBinder = null
        deathRecipient = null
        watchedRequestId = 0
    }

    private fun lostConnection(request: WgRequest?) {
        if (observedPid > 0) deadPid = observedPid
        request?.result?.completeExceptionally(WgTransportException("WireGuard 远程进程中断，状态未知；请刷新后重试。"))
        onDeath()
    }
}
