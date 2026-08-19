package com.morainet.mcos.security

import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.security.audit.RunRecord
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import com.morainet.mcos.security.permission.PermissionKernel
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandDescriptor
import com.morainet.mcos.sdk.SideEffectClass

/**
 * The executor's security posture, wired as one explicit choice.
 *
 * Every component is non-null: disabling a control is a *named* null object
 * (e.g. [PermissivePermissionKernel], [AllowAllEgressPolicy]), never a
 * missing argument. This keeps "security off" greppable and auditable:
 *
 * ```
 *   grep -rn "permissive()" src/        # every security exemption
 *   grep -rn "Permissive\|AllowAll\|Trusting\|Unlimited\|Noop\|DenyAll" src/
 * ```
 *
 * - [defaults] — the production posture: real permission kernel, token-bucket
 *   rate limiter, scope-based egress, HMAC stamps, sliding-window quarantine.
 *   No audit trail (matches the historical Builder default; hosts that want
 *   one pass an [InMemoryAuditLog] or their own sink explicitly).
 * - [permissive] — every control replaced by its named inert counterpart.
 *   Intended for tests and local scaffolding only.
 *
 * Caller-supplied [AuthStamp] validation (expiry + permission coverage) is
 * performed by the Executor **regardless of this configuration** — it is not
 * a kernel decision and cannot be waived here.
 */
data class SecurityConfig(
    val kernel: PermissionKernel,
    val rateLimiter: RateLimiter,
    val egress: NetworkEgressPolicy,
    val signer: AuthStampSigner,
    val quarantine: CrashQuarantine,
    val enterprisePolicy: EnterprisePolicySource,
    val auditLog: AuditLog,
) {
    companion object {
        /** Production posture — every control real. */
        fun defaults(): SecurityConfig = SecurityConfig(
            kernel = DefaultPermissionKernel(),
            rateLimiter = TokenBucketRateLimiter(),
            egress = ScopeBasedEgressPolicy(),
            signer = HmacAuthStampSigner(),
            quarantine = SlidingWindowCrashQuarantine(),
            enterprisePolicy = EnterprisePolicySource.None,
            auditLog = NullAuditLog,
        )

        /**
         * Every control replaced by its named inert counterpart — the explicit
         * "security off" choice. Prefer `.copy(...)` over this for partial
         * exemptions, e.g. `SecurityConfig.permissive().copy(egress = ScopeBasedEgressPolicy())`.
         */
        fun permissive(): SecurityConfig = SecurityConfig(
            kernel = PermissivePermissionKernel(),
            rateLimiter = UnlimitedRateLimiter(),
            egress = AllowAllEgressPolicy,
            signer = TrustingAuthStampSigner(),
            quarantine = NoopCrashQuarantine,
            enterprisePolicy = EnterprisePolicySource.None,
            auditLog = NullAuditLog,
        )
    }
}

// ─── Named null objects ─────────────────────────────────────────────────────
//
// Each class below switches exactly one control off, by name. They exist so
// "disable X" is an explicit, greppable construction site instead of a
// silently-absent `null` argument (fail-open).

/**
 * [PermissionKernel] that never denies: [authorize] always succeeds.
 *
 * The minted stamp covers only the descriptor's *explicit* permissions —
 * implicit sideEffectClass scopes (e.g. `network.*`) are deliberately NOT
 * synthesized, so a real [NetworkEgressPolicy] layered on top still applies
 * its default-deny. Combining this kernel with [AllowAllEgressPolicy] opts
 * out of both axes; see [SecurityConfig.permissive].
 */
class PermissivePermissionKernel : PermissionKernel {

    override var authStampTtlMs: Long = PermissionKernel.DEFAULT_AUTH_TTL_MS

    override fun authorize(
        descriptor: CommandDescriptor,
        enterprisePolicy: EnterprisePolicy?,
    ): com.morainet.mcos.security.permission.AuthorizationResult {
        val now = System.currentTimeMillis()
        return com.morainet.mcos.security.permission.AuthorizationResult.Authorized(
            stamp = AuthStamp(
                runId = "", // filled by Executor when the run binds
                commandId = descriptor.id,
                pluginId = descriptor.pluginId,
                grantsUsed = descriptor.permissions.map { it.name }.toSet(),
                issuedAt = now,
                expiresAt = now + authStampTtlMs,
            )
        )
    }

    override fun grant(pluginId: String, permission: String) { /* inert by design */ }
    override fun grantSession(pluginId: String, permission: String) { /* inert by design */ }
    override fun revoke(pluginId: String, permission: String) { /* inert by design */ }
    override fun revokeAll(pluginId: String) { /* inert by design */ }
    override fun setAutoApprove(commandId: String, enabled: Boolean, sideEffectClass: SideEffectClass?) { /* inert by design */ }
    override fun setAlwaysConfirm(enabled: Boolean) { /* inert by design */ }

    /** Always granted — the defining property of the permissive kernel. */
    override fun hasPermission(pluginId: String, permission: String): Boolean = true

    /** Diagnostics only; the permissive kernel keeps no grant state. */
    override fun getGrants(pluginId: String): Set<String> = emptySet()

    override fun isSessionGrant(pluginId: String, permission: String): Boolean = false
    override fun clearSessionGrants() { /* inert by design */ }
    override fun clearAll() { /* inert by design */ }
}

/** [RateLimiter] that never limits — the named "rate limiting off" choice. */
class UnlimitedRateLimiter : RateLimiter {
    override fun tryConsume(pluginId: String, sideEffectClass: SideEffectClass): RateLimitResult =
        RateLimitResult.Allowed

    override fun getRemainingTokens(pluginId: String, kind: RateLimitKind): Int = Int.MAX_VALUE
    override fun reset() { /* inert by design */ }
}

/** [NetworkEgressPolicy] that allows every URL — the named "egress off" choice. */
object AllowAllEgressPolicy : NetworkEgressPolicy {
    override fun decideEgress(
        url: String,
        authStamp: AuthStamp?,
        globalKillSwitch: Boolean,
        debugMode: Boolean,
        enterprisePolicy: EnterprisePolicy?,
    ): EgressDecision = EgressDecision.Allow
}

/** [NetworkEgressPolicy] that denies every URL — a fail-closed building block. */
object DenyAllEgressPolicy : NetworkEgressPolicy {
    override fun decideEgress(
        url: String,
        authStamp: AuthStamp?,
        globalKillSwitch: Boolean,
        debugMode: Boolean,
        enterprisePolicy: EnterprisePolicy?,
    ): EgressDecision = EgressDecision.Deny(reason = "deny_all", missingDomain = null)
}

/** [AuthStampSigner] that trusts every stamp — the named "signature checks off" choice. */
class TrustingAuthStampSigner : AuthStampSigner {
    override fun sign(stamp: AuthStamp): AuthStamp = stamp
    override fun verify(stamp: AuthStamp): Boolean = true
}

/** [CrashQuarantine] with no isolation — the named "crash-loop quarantine off" choice. */
object NoopCrashQuarantine : CrashQuarantine {
    override fun isQuarantined(pluginId: String): Boolean = false
    override fun quarantineReason(pluginId: String): String? = null
    override fun quarantinedPlugins(): Set<String> = emptySet()

    /** Never crosses the threshold, so the executor never quarantines. */
    override fun recordCrash(pluginId: String, stackTrace: String): Boolean = false

    override fun recordSuccess(pluginId: String) { /* inert by design */ }
    override fun lift(pluginId: String) { /* inert by design */ }
}

/** [AuditLog] that records nothing — the named "audit trail off" choice. */
object NullAuditLog : AuditLog {
    override fun append(record: RunRecord) { /* inert by design */ }
    override suspend fun flush() { /* nothing pending */ }
    override fun start() { /* inert by design */ }
    override fun stop() { /* inert by design */ }
    override fun getRuns(): List<RunRecord> = emptyList()
    override fun getRun(runId: String): RunRecord? = null
    override fun getRecent(limit: Int): List<RunRecord> = emptyList()
    override fun count(): Int = 0
    override fun export(): String = ""
    override fun clear() { /* inert by design */ }
}
