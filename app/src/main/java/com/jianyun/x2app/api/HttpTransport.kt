package com.jianyun.x2app.api

import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val statusCode: Int, val body: String)

fun interface HttpTransport {
    suspend fun get(url: String, cookie: String): HttpResponse
}

/** Installed before the UI/ViewModel is created; tests can inject a transport into MagpieApi. */
object MagpieTransport {
    @Volatile var current: HttpTransport = DirectHttpTransport
}

// This ROM hangs on OkHttp's NIO path. Keep the tested platform transport for explicit direct mode.
object DirectHttpTransport : HttpTransport {
    override suspend fun get(url: String, cookie: String): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 8000
            connection.readTimeout = 45000
            connection.setRequestProperty("Cookie", cookie)
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
