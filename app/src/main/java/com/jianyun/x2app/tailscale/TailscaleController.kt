package com.jianyun.x2app.tailscale

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import libtailscale.Libtailscale
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Process singleton. All commands return acceptance/errors; connected comes only from engine + TUN. */
class TailscaleController private constructor(private val context: Context) {
    private val mutableState = MutableStateFlow(TailscaleState())
    val state: StateFlow<TailscaleState> = mutableState.asStateFlow()
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Mutex()
    private val startGeneration = AtomicLong()
    private val authorizationIntent = TailscaleAuthorizationIntent()
    private val services = MutableStateFlow<TailscaleVpnService?>(null)
    private val serviceLock = Any()
    private val store = TailscaleStore(context)
    internal val platform = TailscalePlatform(context, store)
    private var engine: libtailscale.Application? = null
    private var notifications: libtailscale.NotificationManager? = null
    @Volatile internal var initialized = false
        private set

    fun prepareVpn(): Intent? = VpnService.prepare(context)

    suspend fun login(): Result<Unit> = command {
        require(prepareVpn() == null) { "请先允许系统 VPN 授权" }
        withStartedService { service ->
            setAuthorizationIntent(true)
            initialize()
            mutableState.update { it.copy(authUrl = null) }
            val prefs = applyControlServer(state.value.controlUrl)
            val updated = JsonObject(prefs + ("WantRunning" to JsonPrimitive(true)))
            call("POST", "start", buildJsonObject { put("UpdatePrefs", updated) })
            call("POST", "login-interactive")
            requestVpnIfReady(service)
        }
    }

    suspend fun connect(): Result<Unit> = command {
        require(prepareVpn() == null) { "请先允许系统 VPN 授权" }
        withStartedService { service ->
            setAuthorizationIntent(true)
            initialize()
            applyControlServer(state.value.controlUrl)
            wantRunning(true)
            refreshStatus()
            requestVpnIfReady(service)
        }
    }

    /** A successful result guarantees the native disconnect barrier and Android service destruction. */
    suspend fun disconnect(): Result<Unit> = command { disconnectInternal() }

    suspend fun logout(): Result<Unit> = command {
        disconnectInternal()
        call("POST", "logout")
        mutableState.update { it.copy(authUrl = null, addresses = emptyList()) }
        refreshStatus()
    }

    suspend fun refresh(): Result<Unit> = command { initialize(); refreshStatus() }

    /** Changing control planes logs out the old identity before persisting the new choice. */
    suspend fun setControlUrl(value: String): Result<Unit> = command {
        val next = TailscaleControlServer.normalize(value)
        initialize()
        if (next != state.value.controlUrl) {
            disconnectInternal()
            call("POST", "logout")
            mutableState.update { it.copy(addresses = emptyList()) }
            applyControlServer(next)
            store.write(TailscaleControlServer.STORE_KEY, next)
            mutableState.update { it.copy(controlUrl = next, authUrl = null) }
            refreshStatus()
        }
    }

    private suspend fun disconnectInternal() {
        setAuthorizationIntent(false)
        startGeneration.incrementAndGet()
        var failure: Exception? = null
        try {
            // A cold process can still have WantRunning=true on disk. Clear it before
            // releasing the command lock so a queued START_STICKY restore cannot reconnect.
            initialize()
            wantRunning(false)
        } catch (e: Exception) { failure = e }
        services.value?.let { service ->
            withContext(NonCancellable) {
                service.shutdown()
                withTimeout(15_000) { services.first { it !== service } }
            }
        }
        mutableState.update { it.copy(vpnActive = false, authUrl = null) }
        failure?.let { throw it }
    }

    private suspend fun command(block: suspend () -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        commands.withLock {
            mutableState.update { it.copy(busy = true, error = null) }
            try {
                block()
                Result.success(Unit)
            } catch (e: CancellationException) {
                if (e !is TimeoutCancellationException || !currentCoroutineContext().isActive) throw e
                val error = IllegalStateException("等待 Tailscale VPN 服务超时，请重试", e)
                failure(error)
                Result.failure(error)
            } catch (e: Exception) {
                failure(e)
                Result.failure(e)
            } catch (e: LinkageError) {
                services.value?.let { service -> withContext(NonCancellable) { service.shutdown() } }
                val error = IllegalStateException("此安装包的 Tailscale 原生库不可用", e)
                failure(error)
                Result.failure(error)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun initialize() {
        if (initialized) return
        // Read before starting Go: an invalid Keystore entry must not silently create a new identity.
        store.keys()
        val controlUrl = TailscaleControlServer.normalize(store.read(TailscaleControlServer.STORE_KEY))
        mutableState.update { it.copy(controlUrl = controlUrl) }
        go.Seq.setContext(context)
        val dir = File(context.noBackupFilesDir, "tailscale").apply { check(isDirectory || mkdirs()) }
        val app = engine ?: Libtailscale.start(dir.absolutePath, dir.absolutePath, false, platform).also { engine = it }
        platform.start()
        notifications = app.watchNotifications(2L or 16L or 128L) { bytes ->
            try {
                val message = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                applyEngineState { TailscaleMessages.notification(it, message) }
                if ((message["State"] as? JsonPrimitive)?.intOrNull in listOf(5, 6)) {
                    // Never call back into the native backend synchronously from its notifier.
                    scope.launch {
                        commands.withLock {
                            services.value?.let { service ->
                                try { requestVpnIfReady(service) }
                                catch (e: Exception) { failure(e); service.shutdown() }
                            }
                        }
                    }
                }
            } catch (e: Exception) { failure(IllegalStateException("无法读取 Tailscale 引擎状态", e)) }
        }
        initialized = true
        refreshStatus()
    }

    private suspend fun withStartedService(block: suspend (TailscaleVpnService) -> Unit) {
        val generation = startGeneration.incrementAndGet()
        try {
            val service = startService(generation)
            currentCoroutineContext().ensureActive()
            block(service)
            currentCoroutineContext().ensureActive()
        } catch (error: Throwable) {
            setAuthorizationIntent(false)
            // Revoke admission before cleanup: an Android START delivered later must not attach.
            startGeneration.compareAndSet(generation, generation + 1)
            withContext(NonCancellable) {
                try {
                    initialize()
                    wantRunning(false)
                } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                try {
                    val service = withContext(Dispatchers.Main.immediate) {
                        context.stopService(Intent(context, TailscaleVpnService::class.java))
                        // onStartCommand runs on Main too: include any attachment that raced
                        // with invalidation before waiting for native/Android destruction.
                        services.value
                    }
                    if (service != null) {
                        service.shutdown()
                        withTimeout(15_000) { services.first { it !== service } }
                    }
                } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    internal fun acceptsStart(generation: Long): Boolean = generation != 0L && startGeneration.get() == generation
    internal fun ownsService(service: TailscaleVpnService): Boolean = services.value === service

    private suspend fun startService(generation: Long): TailscaleVpnService {

        services.value?.let { service ->
            if (!service.isClosing) return service
            withTimeout(15_000) { services.first { it !== service } }
        }
        withContext(Dispatchers.Main.immediate) {
            ContextCompat.startForegroundService(context, Intent(context, TailscaleVpnService::class.java).setAction(TailscaleVpnService.START).putExtra(TailscaleVpnService.GENERATION, generation))
        }
        return withTimeout(15_000) { services.filterNotNull().first() }
    }

    private fun wantRunning(want: Boolean) {
        val prefs = call("PATCH", "prefs", buildJsonObject { put("WantRunning", want); put("WantRunningSet", true) }).jsonObject
        check((prefs["WantRunning"] as? JsonPrimitive)?.booleanOrNull == want) {
            "Tailscale 引擎未确认连接意图变更"
        }
    }

    private fun applyControlServer(url: String): JsonObject {
        val prefs = call("PATCH", "prefs", buildJsonObject {
            put("ControlURL", url); put("ControlURLSet", true)
            put("CorpDNS", true); put("CorpDNSSet", true)
        }).jsonObject
        check(TailscaleControlServer.normalize((prefs["ControlURL"] as? JsonPrimitive)?.content.orEmpty()) == url) {
            "Tailscale 引擎未确认控制服务器变更"
        }
        return prefs
    }

    private fun requestVpnIfReady(service: TailscaleVpnService) {
        // Before authentication, keep the foreground service only. Registering a VPN early
        // lets login's empty route configuration reach Builder.establish without an address.
        if (!state.value.readyForVpn || service.isClosing || !ownsService(service)) return
        check(prepareVpn() == null) { "系统 VPN 授权已撤销，请重新授权后连接" }
        Libtailscale.requestVPN(service)
    }

    private fun refreshStatus() {
        val json = call("GET", "status?peers=false").jsonObject
        applyEngineState { TailscaleMessages.status(it, json) }
    }

    private fun setAuthorizationIntent(active: Boolean) = authorizationIntent.set(active) {
        mutableState.update { it.copy(authUrl = null) }
    }

    private fun applyEngineState(transform: (TailscaleState) -> TailscaleState) {
        authorizationIntent.apply({ update -> mutableState.update(update) }, transform)
    }

    private fun call(method: String, endpoint: String, body: JsonObject? = null): JsonElement {
        val data = body?.toString()?.toByteArray()?.inputStream()
        val input = data?.let { stream -> object : libtailscale.InputStream {
            override fun read(): ByteArray? {
                val buffer = ByteArray(4096)
                val size = stream.read(buffer)
                return if (size < 0) null else buffer.copyOf(size)
            }
            override fun close() = stream.close()
        } }
        val response = checkNotNull(engine).callLocalAPI(15_000, method, "/localapi/v0/$endpoint", input)
        val bytes = response.bodyBytes() ?: byteArrayOf()
        check(response.statusCode() in 200L..299L) { "Tailscale $endpoint 失败（${response.statusCode()}）" }
        return if (bytes.isEmpty() || method == "POST") JsonNull else Json.parseToJsonElement(bytes.decodeToString())
    }

    internal fun attached(service: TailscaleVpnService) = synchronized(serviceLock) {
        mutableState.update { it.copy(serviceActive = true) }
        services.value = service
    }
    internal fun detached(service: TailscaleVpnService) {
        synchronized(serviceLock) {
            if (services.compareAndSet(service, null)) {
                mutableState.update { it.copy(serviceActive = false, vpnActive = false) }
            }
        }
    }
    internal fun vpnActive(active: Boolean) { mutableState.update { it.copy(vpnActive = active) } }
    internal fun failure(error: Exception) { mutableState.update { it.copy(error = error.message ?: "Tailscale 操作失败") } }

    internal fun restore(service: TailscaleVpnService) {
        scope.launch {
            command {
                require(prepareVpn() == null) { "系统 VPN 授权已撤销" }
                initialize()
                val prefs = call("GET", "prefs").jsonObject
                if ((prefs["WantRunning"] as? JsonPrimitive)?.booleanOrNull == true) {
                    check(TailscaleControlServer.normalize((prefs["ControlURL"] as? JsonPrimitive)?.content.orEmpty()) == state.value.controlUrl) {
                        "控制服务器与保存的选择不一致，请重新连接"
                    }
                    requestVpnIfReady(service)
                } else service.shutdown()
            }.onFailure { service.shutdown() }
        }
    }

    internal fun revoked(service: TailscaleVpnService) {
        setAuthorizationIntent(false)
        scope.launch {
            command {
                try { initialize(); wantRunning(false) } finally { service.shutdown() }
            }
            failure(IllegalStateException("系统 VPN 授权已撤销，请重新授权后连接"))
        }
    }

    companion object {
        @Volatile private var instance: TailscaleController? = null
        fun get(context: Context): TailscaleController = instance ?: synchronized(this) {
            instance ?: TailscaleController(context.applicationContext).also { instance = it }
        }
    }
}
