package com.jianyun.x2app.api

import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val statusCode: Int, val body: String)

fun interface HttpTransport {
    suspend fun request(url: String, authorization: String, method: String): HttpResponse
}

/** Installed before the UI/ViewModel is created; tests can inject a transport into MagpieApi. */
object MagpieTransport {
    @Volatile var current: HttpTransport = DirectHttpTransport
}

// This ROM hangs on OkHttp's NIO path. Keep the tested platform transport for explicit direct mode.
object DirectHttpTransport : HttpTransport {
    override suspend fun request(url: String, authorization: String, method: String): HttpResponse {
        require(method == "GET" || method == "POST")
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 45000
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }
}
