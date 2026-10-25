package com.jianyun.x2app.api

import com.jianyun.x2app.data.Ledger
import com.jianyun.x2app.data.QuotaCard
import com.jianyun.x2app.data.UsageSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.ZoneId
import javax.net.ssl.SSLException

// 这个 ROM 上 OkHttp 的 NIO 线程会挂起，平台 HttpURLConnection 实测正常
class MagpieApi(
    private var baseUrl: String,
    private var webKey: String,
    private val transport: HttpTransport? = null,
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

    private suspend fun request(path: String, params: Map<String, String> = emptyMap(), method: String = "GET"): String =
        withContext(Dispatchers.IO) {
            val response = (transport ?: MagpieTransport.current)
                .request(fullUrl(path, params), "Bearer $webKey", method)
            if (response.statusCode !in 200..299) throw ApiException(response.statusCode, response.body.take(300))
            response.body
        }

    @Serializable
    private data class QuotaCards(val data: List<QuotaCard>)

    suspend fun quotas(asked: Boolean = false, provider: String? = null, user: String? = null): List<QuotaCard> {
        val params = buildMap {
            provider?.let { put("provider", it) }
            user?.let { put("user", it) }
        }
        val response = if (asked) request("/v1/magpie/quotas/refresh", params, method = "POST")
            else request("/v1/magpie/quotas/cards")
        return json.decodeFromString<QuotaCards>(response).data
    }

    suspend fun requests(
        period: String = "7d",
        limit: Int = 100,
        offset: Int = 0,
        agent: String? = null,
        provider: String? = null,
        failed: Boolean = false,
        timeZone: String = ZoneId.systemDefault().id,
    ): Ledger {
        val params = buildMap {
            put("period", period)
            put("limit", limit.toString())
            put("offset", offset.toString())
            put("tz", timeZone)
            agent?.let { put("agent", it) }
            provider?.let { put("provider", it) }
            if (failed) put("failed", "1")
        }
        return json.decodeFromString(request("/v1/magpie/usage/requests", params))
    }

    suspend fun usage(period: String = "7d", timeZone: String = ZoneId.systemDefault().id): UsageSummary =
        json.decodeFromString(request("/v1/magpie/usage", mapOf("period" to period, "tz" to timeZone)))
}

class ApiException(val code: Int, message: String) : Exception("HTTP $code: $message")

fun Throwable.friendlyMessage(): String = when (this) {
    is ApiException -> when (code) {
        401 -> "鉴权失败，请检查网关 API Key"
        403 -> "网关 API Key 无权读取这些数据，请检查共享与密钥权限"
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
