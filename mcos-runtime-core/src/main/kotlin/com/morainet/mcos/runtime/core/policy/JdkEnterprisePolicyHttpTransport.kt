package com.morainet.mcos.runtime.core.policy

import com.morainet.mcos.security.EnterprisePolicyHttpTransport
import com.morainet.mcos.security.PolicyFetchResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Default JVM [EnterprisePolicyHttpTransport] backed by the JDK 11+
 * `java.net.http.HttpClient` (spec §13.3 step 1 — the `mcos-server` /
 * MDM delivery channel is fetched over HTTP(S)).
 *
 * - 2xx → [PolicyFetchResult.Success] with the raw JSON document.
 * - Any non-2xx status or transport fault → [PolicyFetchResult.Failure] —
 *   never an exception, so [com.morainet.mcos.security.HttpEnterprisePolicySource]
 *   can apply its fail-closed rules linearly.
 * - An optional [token] is sent as `Authorization: Bearer <token>`, matching
 *   `mcos-server`'s mandatory auth (same posture as `JdkSyncBlobTransport`).
 *
 * On Android this class is never loaded (Android has no `java.net.http`
 * module); Android hosts provide an `HttpURLConnection`-based transport
 * instead (same seam split as `SyncBlobTransport` / `LlmHttpTransport`).
 */
class JdkEnterprisePolicyHttpTransport(
    private val token: String? = null,
    private val connectTimeoutMs: Long = 5_000,
    private val requestTimeoutMs: Long = 15_000,
) : EnterprisePolicyHttpTransport {

    // HttpClient is immutable and thread-safe; the source's [current] may be
    // called from any executor thread.
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(connectTimeoutMs))
        .build()

    override fun fetch(url: String): PolicyFetchResult = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMillis(requestTimeoutMs))
            .GET()
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() in 200..299) {
            PolicyFetchResult.Success(response.body())
        } else {
            PolicyFetchResult.Failure("HTTP ${response.statusCode()}")
        }
    } catch (e: Exception) {
        // DNS failure, refused connection, timeout, malformed URL, … — every
        // transport fault is an honest Failure, never a thrown exception.
        PolicyFetchResult.Failure(e.message ?: e.javaClass.simpleName)
    }
}
