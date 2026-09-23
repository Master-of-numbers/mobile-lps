package dev.mobilelps.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

class HttpResponse(
    val code: Int,
    val body: ByteArray,
)

/** Minimal blocking HTTP client, replaceable in tests. Callers run it on an IO dispatcher. */
interface HttpClient {
    @Throws(IOException::class)
    fun get(url: String): HttpResponse

    @Throws(IOException::class)
    fun postJson(
        url: String,
        json: String,
    ): HttpResponse
}

/** [HttpClient] on top of [HttpURLConnection], available on both the JVM and Android. */
class UrlConnectionHttpClient(
    private val userAgent: String,
    private val timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) : HttpClient {
    override fun get(url: String): HttpResponse = request(url, "GET", null)

    override fun postJson(
        url: String,
        json: String,
    ): HttpResponse = request(url, "POST", json.toByteArray())

    private fun request(
        url: String,
        method: String,
        body: ByteArray?,
    ): HttpResponse {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.setRequestProperty("User-Agent", userAgent)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in HTTP_OK_RANGE) connection.inputStream else connection.errorStream
            return HttpResponse(code, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 15_000
        val HTTP_OK_RANGE = 200..299
    }
}
