package com.jianyun.x2app.api

import java.io.ByteArrayOutputStream
import java.io.InputStream
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
            if (authorization.isNotBlank()) connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.readResponseBody().orEmpty()
            return HttpResponse(code, body)
        } finally {
            connection.disconnect()
        }
    }
}

private fun InputStream.readResponseBody(): String = use { stream ->
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = stream.read(buffer)
        if (count < 0) break
        check(bytes.size() + count <= 4 * 1024 * 1024) { "响应数据过大，请稍后重试" }
        bytes.write(buffer, 0, count)
    }
    bytes.toString(Charsets.UTF_8.name())
}
