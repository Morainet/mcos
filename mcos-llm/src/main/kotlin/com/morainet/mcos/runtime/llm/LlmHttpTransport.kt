package com.morainet.mcos.runtime.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Raw HTTP response from an LLM transport.
 */
data class HttpTransportResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * Transport-level failure with a typed LLM error code.
 *
 * Implementations map platform-specific network exceptions to this type so
 * [OpenAiLlmProvider] can translate them into [LlmResponse.Err] /
 * [ToolCallResponse.Err] without touching platform-specific classes.
 */
class LlmTransportException(
    val code: String,
    override val message: String,
    val retryable: Boolean = true,
) : Exception(message)

/**
 * Pluggable HTTP transport for LLM providers.
 *
 * The JVM default is [JdkLlmHttpTransport] backed by `java.net.http.HttpClient`.
 * Android does not ship the `java.net.http` module, so Android builds inject an
 * `HttpURLConnection`-based transport (see `mcos-android` `AndroidLlmHttpTransport`)
 * via the constructor parameter of [OpenAiLlmProvider].
 */
interface LlmHttpTransport {
    /**
     * POST a JSON body to the given endpoint with an Authorization Bearer header.
     *
     * Implementations MUST throw [LlmTransportException] for timeouts; plain
     * [ConnectException] / [IOException] may propagate (handled by the provider).
     */
    suspend fun postJson(
        url: String,
        apiKey: String,
        body: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): HttpTransportResponse
}

/**
 * Default [LlmHttpTransport] using the JDK 11+ [HttpClient].
 *
 * On Android this class is never loaded (Android has no `java.net.http` module);
 * the Android module provides its own transport instead.
 */
class JdkLlmHttpTransport : LlmHttpTransport {

    override suspend fun postJson(
        url: String,
        apiKey: String,
        body: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): HttpTransportResponse = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            HttpTransportResponse(response.statusCode(), response.body())
        } catch (e: java.net.http.HttpTimeoutException) {
            // java.net.http-only exception: normalize so the provider never
            // references a class that does not exist on Android.
            throw LlmTransportException("LLM_TIMEOUT", "LLM request timed out", true)
        }
        // ConnectException / IOException propagate to the provider's catch clauses.
    }
}
