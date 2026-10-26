package com.jianyun.x2app.data

import java.net.URI

enum class UpdateChannel(val label: String) {
    STABLE("稳定版"), BETA("Beta");

    companion object {
        fun restored(value: String?): UpdateChannel = entries.firstOrNull { it.name == value } ?: STABLE
    }
}

enum class UpdateMirror(val label: String, val prefix: String?) {
    OFFICIAL("GitHub 官方", ""),
    GH_PROXY("gh-proxy.com", "https://gh-proxy.com"),
    GH_PROXY_ORG("gh-proxy.org", "https://gh-proxy.org"),
    GH_PROXY_HK("hk.gh-proxy.org", "https://hk.gh-proxy.org"),
    CUSTOM("自定义", null);

    companion object {
        fun forPrefix(value: String): UpdateMirror = entries.firstOrNull {
            it.prefix == value.trim().trimEnd('/')
        } ?: CUSTOM
    }
}

data class UpdatePreferences(
    val channel: UpdateChannel = UpdateChannel.STABLE,
    val proxyPrefix: String = "",
)

fun normalizedUpdateProxy(value: String): String {
    val input = value.trim()
    if (input.isEmpty()) return ""
    val uri = try { URI(input) } catch (_: Exception) {
        throw IllegalArgumentException("GitHub 加速地址格式无效")
    }
    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) && !uri.rawAuthority.endsWith(":")) {
        "加速地址须为 HTTPS 前缀，不要包含账号、查询参数或片段"
    }
    return "https" + input.substring(input.indexOf(':')).trimEnd('/')
}

fun acceleratedGitHubUrl(url: String, proxyPrefix: String): String =
    if (proxyPrefix.isEmpty()) url else "${normalizedUpdateProxy(proxyPrefix)}/$url"
