package com.jianyun.x2app.vpn

import com.wireguard.config.Config
import java.io.InputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

internal const val MAX_CONFIG_BYTES = 64 * 1024

// Never surface parser exceptions: they may contain the offending private key.
internal fun parseVpnConfig(bytes: ByteArray): Config {
    require(bytes.size <= MAX_CONFIG_BYTES)
    val config = bytes.inputStream().use { Config.parse(it) }
    require(config.`interface`.addresses.isNotEmpty())
    require(config.peers.isNotEmpty())
    require(config.peers.all { it.allowedIps.isNotEmpty() && it.endpoint.isPresent })
    return config
}

internal class WireGuardParameters(val uapi: String, val localIPsJSON: String, val dnsIPsJSON: String, val mtu: Long)

internal fun wireGuardParameters(config: Config): WireGuardParameters {
    // toWgUserspaceString silently omits unresolved endpoints. Do not report such a start as usable.
    check(config.peers.all { it.endpoint.get().resolved.isPresent }) { "WireGuard Endpoint 无法解析" }
    val uapi = config.toWgUserspaceString()
    check(uapi.lineSequence().count { it.startsWith("endpoint=") } == config.peers.size)
    val iface = config.`interface`
    return WireGuardParameters(
        uapi,
        JsonArray(iface.addresses.map { JsonPrimitive(it.address.hostAddress) }).toString(),
        JsonArray(iface.dnsServers.map { JsonPrimitive(it.hostAddress) }).toString(),
        iface.mtu.orElse(1280).toLong(),
    )
}

internal fun readVpnConfig(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4096)
    try {
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_CONFIG_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    } finally { buffer.fill(0) }
}
