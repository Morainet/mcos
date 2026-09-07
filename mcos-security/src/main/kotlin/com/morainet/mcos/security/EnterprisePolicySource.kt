package com.morainet.mcos.security

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lifecycle event emitted by an [EnterprisePolicySource] (spec §13.3).
 *
 * Consumers (e.g. audit pipeline) subscribe via [EnterprisePolicySource.addListener].
 */
sealed class PolicyEvent {
    /**
     * A new policy document was successfully loaded and is now active.
     */
    data class PolicyUpdated(
        val previousVersion: String,
        val newVersion: String,
        val issuedBy: String,
    ) : PolicyEvent()

    /**
     * The policy document could not be parsed or failed the schema-version
     * check. The source switches to [EnterprisePolicy.FAIL_CLOSED] (spec §13.3
     * step 3). [reason] is human-readable, [documentHash] is a SHA-256 hex of
     * the offending document for forensic audit.
     */
    data class PolicyParseFailed(
        val reason: String,
        val documentHash: String,
    ) : PolicyEvent()

    /**
     * The policy file could not be read from disk. The source keeps serving
     * the last successfully loaded policy (spec §13.3 step 4); if none was
     * ever loaded it serves [EnterprisePolicy.FAIL_CLOSED].
     */
    data class PolicyFetchFailed(
        val reason: String,
    ) : PolicyEvent()
}

/**
 * Supply of the active [EnterprisePolicy].
 *
 * Implementations must never throw from [current] — the fail-closed
 * contract (spec §13.3) requires every error path to resolve to
 * [EnterprisePolicy.FAIL_CLOSED].
 */
fun interface EnterprisePolicySource {
    /** The currently active policy. Never throws; fail-closed on any error. */
    fun current(): EnterprisePolicy

    companion object {
        /**
         * A source that always serves the given fixed [policy]. Convenient for
         * tests and for hosts that manage policy themselves (no hot reload).
         */
        fun fixed(policy: EnterprisePolicy): EnterprisePolicySource = EnterprisePolicySource { policy }

        /**
         * The explicit "no enterprise policy" choice — an inert empty policy.
         *
         * Behaviorally identical to the former `enterprisePolicySource = null`
         * wiring (empty command/network lists, no forced confirmations, no
         * kill switch), but it is a *named* value so opting out of enterprise
         * enforcement is a visible, greppable decision rather than a missing
         * argument.
         */
        val None: EnterprisePolicySource = fixed(EnterprisePolicy())
    }
}

/**
 * A source that keeps [EnterprisePolicySource.current] hot-reloaded from a
 * local policy document (spec §13.3 step 1 & 4).
 *
 * Semantics:
 * - The document is (re)read at most once per [refreshIntervalMs], and only
 *   when its last-modified time changed since the previous load.
 * - Parse/version failure → serve [EnterprisePolicy.FAIL_CLOSED] and emit
 *   [PolicyEvent.PolicyParseFailed] (spec §13.3 step 3).
 * - Read failure → keep serving the last good policy; if none, serve
 *   [EnterprisePolicy.FAIL_CLOSED]. Emit [PolicyEvent.PolicyFetchFailed].
 * - Successful reload → emit [PolicyEvent.PolicyUpdated].
 *
 * The first call to [current] loads the file (or enters fail-closed).
 */
class FileEnterprisePolicySource(
    private val path: Path,
    private val refreshIntervalMs: Long = 60_000,
) : EnterprisePolicySource {

    private val listeners = CopyOnWriteArrayList<(PolicyEvent) -> Unit>()

    @Volatile
    private var cached: EnterprisePolicy? = null

    @Volatile
    private var lastMtime: FileTime? = null

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
        // without touching the file system.
        if (cachedNow != null && lastCheck + refreshIntervalMs > now) {
            return cachedNow
        }
        lastCheck = now

        val mtime = try {
            Files.getLastModifiedTime(path)
        } catch (e: Exception) {
            // File missing/unreadable → fetch failure (spec §13.3 step 4).
            val event = PolicyEvent.PolicyFetchFailed(e.message ?: e.javaClass.simpleName)
            emit(event)
            return cachedNow ?: EnterprisePolicy.FAIL_CLOSED
        }

        // Only reload when the document actually changed.
        if (lastMtime != mtime) {
            lastMtime = mtime
            return loadDocument(mtime)
        }
        return cachedNow ?: loadDocument(mtime)
    }

    private fun loadDocument(mtime: FileTime): EnterprisePolicy {
        val raw = try {
            Files.readString(path)
        } catch (e: Exception) {
            val event = PolicyEvent.PolicyFetchFailed(e.message ?: e.javaClass.simpleName)
            emit(event)
            return cached ?: EnterprisePolicy.FAIL_CLOSED
        }

        return try {
            val policy = EnterprisePolicy.parse(raw)
            val event = PolicyEvent.PolicyUpdated(
                previousVersion = cached?.version ?: "<none>",
                newVersion = policy.version,
                issuedBy = policy.issuedBy,
            )
            cached = policy
            emit(event)
            policy
        } catch (e: Exception) {
            val event = PolicyEvent.PolicyParseFailed(
                reason = e.message ?: e.javaClass.simpleName,
                documentHash = sha256Hex(raw),
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
}

/**
 * Composes a [base] enterprise policy source with a read-through [overlay]
 * ([03-runtime.md §19]). The overlay carries the allow-lists sourced from a
 * live `RuntimeConfig` (its `enterpriseAllowlist` / `networkAllowList`); the
 * merge is **most-restrictive-wins** so the composition can only ever *tighten*
 * what the base already enforces, never loosen it (spec §13.4, mirrored for the
 * §19 overlay):
 *
 * - `allowCommands` / `networkAllow` — when *both* sides are non-empty, the
 *   result is their **intersection** (a command must be permitted by both to
 *   pass); when only one side constrains, that side's list is used; when
 *   neither constrains (both empty), the result is empty (unconstrained).
 * - `denyCommands` / `networkDeny` — **union** (a deny on either side denies).
 * - `forceConfirm` — union (a force-confirm on either side applies).
 * - boolean tightenings (`disableSideload`, `disableCloudMemorySync`,
 *   `auditFailClosed`, `disableAllPluginNetwork`) — OR (either side may set).
 * - `secretTtlDays` — the tighter (smaller) of the two when both set.
 * - `version` / `issuedAt` / `issuedBy` — taken from [base] (the overlay is an
 *   internal §19 projection, not a signed policy document).
 *
 * [current] reads both sides on every call, so it inherits the per-invoke
 * immediacy of the underlying [base] (item 53's hot reload) and of the overlay
 * supplier (the live `RuntimeConfig`). Never throws: an overlay returning null
 * degrades to the base policy unchanged.
 */
class UnionEnterprisePolicySource(
    private val base: EnterprisePolicySource,
    private val overlay: () -> EnterprisePolicy?,
) : EnterprisePolicySource {

    override fun current(): EnterprisePolicy {
        val b = base.current()
        val o = overlay() ?: return b
        return EnterprisePolicy(
            allowCommands = intersectAllow(b.allowCommands, o.allowCommands),
            denyCommands = (b.denyCommands + o.denyCommands).distinct(),
            forceConfirm = (b.forceConfirm + o.forceConfirm).distinct(),
            disableSideload = b.disableSideload || o.disableSideload,
            disableCloudMemorySync = b.disableCloudMemorySync || o.disableCloudMemorySync,
            auditFailClosed = b.auditFailClosed || o.auditFailClosed,
            networkAllow = intersectAllow(b.networkAllow, o.networkAllow),
            networkDeny = (b.networkDeny + o.networkDeny).distinct(),
            disableAllPluginNetwork = b.disableAllPluginNetwork || o.disableAllPluginNetwork,
            secretTtlDays = tighterTtl(b.secretTtlDays, o.secretTtlDays),
            version = b.version,
            issuedAt = b.issuedAt,
            issuedBy = b.issuedBy,
        )
    }

    private companion object {
        /**
         * Most-restrictive intersection of two allow-lists: an empty list means
         * "no constraint from this side", so it yields to the other side; two
         * non-empty lists intersect (both must permit). Two empty lists stay
         * empty (unconstrained).
         */
        fun intersectAllow(a: List<String>, b: List<String>): List<String> = when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            else -> a.filter { it in b }.distinct()
        }

        fun tighterTtl(a: Int?, b: Int?): Int? = when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }
    }
}
