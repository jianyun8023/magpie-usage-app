package com.jianyun.x2app.tailscale

import java.net.URI
import java.util.Locale

internal object TailscaleControlServer {
    const val DEFAULT = "https://controlplane.tailscale.com"
    const val STORE_KEY = "x2-control-url"

    fun normalize(value: String): String {
        val input = value.trim().ifEmpty { DEFAULT }
        val uri = runCatching { URI(input) }.getOrNull()
        require(uri != null && !uri.isOpaque && !uri.host.isNullOrBlank() && uri.userInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.orEmpty() in listOf("", "/") &&
            (uri.port == -1 || uri.port in 1..65535)) {
            "控制服务器必须是 HTTP/HTTPS 根地址，不能包含账号、路径、查询或片段"
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "控制服务器仅支持 HTTP/HTTPS" }
        val port = uri.port.takeUnless { it == defaultPort(scheme) } ?: -1
        return URI(scheme, null, uri.host.lowercase(Locale.ROOT), port, null, null, null).toASCIIString()
    }

    fun authorizationUrl(value: String?, controlUrl: String): String? = value?.takeIf { raw ->
        runCatching {
            val target = URI(raw)
            val scheme = target.scheme?.lowercase(Locale.ROOT)
            if (target.isOpaque || target.host.isNullOrBlank() || target.userInfo != null ||
                (target.port != -1 && target.port !in 1..65535)) return@runCatching false
            if (scheme == "https") return@runCatching true
            val control = URI(normalize(controlUrl))
            scheme == "http" && control.scheme == "http" &&
                target.host.equals(control.host, ignoreCase = true) && effectivePort(target) == effectivePort(control)
        }.getOrDefault(false)
    }

    private fun defaultPort(scheme: String?) = if (scheme == "https") 443 else 80
    private fun effectivePort(uri: URI) = if (uri.port == -1) defaultPort(uri.scheme?.lowercase(Locale.ROOT)) else uri.port
}
