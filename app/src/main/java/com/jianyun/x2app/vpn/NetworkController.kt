package com.jianyun.x2app.vpn

import android.content.Context
import com.jianyun.x2app.tailscale.TailscaleController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class NetworkAction { WG_CONNECT, WG_DISCONNECT, TS_LOGIN, TS_CONNECT, TS_DISCONNECT, TS_LOGOUT }
data class NetworkUiState(val busy: Boolean = false, val error: String? = null)

/** Serialize protocol changes and do not start a new VPN until the old service has stopped. */
class NetworkController internal constructor(
    private val stopWg: suspend () -> Result<Unit>,
    private val startWg: suspend () -> Result<Unit>,
    private val stopTs: suspend () -> Result<Unit>,
    private val startTs: suspend () -> Result<Unit>,
    private val loginTs: suspend () -> Result<Unit>,
    private val logoutTs: suspend () -> Result<Unit>,
    private val saveTsControl: suspend (String) -> Result<Unit>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(NetworkUiState())
    val state = mutableState.asStateFlow()

    fun run(action: NetworkAction) { scope.launch { execute(action) } }
    fun setControlUrl(value: String) { scope.launch { executeControlUrl(value) } }
    fun reportError(message: String) { mutableState.update { it.copy(error = message) } }

    internal suspend fun execute(action: NetworkAction) = serialized {
        when (action) {
            NetworkAction.WG_CONNECT -> { stopTs().getOrThrow(); startWg().getOrThrow() }
            NetworkAction.TS_LOGIN -> { stopWg().getOrThrow(); loginTs().getOrThrow() }
            NetworkAction.TS_CONNECT -> { stopWg().getOrThrow(); startTs().getOrThrow() }
            NetworkAction.WG_DISCONNECT -> stopWg().getOrThrow()
            NetworkAction.TS_DISCONNECT -> stopTs().getOrThrow()
            NetworkAction.TS_LOGOUT -> logoutTs().getOrThrow()
        }
    }

    internal suspend fun executeControlUrl(value: String) = serialized { saveTsControl(value).getOrThrow() }

    private suspend fun serialized(operation: suspend () -> Unit) = mutex.withLock {
        mutableState.value = NetworkUiState(busy = true)
        try {
            operation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            mutableState.update { it.copy(error = e.message ?: "网络切换失败，请重试。") }
        } finally {
            mutableState.update { it.copy(busy = false) }
        }
    }

    companion object {
        @Volatile private var instance: NetworkController? = null
        fun get(context: Context): NetworkController = instance ?: synchronized(this) {
            instance ?: run {
                val wg = VpnController.get(context)
                val ts = TailscaleController.get(context)
                NetworkController(wg::disconnectAndWait, wg::connectAndWait, ts::disconnect, ts::connect, ts::login, ts::logout, ts::setControlUrl)
                    .also { instance = it }
            }
        }
    }
}
