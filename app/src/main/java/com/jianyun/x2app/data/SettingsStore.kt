package com.jianyun.x2app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ServerConfig(
    val baseUrl: String = "",
    val webKey: String = "",
) {
    val isSet: Boolean get() = baseUrl.isNotBlank()
}

class SettingsStore(context: Context) {
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
        prefs.edit()
            .putString(PREF_URL, baseUrl.trim())
            .putString(PREF_KEY, webKey.trim())
            .apply()
        _config.value = ServerConfig(baseUrl.trim(), webKey.trim())
    }

    fun setQuotaIgnored(id: String, ignored: Boolean) {
        val updated = _ignoredQuotaIds.value.toMutableSet().apply {
            if (ignored) add(id) else remove(id)
        }.toSet()
        prefs.edit().putStringSet(PREF_IGNORED_QUOTAS, updated).apply()
        _ignoredQuotaIds.value = updated
    }

    companion object {
        private const val PREF_URL = "base_url"
        private const val PREF_KEY = "web_key"
        private const val PREF_IGNORED_QUOTAS = "ignored_quota_ids"
    }
}
