package com.jianyun.x2app.vpn

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.*
import android.os.Process
import com.jianyun.x2app.MainActivity
import com.wireguard.android.backend.BackendException
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All GoBackend construction/calls live exclusively in :wireguard. */
class WireGuardVpnService : GoBackend.VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val backendDelegate = lazy { GoBackend(this) }
    private val backend by backendDelegate
    private val snapshot = MutableStateFlow(WireGuardStatus(pid = Process.myPid()))
    private val foregroundReady = CompletableDeferred<Unit>()
    @Volatile private var listener: Messenger? = null
    @Volatile private var listenerRequestId = 0
    private var disconnectReply: Pair<Messenger, Int>? = null
    @Volatile private var stopping = false
    @Volatile private var revoked = false
    private val tunnel = object : Tunnel {
        override fun getName() = "x2-magpie"
        override fun onStateChange(state: Tunnel.State) {
            snapshot.update { it.copy(up = state == Tunnel.State.UP, rx = 0, tx = 0, handshake = 0) }
            publish()
        }
    }
    private val control = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.sendingUid != Process.myUid()) return@Handler true
        val reply = message.replyTo ?: return@Handler true
        val command = message.what
        val id = message.arg1
        listenerRequestId = id
        listener = reply
        scope.launch {
            commands.withLock {
                try {
                    check(!stopping) { "WireGuard 服务正在停止，请重试。" }
                    snapshot.update { it.copy(error = null) }
                    when (command) {
                        WgProtocol.CONNECT -> connect()
                        WgProtocol.DISCONNECT -> {
                            disconnect(reply, id)
                            return@withLock
                        }
                        WgProtocol.STATUS -> refresh()
                        WgProtocol.DEBUG_FOREGROUND -> { requireDebug(); startForegroundServiceAndWait() }
                        WgProtocol.DEBUG_NATIVE -> {
                            requireDebug()
                            snapshot.update { it.copy(nativeVersion = backend.version) }
                        }
                        else -> error("不支持的 WireGuard 命令。")
                    }
                    sendResult(reply, id, true)
                } catch (e: Exception) {
                    if (e is CancellationException && (e !is TimeoutCancellationException || !currentCoroutineContext().isActive)) throw e
                    val error = when ((e as? BackendException)?.reason) {
                        BackendException.Reason.VPN_NOT_AUTHORIZED -> "系统 VPN 授权已撤销，请重新授权。"
                        BackendException.Reason.DNS_RESOLUTION_FAILURE -> "无法解析 WireGuard peer 地址，请检查网络。"
                        else -> "WireGuard 操作失败，请检查配置、网络和系统授权后重试。"
                    }
                    snapshot.update { it.copy(error = error) }
                    sendResult(reply, id, false)
                } catch (_: LinkageError) {
                    snapshot.update { it.copy(error = "此设备无法加载 WireGuard 原生引擎。") }
                    sendResult(reply, id, false)
                }
            }
        }
        true
    })

    override fun onCreate() {
        check(Application.getProcessName() == "$packageName:wireguard") { "WireGuard requires its isolated process" }
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == WgProtocol.CONTROL) control.binder else super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == DISCONNECT) {
            scope.launch {
                commands.withLock {
                    try { disconnect(null, 0) } catch (_: Exception) {
                        snapshot.update { it.copy(error = "WireGuard 断开失败，请在应用中重试。") }
                        publish()
                    }
                }
            }
        } else if (intent?.action == START) {
            try {
                promoteForeground()
                foregroundReady.complete(Unit)
            } catch (_: Exception) {
                foregroundReady.completeExceptionally(IllegalStateException("无法启动 WireGuard 前台服务。"))
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun connect() {
        check(android.net.VpnService.prepare(this) == null)
        val bytes = VpnConfigStore(this).load() ?: error("Missing config")
        try {
            val config = parseVpnConfig(bytes, packageName)
            startForegroundServiceAndWait()
            backend.setState(tunnel, Tunnel.State.UP, config)
            check(!revoked)
            refresh()
        } catch (e: Throwable) {
            // A failed/aborted start must not leave a native tunnel or foreground service behind.
            withContext(NonCancellable) {
                if (backendDelegate.isInitialized()) backend.setState(tunnel, Tunnel.State.DOWN, null)
                requestStop()
            }
            throw e
        } finally { bytes.fill(0) }
    }

    private suspend fun disconnect(reply: Messenger?, id: Int) {
        stopping = true
        try {
            if (backendDelegate.isInitialized()) {
                backend.setState(tunnel, Tunnel.State.DOWN, null)
                check(backend.getState(tunnel) == Tunnel.State.DOWN)
            }
            snapshot.update { it.copy(up = false, rx = 0, tx = 0, handshake = 0) }
            withContext(Dispatchers.Main.immediate) {
                disconnectReply = reply?.let { it to id }
                stopForeground(STOP_FOREGROUND_REMOVE)
                snapshot.update { it.copy(foreground = false) }
                stopSelf()
                // DOWN first; release the UI binding so Android can deliver onDestroy.
                reply?.let { send(it, WgProtocol.RELEASE_BINDING, id) }
            }
        } catch (e: Exception) {
            stopping = false
            throw e
        }
    }

    private suspend fun requestStop() = withContext(Dispatchers.Main.immediate) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        snapshot.update { it.copy(foreground = false) }
        stopSelf()
    }

    private suspend fun startForegroundServiceAndWait() {
        withContext(Dispatchers.Main.immediate) {
            startForegroundService(Intent(this@WireGuardVpnService, WireGuardVpnService::class.java).setAction(START))
        }
        withTimeout(5_000) { foregroundReady.await() }
    }

    private fun refresh() {
        if (!backendDelegate.isInitialized()) return
        val up = backend.getState(tunnel) == Tunnel.State.UP
        snapshot.update { it.copy(up = up) }
        if (!up) return
        try {
            val stats = backend.getStatistics(tunnel)
            val handshake = stats.peers().maxOfOrNull { stats.peer(it)?.latestHandshakeEpochMillis() ?: 0L } ?: 0L
            snapshot.update { it.copy(rx = stats.totalRx(), tx = stats.totalTx(), handshake = handshake) }
        } catch (_: Exception) {
            snapshot.update { it.copy(error = "隧道已启动，但暂时无法读取流量和握手统计。") }
        }
    }

    override fun onRevoke() {
        revoked = true
        snapshot.update { it.copy(error = "系统 VPN 授权已撤销。") }
        scope.launch {
            commands.withLock {
                try { disconnect(null, 0) } catch (_: Exception) { requestStop() }
            }
        }
    }

    override fun onDestroy() {
        val wasUp = snapshot.value.up
        super.onDestroy() // Official backend closes the TUN and native handle before the final receipt.
        stopForeground(STOP_FOREGROUND_REMOVE)
        snapshot.update {
            it.copy(up = false, foreground = false, serviceAlive = false,
                error = if (wasUp && it.error == null) "WireGuard 服务已被系统停止。" else it.error)
        }
        publish()
        disconnectReply?.let { (reply, id) -> sendResult(reply, id, true) }
        disconnectReply = null
        listener = null
        scope.cancel()
    }

    private fun promoteForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "WireGuard VPN", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1, Intent(this, WireGuardVpnService::class.java).setAction(DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("WireGuard VPN")
            .setContentText("VPN 服务运行中 · peer 状态请在设置中查看")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "断开", disconnect).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(ID, notification)
        snapshot.update { it.copy(foreground = true) }
    }

    private fun requireDebug() { check(applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) }
    private fun publish() { listener?.let { send(it, WgProtocol.STATE, listenerRequestId) } }
    private fun sendResult(reply: Messenger, id: Int, success: Boolean) = send(reply, WgProtocol.RESULT, id, success)
    private fun send(reply: Messenger, what: Int, id: Int, success: Boolean = true) {
        try {
            reply.send(Message.obtain(null, what, id, if (success) 1 else 0).apply { data = snapshot.value.bundle() })
        } catch (_: RemoteException) { /* The UI can rebind and query the actual state. */ }
    }

    companion object {
        private const val CHANNEL = "wireguard"
        private const val ID = 51820
        private const val START = "com.jianyun.x2app.vpn.START"
        private const val DISCONNECT = "com.jianyun.x2app.vpn.DISCONNECT"
    }
}
