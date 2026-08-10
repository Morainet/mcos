package com.mcos.runtime.permission

import com.mcos.sdk.*

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
 * MVP features:
 * - In-memory grant cache (persisted by pluginId + permission name)
 * - Session grants (temporary, cleared on restart)
 * - sideEffectClass-based confirmation policy
 * - AuthStamp issuance for authorized commands
 */
class PermissionKernel {

    // ─── Grant storage ───────────────────────────────────────────────────

    /** Granted permissions: pluginId → set of permission names */
    private val grants = mutableMapOf<String, MutableSet<String>>()

    /** Session-scoped grants that expire on restart */
    private val sessionGrants = mutableSetOf<String>()

    /** Commands the user has marked as "always allow" */
    private val autoApprove = mutableSetOf<String>()

    /** Confirmation policy override per plugin */
    private var alwaysConfirm: Boolean = false

    // ─── Authorization ────────────────────────────────────────────────────

    /**
     * Check whether a command can be executed.
     *
     * Flow:
     * 1. Collect all required permissions (descriptor + plugin-level)
     * 2. Check which are missing from grants
     * 3. If sideEffectClass ≥ write → confirmation required
     * 4. If auto-approve → skip confirmation
     *
     * @param descriptor The resolved command descriptor.
     * @return [AuthorizationResult.Authorized] if all checks pass,
     *         [AuthorizationResult.ConfirmationNeeded] if user confirmation is needed,
     *         [AuthorizationResult.Denied] if permissions are permanently missing.
     */
    fun authorize(descriptor: CommandDescriptor): AuthorizationResult {
        val commandId = descriptor.id
        val pluginId = descriptor.pluginId

        // Collect required permissions
        val required = collectRequiredPermissions(descriptor)

        // Check which are missing
        val missing = if (required.isEmpty()) {
            emptyList()
        } else {
            required.filter { perm ->
                !hasPermission(pluginId, perm)
            }
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
        if (needsConfirmation(descriptor, commandId)) {
            return AuthorizationResult.ConfirmationNeeded(
                commandId = commandId,
                reason = confirmationReason(descriptor.sideEffectClass),
                missingPermissions = emptyList(),
                sideEffectClass = descriptor.sideEffectClass
            )
        }

        // All checks passed — issue auth stamp
        return AuthorizationResult.Authorized(
            stamp = AuthStamp(
                runId = "", // will be filled by Executor
                commandId = commandId,
                pluginId = pluginId,
                grantsUsed = required.toSet(),
                issuedAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + descriptor.timeoutMs
            )
        )
    }

    // ─── Grant management ─────────────────────────────────────────────────

    /**
     * Grant a permission to a plugin. Persisted across sessions.
     */
    fun grant(pluginId: String, permission: String) {
        grants.getOrPut(pluginId) { mutableSetOf() }.add(permission)
    }

    /**
     * Grant a permission for the current session only.
     */
    fun grantSession(pluginId: String, permission: String) {
        grant(pluginId, permission)
        sessionGrants.add("$pluginId:$permission")
    }

    /**
     * Revoke a permission from a plugin.
     */
    fun revoke(pluginId: String, permission: String) {
        grants[pluginId]?.remove(permission)
        sessionGrants.remove("$pluginId:$permission")
    }

    /**
     * Revoke all permissions for a plugin.
     */
    fun revokeAll(pluginId: String) {
        grants.remove(pluginId)
        sessionGrants.removeAll { it.startsWith("$pluginId:") }
    }

    /**
     * Mark a command as auto-approved — skip confirmation for future invocations.
     */
    fun setAutoApprove(commandId: String, enabled: Boolean) {
        if (enabled) autoApprove.add(commandId.lowercase())
        else autoApprove.remove(commandId.lowercase())
    }

    /**
     * Set global confirmation policy.
     * When true, ALL commands require confirmation regardless of sideEffectClass.
     */
    fun setAlwaysConfirm(enabled: Boolean) {
        alwaysConfirm = enabled
    }

    // ─── Query ────────────────────────────────────────────────────────────

    /**
     * Check if a specific permission is granted to a plugin.
     */
    fun hasPermission(pluginId: String, permission: String): Boolean {
        val pluginGrants = grants[pluginId] ?: return false
        return permission in pluginGrants
    }

    /**
     * Get all granted permissions for a plugin.
     */
    fun getGrants(pluginId: String): Set<String> =
        grants[pluginId]?.toSet() ?: emptySet()

    /**
     * Check if a permission is session-scoped.
     */
    fun isSessionGrant(pluginId: String, permission: String): Boolean =
        "$pluginId:$permission" in sessionGrants

    /**
     * Clear session grants (e.g. on app restart).
     */
    fun clearSessionGrants() {
        sessionGrants.forEach { key ->
            val parts = key.split(":", limit = 2)
            if (parts.size == 2) {
                grants[parts[0]]?.remove(parts[1])
            }
        }
        sessionGrants.clear()
    }

    /**
     * Clear all grants and auto-approve flags (for testing).
     */
    fun clearAll() {
        grants.clear()
        sessionGrants.clear()
        autoApprove.clear()
        alwaysConfirm = false
    }

    // ─── Internal ─────────────────────────────────────────────────────────

    private fun collectRequiredPermissions(descriptor: CommandDescriptor): List<String> {
        val perms = mutableSetOf<String>()

        // Add command-level permissions
        descriptor.permissions.forEach { entry ->
            perms.add(entry.name)
        }

        // SideEffectClass-based implicit permissions
        when (descriptor.sideEffectClass) {
            SideEffectClass.network -> perms.add("mcos:network")
            SideEffectClass.destructive -> perms.add("mcos:destructive")
            SideEffectClass.control -> perms.add("mcos:control")
            else -> {} // read, write — no implicit perm needed
        }

        return perms.toList()
    }

    private fun needsConfirmation(descriptor: CommandDescriptor, commandId: String): Boolean {
        if (alwaysConfirm) return true
        if (autoApprove.contains(commandId.lowercase())) return false

        // sideEffectClass ≥ write → confirmation needed
        return descriptor.sideEffectClass >= SideEffectClass.write
    }

    private fun confirmationReason(sideEffectClass: SideEffectClass): String = when (sideEffectClass) {
        SideEffectClass.write -> "This command may modify data"
        SideEffectClass.destructive -> "This command may delete or permanently alter data"
        SideEffectClass.network -> "This command requires network access"
        SideEffectClass.control -> "This command may control external devices"
        SideEffectClass.read -> "User policy requires confirmation"
    }
}
