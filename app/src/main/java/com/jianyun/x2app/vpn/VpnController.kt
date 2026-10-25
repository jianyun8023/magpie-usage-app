package com.jianyun.x2app.vpn

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.VpnService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class VpnUiState(
    val configured: Boolean = false,
    val busy: Boolean = true,
    val up: Boolean = false,
    val error: String? = null,
    val rx: Long = 0,
    val tx: Long = 0,
    val handshake: Long = 0,
    val known: Boolean = false,
    val remotePid: Int = 0,
    val remoteServiceAlive: Boolean = false,
    val foreground: Boolean = false,
)

/** UI/config owner only. Native WireGuard is accessed exclusively through Messenger IPC. */
class VpnController private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val store = VpnConfigStore(context)
    private val mutableState = MutableStateFlow(VpnUiState())
    val state = mutableState.asStateFlow()
    private val ipc = WireGuardIpc(context, ::receiveState) {
        mutableState.update {
            if (it.up || it.foreground || (it.busy && it.remoteServiceAlive)) it.copy(
                known = false, up = false, foreground = false, remoteServiceAlive = false,
                error = "WireGuard 远程进程中断，状态未知；请刷新后重试。",
            ) else it
        }
    }

    init {
        operation("已保存的 VPN 配置无法解密或已损坏，请重新导入。") {
            store.load()?.let { bytes ->
                try {
                    parseVpnConfig(bytes, context.packageName)
                    mutableState.update { it.copy(configured = true) }
                } finally { bytes.fill(0) }
            }
            ipc.request(WgProtocol.STATUS)
        }
    }

    private fun receiveState(snapshot: WireGuardStatus) {
        mutableState.update {
            it.copy(
                known = true, up = snapshot.up, rx = snapshot.rx, tx = snapshot.tx, handshake = snapshot.handshake,
                remotePid = snapshot.pid, remoteServiceAlive = snapshot.serviceAlive, foreground = snapshot.foreground,
                error = snapshot.error ?: if (it.known) it.error else null,
            )
        }
    }

    private fun operation(failure: String, block: suspend () -> Unit) { scope.launch { perform(failure, block) } }
    private suspend fun perform(failure: String, block: suspend () -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                block()
                Result.success(Unit)
            } catch (e: Exception) {
                if (e is CancellationException && (e !is TimeoutCancellationException || !currentCoroutineContext().isActive)) throw e
                val unknown = e is WgTransportException || e is TimeoutCancellationException
                val message = when (e) {
                    is VpnRoutingException -> "配置将本应用排除在 VPN 外，请调整 IncludedApplications / ExcludedApplications 后重新导入。"
                    is WgTransportException, is WgRemoteException -> e.message ?: failure
                    is TimeoutCancellationException -> "等待 WireGuard 服务超时，状态未知；请确认断开后重试。"
                    else -> failure
                }
                mutableState.update { it.copy(error = message, known = if (unknown) false else it.known, up = if (unknown) false else it.up) }
                Result.failure(IllegalStateException(message))
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun importConfig(uri: Uri) = operation("导入失败：请提供有效的 WireGuard .conf（含地址、peer、Endpoint 和 AllowedIPs，最大 64 KB）。") {
        ipc.request(WgProtocol.STATUS)
        check(state.value.known && !state.value.up)
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readVpnConfig) ?: error("No stream")
        try {
            parseVpnConfig(bytes, context.packageName)
            store.save(bytes)
            mutableState.update { it.copy(configured = true) }
        } finally { bytes.fill(0) }
    }

    suspend fun connectAndWait() = perform("WireGuard 连接失败，请检查配置、网络和系统授权。") {
        check(VpnService.prepare(context) == null)
        check(state.value.configured)
        ipc.request(WgProtocol.CONNECT)
    }

    fun disconnect() { scope.launch { disconnectAndWait() } }
    suspend fun disconnectAndWait() = perform("WireGuard 断开未获确认，未切换网络；请重试。") {
        val stopped = ipc.request(WgProtocol.DISCONNECT)
        check(!stopped.up && !stopped.serviceAlive && !stopped.foreground)
    }

    fun delete() = operation("删除失败，请先确认 VPN 已断开后重试。") {
        ipc.request(WgProtocol.STATUS)
        check(state.value.known && !state.value.up)
        store.delete()
        mutableState.update { it.copy(configured = false) }
    }

    suspend fun refresh() = mutex.withLock {
        try {
            val snapshot = ipc.request(WgProtocol.STATUS)
            mutableState.update { it.copy(error = snapshot.error) }
        } catch (e: Exception) {
            if (e is CancellationException && (e !is TimeoutCancellationException || !currentCoroutineContext().isActive)) throw e
            mutableState.update { it.copy(known = false, up = false, error = "无法读取 WireGuard 远程状态，请确认断开后重试。") }
        }
    }

    /** Debug commands are also checked by the remote service; neither loads native code in this process. */
    internal suspend fun debugRemote(command: Int): WireGuardStatus = mutex.withLock { ipc.request(command) }

    companion object {
        @SuppressLint("StaticFieldLeak") // Application context only.
        @Volatile private var instance: VpnController? = null
        fun get(context: Context): VpnController = instance ?: synchronized(this) {
            instance ?: VpnController(context.applicationContext).also { instance = it }
        }
    }
}
