package com.jianyun.x2app.tailscale

import kotlinx.serialization.json.*

data class TailscaleState(
    val backendState: String = "NoState",
    val vpnActive: Boolean = false,
    val busy: Boolean = false,
    val authUrl: String? = null,
    val addresses: List<String> = emptyList(),
    val error: String? = null,
    val health: List<String> = emptyList(),
    val controlUrl: String = TailscaleControlServer.DEFAULT,
    val serviceActive: Boolean = false,
) {
    val connected: Boolean get() = backendState == "Running" && vpnActive
    internal val readyForVpn: Boolean get() = backendState == "Starting" || backendState == "Running"
}

internal object TailscaleMessages {
    private val states = listOf("NoState", "InUseOtherUser", "NeedsLogin", "NeedsMachineAuth", "Stopped", "Starting", "Running")
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    fun status(previous: TailscaleState, value: JsonObject): TailscaleState {
        val backend = value.string("BackendState") ?: previous.backendState
        return previous.copy(
            backendState = backend,
            addresses = if (backend == "NeedsLogin") emptyList() else value.strings("TailscaleIPs") ?: emptyList(),
            authUrl = if (backend == "Running" || backend == "Stopped") null else TailscaleControlServer.authorizationUrl(value.string("AuthURL"), previous.controlUrl) ?: previous.authUrl,
            health = value.strings("Health") ?: emptyList(),
        )
    }

    fun notification(previous: TailscaleState, value: JsonObject): TailscaleState {
        var next = previous
        (value["State"] as? JsonPrimitive)?.intOrNull?.let { id ->
            next = next.copy(backendState = states.getOrNull(id) ?: "Unknown")
            if (id == 2) next = next.copy(addresses = emptyList())
            if (id == 6 || id == 4) next = next.copy(authUrl = null)
        }
        (value["InitialStatus"] as? JsonObject)?.let { next = status(next, it) }
        value.string("BrowseToURL")?.let { url ->
            val accepted = TailscaleControlServer.authorizationUrl(url, next.controlUrl)
            next = next.copy(authUrl = accepted, error = if (url.isNotEmpty() && accepted == null) "引擎返回了无效的授权地址" else next.error)
        }
        if (value["LoginFinished"] != null && value["LoginFinished"] != JsonNull) next = next.copy(authUrl = null)
        value.string("ErrMessage")?.let { next = next.copy(error = it.takeIf(String::isNotBlank)) }
        val netmap = value["NetMap"] as? JsonObject
        val self = value["SelfChange"] as? JsonObject ?: netmap?.get("SelfNode") as? JsonObject
        self?.strings("Addresses")?.let { next = next.copy(addresses = it.map { ip -> ip.substringBefore('/') }) }
        (value["Health"] as? JsonObject)?.let { health ->
            val warnings = health["Warnings"] as? JsonObject
            next = next.copy(health = warnings?.values?.mapNotNull { (it as? JsonObject)?.string("Text") }.orEmpty())
        }
        return next
    }
}

/** Serializes user intent with asynchronous status/notification updates, including late replies. */
internal class TailscaleAuthorizationIntent {
    private var accepting = false

    @Synchronized fun set(active: Boolean, clearUrl: () -> Unit) {
        accepting = active
        clearUrl()
    }

    @Synchronized fun apply(publish: ((TailscaleState) -> TailscaleState) -> Unit,
                            transform: (TailscaleState) -> TailscaleState) {
        publish { previous ->
            val next = transform(previous)
            if (accepting) next else next.copy(authUrl = null)
        }
    }
}
