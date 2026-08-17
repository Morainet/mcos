package com.mcos.runtime.permission

import com.mcos.runtime.security.EnterprisePolicy
import com.mcos.sdk.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Result of [PermissionKernel.authorize].
 */
sealed class AuthorizationResult {
    /** All permissions granted, command can execute. */
    data class Authorized(val stamp: AuthStamp) : AuthorizationResult()

    /** User must confirm before execution (sideEffectClass ≥ write, or policy requires it). */
    data class ConfirmationNeeded(
        val commandId: String,
        val reason: String,
        val missingPermissions: List<String>,
        val sideEffectClass: SideEffectClass
    ) : AuthorizationResult()

    /** Required permissions not granted and cannot be auto-granted. */
    data class Denied(
        val commandId: String,
        val missingPermissions: List<String>,
        val reason: String
    ) : AuthorizationResult()
}

/**
 * Permission Kernel — enforces command authorization before execution.
 *
 * Implements MCOS Runtime spec [03-runtime.md 7], [08-security.md].
 *
 * The kernel is an interface so hosts and tests can substitute behaviour
 * explicitly. Production uses [DefaultPermissionKernel]; tests that need
 * authorization switched off use the named [PermissivePermissionKernel]
 * — never `null` (fail-open is not an option on this path).
 */
interface PermissionKernel {

    /**
     * Authorization stamp lifetime in milliseconds.
     *
     * This is **independent of per-command [CommandDescriptor.timeoutMs]**:
     * `timeoutMs` governs how long a single command may execute (enforced
     * by `Executor` via `withTimeout` + `ExecutionContext.deadline`),
     * while `authStampTtlMs` governs how long a minted [AuthStamp] remains
     * valid. Per [08-security.md §5.2 rule #4](../../../docs/en/08-security.md),
     * stamps are "run-scoped, short-lived" — they must cover the entire
     * multi-step run, not just one command's timeout.
     *
     * The default (5 min) is derived from the background-event confirmation
     * timeout in [08-security.md §6.3].
     */
    var authStampTtlMs: Long

    /**
     * Check whether a command can be executed.
     *
     * Flow:
     * 1. Enterprise policy command lists (deny-list wins, then non-empty
     *    allow-list is an upper bound) — spec [08-security.md 13.2]
     * 2. Collect all required permissions (descriptor + plugin-level)
     * 3. Check which are missing from grants
     * 4. If sideEffectClass ≥ write → confirmation required
     * 5. If auto-approve → skip confirmation (unless destructive, or the
     *    enterprise force-confirm list upgrades it — spec [08-security.md 4.3])
     *
     * @param descriptor The resolved command descriptor.
     * @param enterprisePolicy Optional enterprise policy; `null` skips the
     *        enterprise command-list and force-confirm checks.
     * @return [AuthorizationResult.Authorized] if all checks pass,
     *         [AuthorizationResult.ConfirmationNeeded] if user confirmation is needed,
     *         [AuthorizationResult.Denied] if permissions are permanently missing.
     */
    fun authorize(
        descriptor: CommandDescriptor,
        enterprisePolicy: EnterprisePolicy? = null,
    ): AuthorizationResult

    // ─── Grant management ─────────────────────────────────────────────────

    /** Grant a permission to a plugin. Persisted across sessions. */
    fun grant(pluginId: String, permission: String)

    /** Grant a permission for the current session only. */
    fun grantSession(pluginId: String, permission: String)

    /** Revoke a permission from a plugin. */
    fun revoke(pluginId: String, permission: String)

    /** Revoke all permissions for a plugin. */
    fun revokeAll(pluginId: String)

    /**
     * Mark a command as auto-approved — skip confirmation for future invocations.
     *
     * **Safety invariant** (08-security.md §4.0): `destructive` commands
     * always require `CONFIRM_ONCE` — auto-approve is rejected for them.
     */
    fun setAutoApprove(
        commandId: String,
        enabled: Boolean,
        sideEffectClass: SideEffectClass? = null
    )

    /**
     * Set global confirmation policy.
     * When true, ALL commands require confirmation regardless of sideEffectClass.
     */
    fun setAlwaysConfirm(enabled: Boolean)

    // ─── Query ────────────────────────────────────────────────────────────

    /** Check if a specific permission is granted to a plugin. */
    fun hasPermission(pluginId: String, permission: String): Boolean

    /** Get all granted permissions for a plugin. */
    fun getGrants(pluginId: String): Set<String>

    /** Check if a permission is session-scoped. */
    fun isSessionGrant(pluginId: String, permission: String): Boolean

    /** Clear session grants (e.g. on app restart). */
    fun clearSessionGrants()

    /** Clear all grants and auto-approve flags (for testing). */
    fun clearAll()

    companion object {
        /** Default authorization stamp lifetime: 5 minutes (run-scoped). */
        const val DEFAULT_AUTH_TTL_MS = 300_000L // 5 min
    }
}

/**
 * Production [PermissionKernel] — in-memory grant cache with
 * sideEffectClass-based confirmation policy and AuthStamp issuance.
 *
 * MVP features:
 * - In-memory grant cache (persisted by pluginId + permission name)
 * - Session grants (temporary, cleared on restart)
 * - sideEffectClass-based confirmation policy
 * - AuthStamp issuance for authorized commands
 *
 * Thread safety: all mutable state is guarded by an internal lock
 * (`synchronized(this)`) because the runtime invokes commands on
 * `Dispatchers.Default` concurrently.
 */
class DefaultPermissionKernel : PermissionKernel {

    // ─── Grant storage ───────────────────────────────────────────────────

    /** Granted permissions: pluginId → set of permission names */
    private val grants = ConcurrentHashMap<String, MutableSet<String>>()

    /** Session-scoped grants that expire on restart */
    private val sessionGrants = ConcurrentHashMap.newKeySet<String>()

    /** Commands the user has marked as "always allow" */
    private val autoApprove = ConcurrentHashMap.newKeySet<String>()

    /** Confirmation policy override per plugin */
    @Volatile
    private var alwaysConfirm: Boolean = false

    @Volatile
    override var authStampTtlMs: Long = PermissionKernel.DEFAULT_AUTH_TTL_MS

    // ─── Authorization ────────────────────────────────────────────────────

    override fun authorize(
        descriptor: CommandDescriptor,
        enterprisePolicy: EnterprisePolicy?,
    ): AuthorizationResult {
        val commandId = descriptor.id
        val pluginId = descriptor.pluginId

        // Step 1: Enterprise command lists (spec §13.2). Deny-list wins
        // unconditionally; a non-empty allow-list is an upper bound.
        val policy = enterprisePolicy
        if (policy != null && !policy.commandAllowed(commandId)) {
            return AuthorizationResult.Denied(
                commandId = commandId,
                missingPermissions = emptyList(),
                reason = "Enterprise policy denies command '$commandId'"
            )
        }

        // Collect required permissions (snapshot under lock)
        val required = collectRequiredPermissions(descriptor)

        // Check which are missing
        val missing = if (required.isEmpty()) {
            emptyList()
        } else {
            required.filter { perm -> !hasPermission(pluginId, perm) }
        }

        // If permissions are missing → Denied
        if (missing.isNotEmpty()) {
            return AuthorizationResult.Denied(
                commandId = commandId,
                missingPermissions = missing,
                reason = "Required permissions not granted: ${missing.joinToString(", ")}"
            )
        }

        // Check if confirmation is needed based on sideEffectClass
        if (needsConfirmation(descriptor, commandId, policy)) {
            return AuthorizationResult.ConfirmationNeeded(
                commandId = commandId,
                reason = confirmationReason(descriptor.sideEffectClass, policy),
                missingPermissions = emptyList(),
                sideEffectClass = descriptor.sideEffectClass
            )
        }

        // All checks passed — issue auth stamp.
        // Include both explicit permissions and implicit sideEffectClass
        // scopes in grantsUsed so downstream consumers (e.g.
        // NetworkEgressPolicy) can match them.
        //
        // expiresAt uses authStampTtlMs (NOT descriptor.timeoutMs) because the
        // stamp must remain valid across an entire multi-step run. Using
        // timeoutMs here would cause subsequent commands in executeSequence
        // — which share the same stamp — to be spuriously rejected as expired.
        val now = System.currentTimeMillis()
        val allScopes = required.toSet() + collectImplicitScopes(descriptor)
        return AuthorizationResult.Authorized(
            stamp = AuthStamp(
                runId = "", // will be filled by Executor
                commandId = commandId,
                pluginId = pluginId,
                grantsUsed = allScopes,
                issuedAt = now,
                expiresAt = now + authStampTtlMs
            )
        )
    }

    // ─── Grant management ─────────────────────────────────────────────────

    override fun grant(pluginId: String, permission: String) {
        grants.computeIfAbsent(pluginId) { java.util.Collections.synchronizedSet(mutableSetOf()) }
            .add(permission)
    }

    override fun grantSession(pluginId: String, permission: String) {
        grant(pluginId, permission)
        sessionGrants.add("$pluginId:$permission")
    }

    override fun revoke(pluginId: String, permission: String) {
        grants[pluginId]?.remove(permission)
        sessionGrants.remove("$pluginId:$permission")
    }

    override fun revokeAll(pluginId: String) {
        grants.remove(pluginId)
        sessionGrants.removeAll { it.startsWith("$pluginId:") }
    }

    override fun setAutoApprove(
        commandId: String,
        enabled: Boolean,
        sideEffectClass: SideEffectClass?
    ) {
        if (enabled && sideEffectClass == SideEffectClass.destructive) {
            throw IllegalArgumentException(
                "Cannot auto-approve destructive command '$commandId': " +
                    "spec 08 §4.0 mandates CONFIRM_ONCE for all destructive operations"
            )
        }
        if (enabled) {
            autoApprove.add(commandId.lowercase())
        } else {
            autoApprove.remove(commandId.lowercase())
        }
    }

    override fun setAlwaysConfirm(enabled: Boolean) {
        alwaysConfirm = enabled
    }

    // ─── Query ────────────────────────────────────────────────────────────

    override fun hasPermission(pluginId: String, permission: String): Boolean {
        val pluginGrants = grants[pluginId] ?: return false
        // Synchronize on the per-plugin set for safe reads
        synchronized(pluginGrants) {
            return permission in pluginGrants
        }
    }

    override fun getGrants(pluginId: String): Set<String> {
        val pluginGrants = grants[pluginId] ?: return emptySet()
        return synchronized(pluginGrants) { pluginGrants.toSet() }
    }

    override fun isSessionGrant(pluginId: String, permission: String): Boolean =
        "$pluginId:$permission" in sessionGrants

    override fun clearSessionGrants() {
        val keys = sessionGrants.toList()
        sessionGrants.clear()
        for (key in keys) {
            val idx = key.indexOf(':')
            if (idx > 0) {
                val pid = key.substring(0, idx)
                val perm = key.substring(idx + 1)
                grants[pid]?.remove(perm)
            }
        }
    }

    override fun clearAll() {
        grants.clear()
        sessionGrants.clear()
        autoApprove.clear()
        alwaysConfirm = false
    }

    // ─── Internal ─────────────────────────────────────────────────────────

    private fun collectRequiredPermissions(descriptor: CommandDescriptor): List<String> {
        val perms = mutableSetOf<String>()

        // Add command-level explicit permissions.
        // These are the hard requirements — if not granted, the command
        // is Denied.
        descriptor.permissions.forEach { entry ->
            perms.add(entry.name)
        }

        return perms.toList()
    }

    /**
     * Collect the implicit scope entries that should appear in the issued
     * AuthStamp's `grantsUsed`, based on sideEffectClass. These are NOT
     * hard requirements — they are informational grants that downstream
     * consumers (e.g. NetworkEgressPolicy) use for scope matching.
     */
    private fun collectImplicitScopes(descriptor: CommandDescriptor): Set<String> {
        return when (descriptor.sideEffectClass) {
            SideEffectClass.network -> setOf("network.*")
            SideEffectClass.destructive -> setOf("mcos:destructive")
            SideEffectClass.control -> setOf("mcos:control")
            else -> emptySet()
        }
    }

    private fun needsConfirmation(
        descriptor: CommandDescriptor,
        commandId: String,
        enterprisePolicy: EnterprisePolicy? = null,
    ): Boolean {
        if (alwaysConfirm) return true

        // Enterprise force-confirm (spec 08 §4.3): classes listed in
        // `forceConfirm` always require CONFIRM_ONCE — this upgrades an
        // otherwise auto-approved command, it never downgrades.
        if (enterprisePolicy?.requiresForceConfirm(descriptor.sideEffectClass) == true) return true

        // Destructive commands ALWAYS require confirmation (spec 08 §4.0):
        // "no allow-persistent path". Auto-approve is ignored for them.
        if (descriptor.sideEffectClass >= SideEffectClass.destructive) return true

        if (autoApprove.contains(commandId.lowercase())) return false

        // sideEffectClass ≥ write → confirmation needed
        return descriptor.sideEffectClass >= SideEffectClass.write
    }

    private fun confirmationReason(
        sideEffectClass: SideEffectClass,
        enterprisePolicy: EnterprisePolicy? = null,
    ): String {
        if (enterprisePolicy?.requiresForceConfirm(sideEffectClass) == true) {
            return "Enterprise policy requires confirmation for ${sideEffectClass.name}"
        }
        return when (sideEffectClass) {
            SideEffectClass.write -> "This command may modify data"
            SideEffectClass.destructive -> "This command may delete or permanently alter data"
            SideEffectClass.network -> "This command requires network access"
            SideEffectClass.control -> "This command may control external devices"
            SideEffectClass.read -> "User policy requires confirmation"
        }
    }
}
