package com.jianyun.x2app.vpn

import android.content.Context
import appnet.Appnet
import appnet.InterfaceProvider
import go.Seq
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.NetworkInterface

internal object AndroidNetworkInterfaces : InterfaceProvider {
    override fun interfacesJSON(): String = JsonArray(
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .mapNotNull { nif ->
                runCatching {
                    buildJsonObject {
                        put("name", nif.name)
                        put("index", nif.index)
                        put("mtu", nif.mtu)
                        put("up", nif.isUp)
                        put("broadcast", nif.interfaceAddresses.any { it.broadcast != null })
                        put("loopback", nif.isLoopback)
                        put("pointToPoint", nif.isPointToPoint)
                        put("multicast", nif.supportsMulticast())
                        put(
                            "addrs",
                            JsonArray(nif.interfaceAddresses.mapNotNull { address ->
                                address.address?.hostAddress?.let { ip ->
                                    buildJsonObject {
                                        put("ip", ip)
                                        put("prefixLen", address.networkPrefixLength.toInt())
                                    }
                                }
                            }),
                        )
                    }
                }.getOrNull()
            },
    ).toString()
}

private val initializationLock = Any()
@Volatile private var appnetInitialized = false

internal fun initializeAppnet(context: Context) {
    if (appnetInitialized) return
    synchronized(initializationLock) {
        if (appnetInitialized) return
        Seq.setContext(context.applicationContext)
        Appnet.setInterfaceProvider(AndroidNetworkInterfaces)
        appnetInitialized = true
    }
}
