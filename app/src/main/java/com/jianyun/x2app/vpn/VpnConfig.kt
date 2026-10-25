package com.jianyun.x2app.vpn

import com.wireguard.config.Config
import java.io.InputStream
import java.io.ByteArrayOutputStream

internal const val MAX_CONFIG_BYTES = 64 * 1024

// Never surface parser exceptions: they may contain the offending private key.
internal class VpnRoutingException : IllegalArgumentException()

internal fun parseVpnConfig(bytes: ByteArray, packageName: String = "com.jianyun.x2app"): Config {
    require(bytes.size <= MAX_CONFIG_BYTES)
    val config = bytes.inputStream().use { Config.parse(it) }
    require(config.`interface`.addresses.isNotEmpty())
    require(config.peers.isNotEmpty())
    require(config.peers.all { it.allowedIps.isNotEmpty() && it.endpoint.isPresent })
    val iface = config.`interface`
    if (packageName in iface.excludedApplications ||
        (iface.includedApplications.isNotEmpty() && packageName !in iface.includedApplications)) {
        throw VpnRoutingException()
    }
    return config
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
