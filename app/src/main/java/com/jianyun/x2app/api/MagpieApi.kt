package com.jianyun.x2app.api

import com.jianyun.x2app.data.Ledger
import com.jianyun.x2app.data.QuotaCard
import com.jianyun.x2app.data.UsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// 这个 ROM 上 OkHttp 的 NIO 线程会挂起，平台 HttpURLConnection 实测正常
class MagpieApi(
    private var baseUrl: String,
    private var webKey: String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun updateConfig(baseUrl: String, webKey: String) {
        this.baseUrl = baseUrl.trim().trimEnd('/')
        this.webKey = webKey
    }

    private fun normalizedBaseUrl(): String {
        val value = baseUrl.trim().trimEnd('/')
        val withScheme = if (value.startsWith("http://") || value.startsWith("https://")) value else "http://$value"
        val uri = URI(withScheme)
        require(uri.scheme == "http" || uri.scheme == "https") { "服务器地址只支持 HTTP 或 HTTPS" }
        require(!uri.host.isNullOrBlank()) { "服务器地址格式不正确" }
        return withScheme
    }

    private fun fullUrl(path: String, params: Map<String, String> = emptyMap()): String {
        val query = params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return normalizedBaseUrl() + path + if (query.isEmpty()) "" else "?$query"
    }

    private fun port(): String = try {
        val url = URL(normalizedBaseUrl())
        if (url.port == -1 || url.port == 80) "" else url.port.toString()
    } catch (_: Exception) {
        ""
    }

    private suspend fun get(path: String, params: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val connection = URL(fullUrl(path, params)).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 45000
                connection.setRequestProperty("Cookie", "magpie_web_${port()}=$webKey")
                connection.setRequestProperty("Accept", "application/json")
                val code = connection.responseCode
                val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
                if (code !in 200..299) throw ApiException(code, body.take(300))
                body
            } finally {
                connection.disconnect()
            }
        }

    suspend fun quotas(): List<QuotaCard> = json.decodeFromString(get("/api/usage/quotas"))

    suspend fun requests(
        period: String = "7d",
        limit: Int = 100,
        offset: Int = 0,
        agent: String? = null,
        provider: String? = null,
        failed: Boolean = false,
    ): Ledger {
        val params = buildMap {
            put("period", period)
            put("limit", limit.toString())
            put("offset", offset.toString())
            agent?.let { put("agent", it) }
            provider?.let { put("provider", it) }
            if (failed) put("failed", "1")
        }
        return json.decodeFromString(get("/api/usage/requests", params))
    }

    suspend fun usage(period: String = "7d"): UsageSummary =
        json.decodeFromString(get("/api/usage", mapOf("period" to period)))
}

class ApiException(val code: Int, message: String) : Exception("HTTP $code: $message")

fun Throwable.friendlyMessage(): String = when (this) {
    is ApiException -> when (code) {
        401, 403 -> "鉴权失败，请检查 MAGPIE_WEB_KEY"
        404 -> "接口不存在，请检查服务器地址和 Magpie 版本"
        else -> "服务器返回 HTTP $code"
    }
    is UnknownHostException -> "无法解析服务器地址，请检查 IP 或域名"
    is SocketTimeoutException -> "连接或读取超时，请检查地址、端口和网络"
    is ConnectException -> "无法连接服务器，请检查地址、端口和服务状态"
    is SSLException -> "HTTPS 连接失败，请检查证书和地址"
    is IllegalArgumentException -> message ?: "服务器地址格式不正确"
    else -> "连接失败：${message ?: javaClass.simpleName}"
}
