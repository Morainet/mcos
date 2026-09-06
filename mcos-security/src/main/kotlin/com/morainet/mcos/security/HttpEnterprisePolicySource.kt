package com.morainet.mcos.security

import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Result of one remote enterprise-policy fetch attempt (spec §13.3 step 1).
 *
 * Transports MUST fold every failure (non-2xx status, DNS error, timeout,
 * malformed reply) into [Failure] with a human-readable [PolicyFetchResult.Failure.reason]
 * — [HttpEnterprisePolicySource] never sees an exception, which keeps its
 * fail-closed logic linear. HTTP status codes ride inside the reason string
 * (e.g. `"HTTP 401"`) so operators can tell an auth error from a dead server.
 */
sealed interface PolicyFetchResult {
    /** The policy document was fetched; [document] is the raw JSON body. */
    data class Success(val document: String) : PolicyFetchResult

    /** The fetch failed before a usable document was obtained. */
    data class Failure(val reason: String) : PolicyFetchResult
}

/**
 * HTTP(S) fetch seam for remote enterprise-policy delivery (spec §13.3).
 *
 * The default JVM implementation is `JdkEnterprisePolicyHttpTransport`
 * (mcos-runtime-core, `java.net.http`). Android has no `java.net.http`
 * module, so Android hosts supply an `HttpURLConnection`-based transport —
 * the same seam split as `SyncBlobTransport` and `LlmHttpTransport`.
 */
fun interface EnterprisePolicyHttpTransport {
    /** Blocking GET of the policy document at [url]. MUST NOT throw. */
    fun fetch(url: String): PolicyFetchResult
}

/**
 * A source that keeps [EnterprisePolicySource.current] hot-reloaded from a
 * remote policy URL (spec §13.3 step 1 & 4) — the `mcos-server` / MDM
 * "management channel" delivery, over HTTP(S).
 *
 * Semantics mirror [FileEnterprisePolicySource] exactly:
 * - The document is re-fetched at most once per [refreshIntervalMs] (the
 *   spec's "periodically, default every 1 hour" cadence is the throttle
 *   interval; the first call always fetches).
 * - Transport failure / non-2xx → serve the last good policy and emit
 *   [PolicyEvent.PolicyFetchFailed]; with no cached policy, serve
 *   [EnterprisePolicy.FAIL_CLOSED] (spec §13.3 step 4 — a device that cannot
 *   reach its policy server must never fall back to "no policy").
 * - Parse/version failure → serve [EnterprisePolicy.FAIL_CLOSED] and emit
 *   [PolicyEvent.PolicyParseFailed] carrying the SHA-256 fingerprint of the
 *   offending document (spec §13.3 step 3).
 * - An unchanged document (same SHA-256) is not re-parsed and does not
 *   re-emit [PolicyEvent.PolicyUpdated]; a changed, valid document emits
 *   [PolicyEvent.PolicyUpdated] with previous/new version and issuer.
 * - After a parse failure the source serves [EnterprisePolicy.FAIL_CLOSED]
 *   until a fetch returns a parseable document again, at which point it
 *   recovers via [PolicyEvent.PolicyUpdated].
 *
 * [current] runs the fetch on the caller's thread (bounded by the transport's
 * own timeouts) and never throws. Configuring the source is the host's job:
 * `McosRuntime.Builder.withEnterprisePolicySource(...)` accepts it like any
 * other [EnterprisePolicySource].
 */
class HttpEnterprisePolicySource(
    private val url: String,
    private val transport: EnterprisePolicyHttpTransport,
    private val refreshIntervalMs: Long = DEFAULT_REFRESH_INTERVAL_MS,
) : EnterprisePolicySource {

    private val listeners = CopyOnWriteArrayList<(PolicyEvent) -> Unit>()

    @Volatile
    private var cached: EnterprisePolicy? = null

    /** SHA-256 hex of the last document that parsed successfully. */
    @Volatile
    private var lastGoodHash: String? = null

    @Volatile
    private var lastCheck: Long = 0L

    /** Last event emitted, for tests and diagnostics. */
    @Volatile
    var lastEvent: PolicyEvent? = null
        private set

    /**
     * Register an event listener. Fired on the caller thread of [current].
     */
    fun addListener(listener: (PolicyEvent) -> Unit) {
        listeners.add(listener)
    }

    override fun current(): EnterprisePolicy {
        val now = System.currentTimeMillis()
        val cachedNow = cached

        // Refresh throttling: inside the window, serve the cached policy
        // without touching the network (spec §13.3 step 1's cadence).
        if (cachedNow != null && lastCheck + refreshIntervalMs > now) {
            return cachedNow
        }
        lastCheck = now

        val result = try {
            transport.fetch(url)
        } catch (e: Exception) {
            // Defensive: the transport contract forbids throwing, but a host
            // transport bug must still fail closed, not propagate.
            PolicyFetchResult.Failure(e.message ?: e.javaClass.simpleName)
        }

        return when (result) {
            is PolicyFetchResult.Success -> handleFetched(cachedNow, result.document)
            is PolicyFetchResult.Failure -> {
                // Fetch failure (spec §13.3 step 4): keep the last good
                // policy; with no cache, the most restrictive policy.
                emit(PolicyEvent.PolicyFetchFailed(result.reason))
                cachedNow ?: EnterprisePolicy.FAIL_CLOSED
            }
        }
    }

    private fun handleFetched(cachedNow: EnterprisePolicy?, document: String): EnterprisePolicy {
        val hash = sha256Hex(document)

        // Same document the source already serves (and it is not FAIL_CLOSED):
        // keep serving the cache — no re-parse, no duplicate PolicyUpdated.
        if (hash == lastGoodHash && cachedNow != null && cachedNow !== EnterprisePolicy.FAIL_CLOSED) {
            return cachedNow
        }

        return try {
            val policy = EnterprisePolicy.parse(document)
            lastGoodHash = hash
            val event = PolicyEvent.PolicyUpdated(
                previousVersion = cachedNow?.version ?: "<none>",
                newVersion = policy.version,
                issuedBy = policy.issuedBy,
            )
            cached = policy
            emit(event)
            policy
        } catch (e: Exception) {
            // Parse failure (spec §13.3 step 3): fail closed. The document
            // fingerprint goes into the event for forensic audit. lastGoodHash
            // is deliberately NOT advanced, so a later fetch of the same good
            // document recovers instead of being mistaken for an unchanged one.
            val event = PolicyEvent.PolicyParseFailed(
                reason = e.message ?: e.javaClass.simpleName,
                documentHash = hash,
            )
            cached = EnterprisePolicy.FAIL_CLOSED
            emit(event)
            EnterprisePolicy.FAIL_CLOSED
        }
    }

    private fun emit(event: PolicyEvent) {
        lastEvent = event
        for (listener in listeners) {
            listener(event)
        }
    }

    private fun sha256Hex(data: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Spec §13.3 step 1: "periodically, default every 1 hour". */
        const val DEFAULT_REFRESH_INTERVAL_MS: Long = 3_600_000L
    }
}
