// Android bridge adapted from tailscale/tailscale-android (BSD-3-Clause).
// See THIRD_PARTY_NOTICE.txt in this directory.
package com.jianyun.x2app.tailscale

import android.content.Context
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import kotlinx.serialization.json.*
import libtailscale.Libtailscale
import java.net.NetworkInterface
import java.security.KeyStore
import android.util.Base64

internal class TailscalePlatform(private val context: Context, private val store: TailscaleStore) : libtailscale.AppContext {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private data class Info(val caps: NetworkCapabilities, val links: LinkProperties)
    private val networks = linkedMapOf<Network, Info>()
    @Volatile var network: Network? = null
        private set
    @Volatile private var dns = ""
    @Volatile var onNetwork: ((Network?) -> Unit)? = null
    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = update(network, caps, null)
        override fun onLinkPropertiesChanged(network: Network, links: LinkProperties) = update(network, null, links)
        override fun onLost(network: Network) { synchronized(networks) { networks.remove(network); recompute() } }
    }

    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()
        connectivity.registerNetworkCallback(request, callback)
        registered = true
        connectivity.allNetworks.forEach { n -> update(n, connectivity.getNetworkCapabilities(n), connectivity.getLinkProperties(n)) }
    }

    private fun update(n: Network, caps: NetworkCapabilities?, links: LinkProperties?) = synchronized(networks) {
        val old = networks[n]
        networks[n] = Info(caps ?: old?.caps ?: NetworkCapabilities(), links ?: old?.links ?: LinkProperties())
        recompute()
    }

    private fun recompute() {
        val selected = networks.entries.filter {
            it.value.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
                it.value.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.maxByOrNull {
            (if (it.value.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 4 else 0) +
                (if (it.value.links.dnsServers.isNotEmpty()) 2 else 0) +
                (if (it.value.caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) 1 else 0)
        }
        val old = network
        network = selected?.key
        val links = selected?.value?.links
        dns = links?.let { lp -> lp.dnsServers.joinToString(" ") { it.hostAddress.orEmpty() } + "\n" + lp.domains.orEmpty() }.orEmpty().trim()
        if (old != network) onNetwork?.invoke(network)
        val gateway = links?.routes?.filter { it.isDefaultRoute && it.gateway != null }
            ?.sortedBy { if (it.gateway is java.net.Inet4Address) 0 else 1 }?.firstOrNull()?.gateway?.hostAddress.orEmpty()
        Libtailscale.onGatewayChanged(gateway)
        Libtailscale.onDNSConfigChanged(links?.interfaceName.orEmpty())
    }

    override fun bindSocketToNetwork(fd: Int): Boolean {
        val physical = network ?: return false
        return try { ParcelFileDescriptor.fromFd(fd).use { physical.bindSocket(it.fileDescriptor) }; true } catch (_: Exception) { false }
    }
    override fun getPlatformDNSConfig(): String = dns
    // Native logs can contain authentication links and network identifiers. Keep them out of logcat.
    override fun log(tag: String, logLine: String) = Unit
    override fun encryptToPref(key: String, value: String) = store.write(key, value)
    override fun decryptFromPref(key: String): String = store.read(key)
    override fun getStateStoreKeysJSON(): String = store.keys()
    override fun getOSVersion(): String = Build.VERSION.RELEASE
    override fun getSDKInt(): Long = Build.VERSION.SDK_INT.toLong()
    override fun getDeviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"
    override fun getInstallSource(): String = "x2-app"
    override fun shouldUseGoogleDNSFallback(): Boolean = false
    override fun isChromeOS(): Boolean = context.packageManager.hasSystemFeature("android.hardware.type.pc")
    override fun isClientLoggingEnabled(): Boolean = false
    override fun getSyspolicyStringValue(key: String): String = throw Exception("no such key")
    override fun getSyspolicyBooleanValue(key: String): Boolean = throw Exception("no such key")
    override fun getSyspolicyStringArrayJSONValue(key: String): String = throw Exception("no such key")
    override fun hardwareAttestationKeySupported(): Boolean = false
    override fun hardwareAttestationKeyCreate(): String = throw UnsupportedOperationException("Hardware attestation disabled")
    override fun hardwareAttestationKeyRelease(id: String) = Unit
    override fun hardwareAttestationKeyPublic(id: String): ByteArray = throw UnsupportedOperationException("Hardware attestation disabled")
    override fun hardwareAttestationKeySign(id: String, data: ByteArray): ByteArray = throw UnsupportedOperationException("Hardware attestation disabled")
    override fun hardwareAttestationKeyLoad(id: String): Unit = throw UnsupportedOperationException("Hardware attestation disabled")

    override fun getUserCACertsPEM(): ByteArray {
        val keys = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        return keys.aliases().toList().filter { it.startsWith("user:") }.joinToString("\n") { alias ->
            "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(keys.getCertificate(alias).encoded, Base64.NO_WRAP)
                .chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"
        }.toByteArray()
    }

    override fun getInterfacesAsJson(): String = JsonArray(NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().mapNotNull { nif ->
        try {
            buildJsonObject {
                put("name", nif.name); put("index", nif.index); put("mtu", nif.mtu)
                put("up", nif.isUp); put("broadcast", nif.supportsMulticast()); put("loopback", nif.isLoopback)
                put("pointToPoint", nif.isPointToPoint); put("multicast", nif.supportsMulticast())
                put("addrs", JsonArray(nif.interfaceAddresses.mapNotNull { ia -> ia.address?.hostAddress?.let { host ->
                    buildJsonObject { put("ip", host); put("prefixLen", ia.networkPrefixLength.toInt()) }
                } }))
            }
        } catch (_: Exception) { null }
    }).toString()
}
