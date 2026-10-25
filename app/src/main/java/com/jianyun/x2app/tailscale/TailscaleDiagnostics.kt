package com.jianyun.x2app.tailscale

/** Allowlisted diagnostics: arbitrary engine text may contain secrets, even without a key label. */
internal object TailscaleDiagnostics {
    private val signatures = linkedMapOf(
        "VPN_ENGINE_CLOSED" to listOf("Tailscale 引擎关闭了 VPN"),
        "VPN_REVOKED" to listOf("系统 VPN 授权已撤销"),
        "VPN_NOT_PREPARED" to listOf("请先允许系统 VPN 授权", "系统未建立 Tailscale VPN", "VPN not prepared"),
        "VPN_MISSING_ADDRESS" to listOf("At least one address must be specified"),
        "VPN_CLOSING" to listOf("Tailscale 服务正在关闭"),
        "VPN_SERVICE_TIMEOUT" to listOf("等待 Tailscale VPN 服务超时"),
        "VPN_UNDERLYING_NETWORK" to listOf("无法更新 Tailscale 底层网络"),
        "STATE_PARSE" to listOf("无法读取 Tailscale 引擎状态"),
        "STATE_STORAGE" to listOf("无法解密会话", "会话存储已损坏", "state store is unhealthy"),
        "INVALID_AUTH_URL" to listOf("引擎返回了无效的授权地址"),
        "CONTROL_CLIENT_MISSING" to listOf("LocalBackend.assertClient: b.cc == nil"),
        "PERMISSION" to listOf("permission denied", "SecurityException", "INTERACT_ACROSS_USERS"),
        "DNS" to listOf("no such host", "DNS resolution failed"),
        "TLS" to listOf("TLS handshake timeout", "certificate signed by unknown authority", "certificate has expired"),
        "NETWORK_TIMEOUT" to listOf("context deadline exceeded", "i/o timeout", "timed out"),
        "NETWORK_UNREACHABLE" to listOf("network is unreachable", "connection refused", "no route to host"),
    )

    fun error(message: String?): String {
        if (message == null) return "NONE"
        val matches = signatures.mapNotNull { (category, phrases) ->
            phrases.firstOrNull { message.contains(it, ignoreCase = true) }?.let { "$category:$it" }
        }
        // Only emit constants from our allowlist, never any matched substring or raw suffix.
        return matches.joinToString("|").ifEmpty { "UNCLASSIFIED:[message redacted]" }
    }

    fun state(state: TailscaleState): String {
        val backend = state.backendState.takeIf { it in setOf("NoState", "InUseOtherUser", "NeedsLogin", "NeedsMachineAuth", "Stopped", "Starting", "Running") } ?: "Unknown"
        return "backend=$backend serviceActive=${state.serviceActive} vpnActive=${state.vpnActive} connected=${state.connected} " +
            "authPresent=${state.authUrl != null} ipCount=${state.addresses.size} error=${error(state.error)} " +
            "health=${state.health.map(::error)}"
    }
}
