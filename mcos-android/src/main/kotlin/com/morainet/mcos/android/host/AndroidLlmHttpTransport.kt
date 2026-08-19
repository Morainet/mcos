package com.morainet.mcos.android.host

import com.morainet.mcos.llm.HttpTransportResponse
import com.morainet.mcos.llm.LlmHttpTransport
import com.morainet.mcos.llm.LlmTransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Android-compatible [LlmHttpTransport] backed by [HttpURLConnection].
 *
 * The JVM default [com.morainet.mcos.llm.JdkLlmHttpTransport] cannot be used on
 * Android because the `java.net.http` module is not part of the Android
 * runtime. This implementation mirrors its behavior:
 * - timeouts surface as [LlmTransportException] with code `LLM_TIMEOUT`;
 * - [java.net.ConnectException] / [IOException] propagate so the provider's
 *   existing catch clauses map them to `LLM_CONNECT_ERROR` / `LLM_NETWORK_ERROR`.
 */
class AndroidLlmHttpTransport : LlmHttpTransport {

    override suspend fun postJson(
        url: String,
        apiKey: String,
        body: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): HttpTransportResponse = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs.toInt()
            conn.readTimeout = requestTimeoutMs.toInt()
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            HttpTransportResponse(statusCode = status, body = text)
        } catch (e: SocketTimeoutException) {
            throw LlmTransportException("LLM_TIMEOUT", "LLM request timed out", true)
        } finally {
            conn.disconnect()
        }
    }
}
