// Android bridge adapted from tailscale/tailscale-android (BSD-3-Clause).
// See THIRD_PARTY_NOTICE.txt in this directory.
package com.jianyun.x2app.tailscale

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.IpPrefix
import android.net.VpnService
import android.os.Build
import android.system.OsConstants
import kotlinx.coroutines.*
import libtailscale.Libtailscale
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class TailscaleVpnService : VpnService(), libtailscale.IPNService {
    private val serviceId = UUID.randomUUID().toString()
    private val closing = AtomicBoolean(false)
    private val stopped = CompletableDeferred<Unit>()
    private lateinit var controller: TailscaleController
    internal val isClosing: Boolean get() = closing.get()

    override fun onCreate() {
        super.onCreate()
        controller = TailscaleController.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            showNotification()
            if (intent?.action == START && !controller.acceptsStart(intent.getLongExtra(GENERATION, 0L))) {
                // A cancelled start may arrive after a newer command has already attached this
                // service. Ignore that stale delivery without tearing down the newer connection.
                if (controller.ownsService(this)) return START_STICKY
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            controller.platform.onNetwork = { network ->
                if (!setUnderlyingNetworks(network?.let { arrayOf(it) } ?: emptyArray())) {
                    controller.failure(IllegalStateException("无法更新 Tailscale 底层网络"))
                }
            }
            controller.attached(this)
            when (intent?.action) {
                STOP -> controller.scope.launch { controller.disconnect() }
                START -> Unit
                else -> controller.restore(this)
            }
            return START_STICKY
        } catch (e: Exception) {
            controller.failure(e)
            stopSelf()
            return START_NOT_STICKY
        }
    }

    private fun showNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Tailscale 网络接入", NotificationManager.IMPORTANCE_LOW)
        )
        val disconnect = PendingIntent.getService(this, 1, Intent(this, TailscaleVpnService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Tailscale 网络接入").setContentText("正在维护登录或 VPN 连接")
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null, "断开", disconnect).build())
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            builder.setContentIntent(PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
        else startForeground(NOTIFICATION, builder.build())
    }

    override fun id(): String = serviceId
    // protect(Int) is inherited from VpnService and fulfills the native interface.
    override fun updateVpnStatus(active: Boolean) {
        if (controller.ownsService(this)) controller.vpnActive(active && !closing.get())
    }
    override fun close() { controller.scope.launch { shutdown() } }
    override fun disconnectVPN() {
        controller.failure(IllegalStateException("Tailscale 引擎关闭了 VPN，请检查系统授权和网络状态"))
        close()
    }

    internal suspend fun shutdown() {
        if (!closing.compareAndSet(false, true)) { stopped.await(); return }
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                if (controller.initialized) {
                    Libtailscale.serviceDisconnect(this@TailscaleVpnService)
                    // The channel is unbuffered. A second send is accepted only after the first
                    // handler finishes CloseTUNs; it is an idempotent barrier, not a second tunnel.
                    Libtailscale.serviceDisconnect(this@TailscaleVpnService)
                }
                if (controller.ownsService(this@TailscaleVpnService)) controller.vpnActive(false)
                withContext(Dispatchers.Main.immediate) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                stopped.complete(Unit)
            } catch (e: Exception) {
                controller.failure(e)
                stopped.completeExceptionally(e)
                throw e
            }
        }
    }

    override fun onRevoke() {
        controller.vpnActive(false)
        controller.revoked(this)
        super.onRevoke()
    }

    override fun onDestroy() {
        if (controller.ownsService(this)) {
            controller.platform.onNetwork = null
            controller.vpnActive(false)
        }
        controller.scope.launch {
            try { shutdown() } finally { controller.detached(this@TailscaleVpnService) }
        }
        super.onDestroy()
    }

    override fun newBuilder(): libtailscale.VPNServiceBuilder {
        return object : libtailscale.VPNServiceBuilder {
            // NewBuilder has no Go error return. Defer Android calls to methods that do.
            private val builder by lazy {
                Builder().setSession("X2 Tailscale").setMetered(false)
                    .allowFamily(OsConstants.AF_INET).allowFamily(OsConstants.AF_INET6)
                    .setUnderlyingNetworks(controller.platform.network?.let { arrayOf(it) } ?: emptyArray())
            }
            override fun setMTU(mtu: Int) { builder.setMtu(mtu) }
            override fun addDNSServer(address: String) { builder.addDnsServer(address) }
            override fun addSearchDomain(domain: String) { builder.addSearchDomain(domain) }
            override fun addRoute(address: String, prefix: Int) { builder.addRoute(address, prefix) }
            override fun addAddress(address: String, prefix: Int) { builder.addAddress(address, prefix) }
            override fun excludeRoute(address: String, prefix: Int) {
                // libtailscale computes included route ranges itself below API 33.
                if (Build.VERSION.SDK_INT >= 33) builder.excludeRoute(IpPrefix(InetAddress.getByName(address), prefix))
            }
            override fun establish(): libtailscale.ParcelFileDescriptor {
                try {
                    check(!closing.get()) { "Tailscale 服务正在关闭" }
                    val descriptor = checkNotNull(builder.establish()) { "系统未建立 Tailscale VPN，请重新授权" }
                    return object : libtailscale.ParcelFileDescriptor {
                        override fun detach(): Int = descriptor.detachFd()
                    }
                } catch (e: Exception) {
                    controller.failure(e)
                    throw e
                }
            }
        }
    }

    companion object {
        internal const val START = "com.jianyun.x2app.tailscale.START"
        internal const val GENERATION = "startGeneration"
        private const val STOP = "com.jianyun.x2app.tailscale.STOP"
        private const val CHANNEL = "x2_tailscale"
        private const val NOTIFICATION = 402
    }
}
