package com.jianyun.x2app.data

import android.content.Context
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ServerConfig(
    val baseUrl: String = "",
    val webKey: String = "",
) {
    val isSet: Boolean get() = baseUrl.isNotBlank()
}

fun normalizedServerConfig(baseUrl: String, webKey: String): ServerConfig {
    val input = baseUrl.trim()
    require(input.isNotEmpty()) { "请填写服务器地址" }
    val url = if ("://" in input) input else "http://$input"
    val uri = try { URI(url) } catch (_: Exception) {
        throw IllegalArgumentException("服务器地址格式无效，请填写 HTTP 或 HTTPS 地址")
    }
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "服务器地址仅支持 HTTP 或 HTTPS"
    }
    require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
        (uri.port == -1 || uri.port in 1..65535) && !uri.rawAuthority.endsWith(":")) {
        "服务器地址无效：请检查主机和端口，不要包含账号、查询参数或片段"
    }
    return ServerConfig(uri.scheme.lowercase() + url.substring(url.indexOf(':')).trimEnd('/'), webKey.trim())
}

class SettingsStore private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("x2app", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(load())
    val config: StateFlow<ServerConfig> = _config

    private val _ignoredQuotaIds = MutableStateFlow(
        prefs.getStringSet(PREF_IGNORED_QUOTAS, emptySet())?.toSet() ?: emptySet()
    )
    val ignoredQuotaIds: StateFlow<Set<String>> = _ignoredQuotaIds

    private fun load(): ServerConfig = ServerConfig(
        baseUrl = prefs.getString(PREF_URL, "") ?: "",
        webKey = prefs.getString(PREF_KEY, "") ?: "",
    )

    fun save(baseUrl: String, webKey: String) {
        val normalized = normalizedServerConfig(baseUrl, webKey)
        prefs.edit()
            .putString(PREF_URL, normalized.baseUrl)
            .putString(PREF_KEY, normalized.webKey)
            .apply()
        _config.value = normalized
    }

    fun setQuotaIgnored(id: String, ignored: Boolean) {
        val updated = _ignoredQuotaIds.value.toMutableSet().apply {
            if (ignored) add(id) else remove(id)
        }.toSet()
        prefs.edit().putStringSet(PREF_IGNORED_QUOTAS, updated).apply()
        _ignoredQuotaIds.value = updated
    }

    companion object {
        @Volatile private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore = instance ?: synchronized(this) {
            instance ?: SettingsStore(context.applicationContext).also { instance = it }
        }

        private const val PREF_URL = "base_url"
        private const val PREF_KEY = "web_key"
        private const val PREF_IGNORED_QUOTAS = "ignored_quota_ids"
    }
}
