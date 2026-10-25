package com.jianyun.x2app.tailscale

import kotlinx.serialization.json.*

data class TailscaleState(
    val backendState: String = "NoState",
    val engineActive: Boolean = false,
    val nativeConnected: Boolean = false,
    val busy: Boolean = false,
    val authUrl: String? = null,
    val addresses: List<String> = emptyList(),
    val error: String? = null,
    val health: List<String> = emptyList(),
    val controlUrl: String = TailscaleControlServer.DEFAULT,
) {
    val connected: Boolean get() = engineActive && nativeConnected && backendState == "Running"
}

internal object TailscaleMessages {
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.strings(key: String) = (get(key) as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    fun status(previous: TailscaleState, value: JsonObject): TailscaleState {
        val backend = value.string("backendState") ?: "NoState"
        val rawUrl = value.string("authUrl")?.takeIf { it.isNotBlank() }
        val authUrl = TailscaleControlServer.authorizationUrl(rawUrl, previous.controlUrl)
        return previous.copy(
            backendState = backend,
            nativeConnected = (value["connected"] as? JsonPrimitive)?.booleanOrNull == true,
            addresses = if (previous.engineActive && backend != "NeedsLogin") value.strings("addresses") else emptyList(),
            authUrl = if (previous.engineActive && backend !in listOf("Running", "Stopped")) authUrl else null,
            health = value.strings("health"),
            error = if (rawUrl != null && authUrl == null) "引擎返回了无效的授权地址" else previous.error,
        )
    }
}
