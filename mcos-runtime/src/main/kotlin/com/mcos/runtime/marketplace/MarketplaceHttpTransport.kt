package com.mcos.runtime.marketplace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Raw HTTP response from a marketplace endpoint.
 */
data class MarketplaceHttpResponse(
    val statusCode: Int,
    val body: String,
)

/**
 * Transport-level failure with a typed marketplace error code.
 *
 * Implementations map platform-specific network exceptions to this type so
 * [MarketplaceIndex] can translate them into typed errors without touching
 * platform-specific classes.
 */
class MarketplaceTransportException(
    val code: String,
    override val message: String,
    val retryable: Boolean = true,
) : Exception(message)

/**
 * Pluggable HTTP transport for marketplace endpoints ([09-marketplace.md §11]).
 *
 * The JVM default is [JdkMarketplaceHttpTransport] backed by
 * `java.net.http.HttpClient`. Android does not ship the `java.net.http`
 * module, so Android builds inject an `HttpURLConnection`-based transport
 * (see `mcos-android` `AndroidMarketplaceHttpTransport`).
 */
interface MarketplaceHttpTransport {
    /**
     * GET a resource and return its raw body.
     *
     * Implementations MUST throw [MarketplaceTransportException] for timeouts;
     * plain [java.net.ConnectException] / [java.io.IOException] may propagate
     * (handled by the caller).
     */
    suspend fun getJson(
        url: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): MarketplaceHttpResponse
}

/**
 * Default [MarketplaceHttpTransport] using the JDK 11+ [HttpClient].
 *
 * On Android this class is never loaded (Android has no `java.net.http`
 * module); the Android module provides its own transport instead.
 */
class JdkMarketplaceHttpTransport : MarketplaceHttpTransport {

    override suspend fun getJson(
        url: String,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long,
    ): MarketplaceHttpResponse = withContext(Dispatchers.IO) {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build()

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .GET()
            .build()

        try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            MarketplaceHttpResponse(response.statusCode(), response.body())
        } catch (e: java.net.http.HttpTimeoutException) {
            // java.net.http-only exception: normalize so the caller never
            // references a class that does not exist on Android.
            throw MarketplaceTransportException("MARKETPLACE_TIMEOUT", "Marketplace request timed out", true)
        }
        // ConnectException / IOException propagate to the caller's catch clauses.
    }
}
