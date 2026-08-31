package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.security.AuthStampSigner
import com.morainet.mcos.security.DomainGlob
import com.morainet.mcos.security.SecretResolver
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.McosException
import com.morainet.mcos.sdk.NetResponse
import com.morainet.mcos.sdk.NetService
import com.morainet.mcos.sdk.SandboxEntry
import com.morainet.mcos.sdk.SandboxFileService
import com.morainet.mcos.sdk.SecureStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Machine-readable denial reasons emitted by [StampScopedNetService]
 * (08-security.md §8.2). All surface as `PERMISSION_DENIED` with the reason
 * carried in `details.reason`; `stamp_scope_mismatch` is the spec-named code
 * for a scope failure, the others distinguish the stamp-validity checks the
 * facade runs before scope matching.
 */
object StampScopeGateReason {
    const val MISSING = "stamp_missing"
    const val SIGNATURE_INVALID = "stamp_signature_invalid"
    const val EXPIRED = "stamp_expired"
    const val SCOPE_MISMATCH = "stamp_scope_mismatch"
    const val INVALID_URL = "invalid_url"
}

/**
 * AuthStamp-scoped [NetService] gate — slice 2 of process isolation
 * ([08-security.md §8.2], confused-deputy defense).
 *
 * ## The hole this closes
 *
 * Stage 6.5 egress checks only the URLs in a command's *arguments*; a handler
 * can otherwise open any connection from inside its own code through the
 * facade (`ctx.services.net`) — a read-class command exfiltrating data, or a
 * network-class command whispering to a domain the user never approved, both
 * invisible to the argument-tree walk. §8.2 closes exactly this: **every
 * privileged facade call carries the run's [AuthStamp], and the facade
 * verifies the stamp before performing the OS call.**
 *
 * Checks per call (the JVM-enforceable §8.2 subset; Binder caller-UID checks
 * are the Android slice):
 * 1. **Stamp presence** — no stamp on the execution context → deny.
 * 2. **Signature** — [signer.verify] must accept the stamp (the runtime
 *    Keystore key in production; only the named
 *    [com.morainet.mcos.security.TrustingAuthStampSigner] waives this).
 * 3. **Expiry** — the stamp must still be inside its TTL.
 * 4. **Scope match** — `grantsUsed` must contain a `network.<pattern>` scope
 *    glob-matching the URL's host (same [DomainGlob] semantics as
 *    [com.morainet.mcos.security.ScopeBasedEgressPolicy], so the two
 *    enforcement points cannot drift). Scope-based, not class-based (§8.2).
 *
 * ## Where it applies
 *
 * The [Executor] wires this around the Stage-4 facade handed to **non-
 * `BUILTIN`** plugins — §8.2 governs calls arriving from plugin processes,
 * and `BUILTIN` plugins are platform code ([08-security.md §7.2]). The
 * Android bound-service host (slice 3) reuses this decorator on the
 * main-process side of the Binder facade, where it becomes the *only* thing
 * standing between an isolated plugin and the network stack.
 *
 * A denial throws [McosException] (`PERMISSION_DENIED`,
 * `details.reason = "stamp_scope_mismatch"` per §8.2), which the Executor
 * maps to a [com.morainet.mcos.sdk.CommandResult.Err] and the Stage-10 audit
 * records with this command's step.
 *
 * **Honest boundary:** in-process (pre-slice-3) a plugin can still bypass the
 * facade entirely with its own HTTP client — the documented MVP limitation
 * (08 §12 egress note). This gate is the structural seam that becomes a real
 * boundary at the process line, exactly like the §5.2 stamp signature.
 *
 * @param delegate the inner facade [NetService] (typically the
 *        `{{secret.*}}`-resolving decorator, §9.2 — the gate sits outermost so
 *        scope is judged before any store read or egress).
 * @param stamp the run-bound, signed stamp from the [ExecutionContext].
 * @param signer the runtime's configured signer, used for re-verification.
 * @param nowMs injectable clock for the expiry check (tests).
 */
class StampScopedNetService(
    private val delegate: NetService,
    private val stamp: AuthStamp?,
    private val signer: AuthStampSigner,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : NetService {

    override suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): NetResponse {
        gate(url)?.let { failure ->
            throw McosException(
                code = McosErrorCode.PERMISSION_DENIED.name,
                message = "NetService call denied by AuthStamp scope gate: ${failure.second}",
                retryable = false,
                details = buildJsonObject {
                    put("reason", JsonPrimitive(failure.first))
                    DomainGlob.extractHost(url)?.let { put("host", JsonPrimitive(it)) }
                }
            )
        }
        return delegate.request(method, url, body, headers)
    }

    /**
     * Run the §8.2 checks. Returns null when the call may proceed, or a
     * `(reason, humanSummary)` pair when it must be denied.
     */
    private fun gate(url: String): Pair<String, String>? {
        val auth = stamp
            ?: return StampScopeGateReason.MISSING to
                "the execution context carries no AuthStamp"
        if (!signer.verify(auth)) {
            return StampScopeGateReason.SIGNATURE_INVALID to
                "stamp signature failed verification"
        }
        if (nowMs() >= auth.expiresAt) {
            return StampScopeGateReason.EXPIRED to
                "stamp expired at ${auth.expiresAt}"
        }
        val networkScopes = auth.grantsUsed.filter { it.startsWith("network.") }
        if (networkScopes.isEmpty()) {
            return StampScopeGateReason.SCOPE_MISMATCH to
                "grantsUsed carries no network.<domain> scope " +
                    "(command '${auth.commandId}' was not granted network egress)"
        }
        val host = DomainGlob.extractHost(url)
            ?: return StampScopeGateReason.INVALID_URL to
                "target is not a usable URL: '$url'"
        val matched = networkScopes.any { scope ->
            DomainGlob.globMatch(host, scope.removePrefix("network."))
        }
        if (!matched) {
            return StampScopeGateReason.SCOPE_MISMATCH to
                "host '$host' matches no granted network scope " +
                    "(${networkScopes.sorted().joinToString(", ")})"
        }
        return null
    }
}

/**
 * [SandboxFileService] decorator that roots every path at
 * `<pluginId>/…` (04-plugin-sdk.md 6.1). Paths returned by
 * [stat]/[list]/[tempFile] are re-expressed plugin-relative so they
 * round-trip through this same view.
 *
 * Public since item 41 (isolation slice 3a): the Android isolated-process
 * facade server composes the **same** decorators the in-process
 * [Executor] hands a plugin, so the two boundaries cannot drift — an
 * isolated plugin sees exactly the Stage-4 view it would see in-process.
 */
class NamespacedSandbox(
    private val delegate: SandboxFileService,
    private val pluginId: String,
) : SandboxFileService {
    init {
        // The plugin id becomes a path segment — it must be a safe one.
        pluginId.split('/').forEach { segment ->
            require(
                segment.isNotBlank() && segment != "." && segment != ".." &&
                    !segment.contains('\\') && !segment.contains('\u0000'),
            ) { "pluginId must be a safe single path segment: $pluginId" }
        }
    }

    private fun scoped(path: String): String = "$pluginId/$path"

    override suspend fun read(path: String): ByteArray? = delegate.read(scoped(path))

    override suspend fun write(path: String, data: ByteArray, append: Boolean) =
        delegate.write(scoped(path), data, append)

    override suspend fun stat(path: String): SandboxEntry? =
        delegate.stat(scoped(path))?.copy(path = path)

    override suspend fun delete(path: String): Boolean = delegate.delete(scoped(path))

    override suspend fun list(dir: String): List<SandboxEntry> {
        // An empty dir lists this plugin's namespace root, not the
        // host-wide sandbox root.
        val scopedDir = if (dir.isBlank()) pluginId else scoped(dir)
        val prefix = "$pluginId/"
        return delegate.list(scopedDir).map { it.copy(path = it.path.removePrefix(prefix)) }
    }

    override suspend fun tempFile(prefix: String, suffix: String): String {
        // The delegate's tempFile would land at the sandbox root,
        // invisible to this plugin's view — reserve a unique name
        // inside the namespace instead.
        val name = "${prefix.ifEmpty { "mcos" }}-${UUID.randomUUID()}$suffix"
        delegate.write(scoped(name), ByteArray(0))
        return name
    }
}

/**
 * [NetService] decorator that resolves `{{secret.<key>}}` templates in
 * request headers and body before delegating. Templates whose key is not
 * present in the plugin-scoped [SecureStore] stay inert — a plugin can
 * only resolve secrets inside its own namespace.
 *
 * Public since item 41 (isolation slice 3a), alongside [NamespacedSandbox]:
 * the isolated facade server layers [StampScopedNetService] over this
 * decorator exactly like the in-process Stage-4 facade.
 */
class SecretResolvingNetService(
    private val delegate: NetService,
    private val store: SecureStore,
) : NetService {
    override suspend fun request(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): NetResponse {
        val resolvedHeaders = headers.mapValues { (_, v) -> SecretResolver.resolve(v) { store.get(it) } }
        val resolvedBody = body?.let { SecretResolver.resolve(it) { store.get(it) } }
        return delegate.request(method, url, resolvedBody, resolvedHeaders)
    }
}
