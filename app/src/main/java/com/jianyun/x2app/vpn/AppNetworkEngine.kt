package com.jianyun.x2app.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import appnet.Appnet
import appnet.Client
import appnet.StateStore
import com.jianyun.x2app.api.DirectHttpTransport
import com.jianyun.x2app.api.HttpResponse
import com.jianyun.x2app.api.MagpieTransport
import com.jianyun.x2app.tailscale.TailscaleControlServer
import com.jianyun.x2app.tailscale.TailscaleMessages
import com.jianyun.x2app.tailscale.TailscaleState
import com.jianyun.x2app.tailscale.TailscaleStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/** One in-process native client. No Android VPN, service, or local listener. */
class AppNetworkEngine private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val wgStore = VpnConfigStore(context)
    private val tsStore = TailscaleStore(context)
    private val preferences = context.getSharedPreferences("app-network", Context.MODE_PRIVATE)
    private val mutableMode = MutableStateFlow(AppNetworkMode.restored(preferences.getString("mode", null)))
    val mode = mutableMode.asStateFlow()
    private val mutableWg = MutableStateFlow(WireGuardState(busy = true))
    val wireGuard = mutableWg.asStateFlow()
    private val mutableTs = MutableStateFlow(TailscaleState(busy = true))
    val tailscale = mutableTs.asStateFlow()
    @Volatile private var client: Client? = null
    @Volatile private var nativeSessionID: Long = 0
    private var poller: Job? = null
    private val router = AppRequestRouter(mutableMode.value, DirectHttpTransport) { route, url, authorization, method ->
        val native = checkNotNull(client) { "应用内网络尚未连接" }
        val response = native.httpRequestForSession(url, authorization, method, route.nativeSessionID)
        HttpResponse(response.statusCode.toInt(), response.body)
    }

    init { MagpieTransport.current = router }

    private val loaded = scope.async {
        commands.withLock {
            try {
                wgStore.load()?.let { bytes ->
                    try { parseVpnConfig(bytes); mutableWg.update { it.copy(configured = true) } }
                    finally { bytes.fill(0) }
                }
            } catch (_: Exception) {
                mutableWg.update { it.copy(error = "已保存的 WireGuard 配置无法读取，请重新导入") }
            } finally { mutableWg.update { it.copy(busy = false) } }
            try {
                tsStore.keys() // Decryption errors must not silently replace the existing identity.
                val url = TailscaleControlServer.normalize(tsStore.read(TailscaleControlServer.STORE_KEY))
                mutableTs.update { it.copy(controlUrl = url) }
            } catch (_: Exception) {
                mutableTs.update { it.copy(error = "Tailscale 会话或控制服务器配置无法读取") }
            } finally { mutableTs.update { it.copy(busy = false) } }
        }
    }

    init {
        if (mutableMode.value == AppNetworkMode.TAILSCALE) scope.launch { connectTailscale() }
    }

    private fun native(): Client {
        client?.let { return it }
        tsStore.keys()
        initializeAppnet(context)
        val directory = File(context.noBackupFilesDir, "tailscale").apply { check(isDirectory || mkdirs()) }
        return Appnet.newClient(directory.absolutePath, object : StateStore {
            override fun read(key: String): String = tsStore.read(key)
            override fun write(key: String, value: String) = tsStore.write(key, value)
        }).also { client = it }
    }

    private fun select(mode: AppNetworkMode) {
        // Block first, including when persisting a selection fails.
        router.update(mode, false)
        mutableMode.value = mode
        check(preferences.edit().putString("mode", mode.name).commit()) { "无法保存应用内网络选择" }
    }

    private fun block(mode: AppNetworkMode) {
        if (mutableMode.value == mode) router.update(mode, false)
    }

    private fun stopWg() {
        block(AppNetworkMode.WIREGUARD)
        client?.stopWireGuard()
        nativeSessionID = 0
        mutableWg.update { it.copy(up = false) }
    }

    private fun stopTs() {
        poller?.cancel()
        poller = null
        block(AppNetworkMode.TAILSCALE)
        client?.stopTailscale()
        nativeSessionID = 0
        mutableTs.update { it.copy(engineActive = false, nativeConnected = false, authUrl = null,
            addresses = emptyList(), health = emptyList(), backendState = "Stopped") }
    }

    private suspend fun command(failure: String, block: suspend () -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        loaded.await()
        commands.withLock {
            mutableWg.update { it.copy(busy = true) }
            mutableTs.update { it.copy(busy = true) }
            try {
                block()
                Result.success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Parser/native exceptions can include private configuration: expose only our message.
                Result.failure(IllegalStateException(failure))
            } catch (e: LinkageError) {
                Result.failure(IllegalStateException("应用内网络原生库不可用，请检查安装包"))
            } finally {
                mutableWg.update { it.copy(busy = false) }
                mutableTs.update { it.copy(busy = false) }
            }
        }
    }

    fun importWireGuard(uri: Uri) { scope.launch {
        command("导入失败：请提供有效的 WireGuard .conf（最大 64 KB）") {
            check(!wireGuard.value.up)
            val bytes = context.contentResolver.openInputStream(uri)?.use(::readVpnConfig) ?: error("No input")
            try {
                parseVpnConfig(bytes)
                wgStore.save(bytes)
                mutableWg.update { it.copy(configured = true, error = null) }
            } finally { bytes.fill(0) }
        }.onFailure { error -> mutableWg.update { it.copy(error = error.message) } }
    } }

    fun deleteWireGuard() { scope.launch {
        command("删除失败，请先断开 WireGuard 后重试") {
            check(!wireGuard.value.up)
            wgStore.delete()
            mutableWg.update { it.copy(configured = false, error = null) }
        }.onFailure { error -> mutableWg.update { it.copy(error = error.message) } }
    } }

    fun reportTailscaleError(message: String) {
        mutableTs.update { it.copy(error = message) }
    }

    suspend fun connectWireGuard() = command("WireGuard 连接失败，请检查配置及 Endpoint 域名解析；请求未回落直连") {
        select(AppNetworkMode.WIREGUARD)
        stopTs()
        stopWg()
        val bytes = checkNotNull(wgStore.load())
        try {
            val parameters = wireGuardParameters(parseVpnConfig(bytes))
            val native = native()
            native.startWireGuard(parameters.uapi, parameters.localIPsJSON, parameters.dnsIPsJSON, parameters.mtu)
            mutableWg.update { it.copy(configured = true, up = true, error = null) }
            nativeSessionID = native.sessionID()
            router.update(AppNetworkMode.WIREGUARD, true, nativeSessionID)
        } catch (error: Exception) {
            try { stopWg() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        } finally { bytes.fill(0) }
    }.onFailure { error -> mutableWg.update { it.copy(error = error.message) } }

    suspend fun disconnectWireGuard() = command("WireGuard 断开失败，未切换网络") { stopWg() }

    private suspend fun startTailscale(login: Boolean) = command("Tailscale 启动失败；请求未回落直连") {
        select(AppNetworkMode.TAILSCALE)
        stopWg()
        stopTs()
        val control = TailscaleControlServer.normalize(tsStore.read(TailscaleControlServer.STORE_KEY))
        val native = native()
        native.startTailscale(control)
        nativeSessionID = native.sessionID()
        mutableTs.update { it.copy(engineActive = true, backendState = "Starting", controlUrl = control, error = null) }
        try {
            if (login) native.loginTailscale()
            refreshTs()
        } catch (error: Exception) {
            try { stopTs() } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
            throw error
        }
        poller = scope.launch {
            while (isActive) {
                delay(2_000)
                commands.withLock {
                    if (!tailscale.value.engineActive) return@launch
                    try { refreshTs() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) {
                        block(AppNetworkMode.TAILSCALE)
                        mutableTs.update { it.copy(nativeConnected = false, error = "无法读取 Tailscale 状态，请刷新或重新连接") }
                    }
                }
            }
        }
    }.onFailure { error ->
        mutableTs.update { it.copy(error = error.message) }
    }

    suspend fun connectTailscale() = startTailscale(false)
    suspend fun loginTailscale() = startTailscale(true)
    suspend fun disconnectTailscale() = command("Tailscale 断开失败，未切换网络") { stopTs() }
    suspend fun logoutTailscale() = command("退出 Tailscale 账号失败，请重试") { logoutTs() }

    private fun logoutTs() {
        stopTs()
        val control = TailscaleControlServer.normalize(tsStore.read(TailscaleControlServer.STORE_KEY))
        native().logoutTailscale(control)
        mutableTs.update { it.copy(backendState = "NeedsLogin", error = null) }
    }

    suspend fun setControlUrl(value: String) = command("控制服务器保存失败，请检查地址；旧配置仍保留") {
        val next = TailscaleControlServer.normalize(value)
        if (next != mutableTs.value.controlUrl) {
            logoutTs()
            tsStore.write(TailscaleControlServer.STORE_KEY, next)
            mutableTs.update { it.copy(controlUrl = next) }
        }
    }

    suspend fun useDirect() = command("旧连接未能停止，未启用直连") {
        router.update(mutableMode.value, false)
        stopWg()
        stopTs()
        select(AppNetworkMode.DIRECT)
        router.update(AppNetworkMode.DIRECT, true)
    }

    private fun refreshTs() {
        if (!mutableTs.value.engineActive) return
        val status = Json.parseToJsonElement(checkNotNull(client).tailscaleStatusJSON()).jsonObject
        mutableTs.update { TailscaleMessages.status(it.copy(error = null), status) }
        if (mutableMode.value == AppNetworkMode.TAILSCALE) {
            router.update(AppNetworkMode.TAILSCALE, mutableTs.value.connected, nativeSessionID)
        }
    }

    suspend fun refresh() = command("无法读取应用内网络状态") { refreshTs() }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: AppNetworkEngine? = null
        fun get(context: Context): AppNetworkEngine = instance ?: synchronized(this) {
            instance ?: AppNetworkEngine(context.applicationContext).also { instance = it }
        }
    }
}

data class WireGuardState(
    val configured: Boolean = false,
    val busy: Boolean = false,
    val up: Boolean = false,
    val error: String? = null,
)
