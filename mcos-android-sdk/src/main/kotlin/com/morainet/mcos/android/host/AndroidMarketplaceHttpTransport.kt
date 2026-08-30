package com.morainet.mcos.android.host

import com.morainet.mcos.marketplace.MarketplaceHttpTransport
import com.morainet.mcos.marketplace.MarketplaceHttpResponse
import com.morainet.mcos.marketplace.MarketplaceTransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Android-compatible [MarketplaceHttpTransport] backed by [HttpURLConnection].
 *
 * The JVM default [com.morainet.mcos.marketplace.JdkMarketplaceHttpTransport]
 * cannot be used on Android because the `java.net.http` module is not part of
 * the Android runtime. This implementation mirrors its behavior and implements
 * all three methods — [getBytes] (plugin artifact downloads, 09-marketplace.md
 * §7.1 step 1) and [postJson] (telemetry / user reports, §11.3/§14.1) included,
 * because the interface defaults fail loudly.
 *
 * Exception contract (mirrors [AndroidLlmHttpTransport]):
 * - timeouts surface as [MarketplaceTransportException] with code
 *   `MARKETPLACE_TIMEOUT` (retryable);
 * - [java.net.ConnectException] / [java.io.IOException] propagate so
 *   [com.morainet.mcos.marketplace.MarketplaceIndex] can map them to
 *   `MARKETPLACE_UNREACHABLE` / `MARKETPLACE_IO`.
 */
class AndroidMarketplaceHttpTransport : MarketplaceHttpTransport {

    override suspend fun getJson(
        url: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): MarketplaceHttpResponse = withContext(Dispatchers.IO) {
        val conn = open(url, connectTimeoutMs, requestTimeoutMs)
        try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            readResponse(conn)
        } catch (e: SocketTimeoutException) {
            throw MarketplaceTransportException("MARKETPLACE_TIMEOUT", "Marketplace request timed out", true)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun getBytes(
        url: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): ByteArray = withContext(Dispatchers.IO) {
        val conn = open(url, connectTimeoutMs, requestTimeoutMs)
        try {
            conn.requestMethod = "GET"
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            stream?.use { it.readBytes() }
                ?: throw MarketplaceTransportException(
                    "MARKETPLACE_IO",
                    "Marketplace download returned no body (HTTP $status)",
                    true,
                )
        } catch (e: SocketTimeoutException) {
            throw MarketplaceTransportException("MARKETPLACE_TIMEOUT", "Marketplace download timed out", true)
        } finally {
            conn.disconnect()
        }
    }

    override suspend fun postJson(
        url: String,
        body: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): MarketplaceHttpResponse = withContext(Dispatchers.IO) {
        val conn = open(url, connectTimeoutMs, requestTimeoutMs)
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            readResponse(conn)
        } catch (e: SocketTimeoutException) {
            throw MarketplaceTransportException("MARKETPLACE_TIMEOUT", "Marketplace request timed out", true)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, connectTimeoutMs: Long, requestTimeoutMs: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs.toInt()
        conn.readTimeout = requestTimeoutMs.toInt()
        return conn
    }

    private fun readResponse(conn: HttpURLConnection): MarketplaceHttpResponse {
        val status = conn.responseCode
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return MarketplaceHttpResponse(statusCode = status, body = text)
    }
}
