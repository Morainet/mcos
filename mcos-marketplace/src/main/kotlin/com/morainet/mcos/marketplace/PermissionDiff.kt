package com.morainet.mcos.marketplace

/**
 * How a permission scope changed between two package versions
 * ([09-marketplace.md §7.2]).
 */
enum class ChangeType {
    /** Same scope, riskTier got more dangerous. */
    RISK_TIER_ESCALATED,

    /** Same scope and riskTier, but the publisher's justification changed. */
    JUSTIFICATION_CHANGED,
}

/**
 * A single changed permission scope ([09-marketplace.md §7.2]).
 */
data class PermissionChange(
    /** The permission scope, `type:name` (e.g. `android:CAMERA`). */
    val scope: String,
    val oldEntry: MarketplacePermissionEntry,
    val newEntry: MarketplacePermissionEntry,
    val changeType: ChangeType,
)

/**
 * Result of comparing the installed version's permissions against a new
 * version's permissions ([09-marketplace.md §7.2]).
 *
 * The diff decides whether an update may proceed silently or must ask for
 * fresh consent:
 * - `added` empty                    → silent update
 * - `added` only `normal` tier       → lightweight prompt
 * - `added`/`changed` has elevated or destructive → full preview, [consentRequired]
 */
data class PermissionDiff(
    /** Scopes present in the new version but not the old. */
    val added: List<MarketplacePermissionEntry>,

    /** Scopes present in the old version but not the new. */
    val removed: List<MarketplacePermissionEntry>,

    /** Same scope with riskTier or justification changed. */
    val changed: List<PermissionChange>,

    /** True if added/changed contains elevated/destructive → fresh consent needed. */
    val consentRequired: Boolean,
) {
    /** True when the update adds no new scopes at all. */
    val isSilent: Boolean get() = added.isEmpty() && changed.isEmpty()
}

/**
 * Compute the permission diff between two package metadata versions
 * (normative algorithm, [09-marketplace.md §7.2]).
 */
fun computePermissionDiff(
    oldMeta: PackageMetadata,
    newMeta: PackageMetadata,
): PermissionDiff {
    fun scopeOf(entry: MarketplacePermissionEntry): String = "${entry.type}:${entry.name}"

    val oldScopes = oldMeta.permissionsPreview.mapTo(mutableSetOf()) { scopeOf(it) }
    val newScopes = newMeta.permissionsPreview.mapTo(mutableSetOf()) { scopeOf(it) }

    val added = newMeta.permissionsPreview.filter { scopeOf(it) !in oldScopes }
    val removed = oldMeta.permissionsPreview.filter { scopeOf(it) !in newScopes }

    val changed = newMeta.permissionsPreview.mapNotNull { newEntry ->
        val oldEntry = oldMeta.permissionsPreview.find { scopeOf(it) == scopeOf(newEntry) }
            ?: return@mapNotNull null
        val riskEscalated = tierRank(oldEntry.riskTier) < tierRank(newEntry.riskTier)
        val tierChanged = oldEntry.riskTier != newEntry.riskTier
        val justificationChanged = oldEntry.justification != newEntry.justification
        if (!tierChanged && !justificationChanged) return@mapNotNull null
        PermissionChange(
            scope = scopeOf(newEntry),
            oldEntry = oldEntry,
            newEntry = newEntry,
            changeType = if (riskEscalated) ChangeType.RISK_TIER_ESCALATED else ChangeType.JUSTIFICATION_CHANGED,
        )
    }

    // Elevated/destructive tiers require fresh consent on add or escalation.
    val consentRequired = added.any { it.riskTier in ElevatedDestructiveTiers } ||
        changed.any { it.changeType == ChangeType.RISK_TIER_ESCALATED }

    return PermissionDiff(added, removed, changed, consentRequired)
}

internal val ElevatedDestructiveTiers = setOf("elevated", "destructive")

/**
 * Risk tier ordering: normal < elevated < destructive. Unknown tiers are
 * ranked above destructive (fail-closed — an unknown tier is never treated
 * as an upgrade of a known tier).
 */
private fun tierRank(tier: String): Int = when (tier) {
    "normal" -> 0
    "elevated" -> 1
    "destructive" -> 2
    else -> 3
}
