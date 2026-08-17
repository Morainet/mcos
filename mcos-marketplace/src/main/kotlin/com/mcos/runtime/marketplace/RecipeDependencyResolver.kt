package com.mcos.runtime.marketplace

/**
 * A parsed recipe dependency ([09-marketplace.md §7.4]).
 *
 * @param range semver range; `*` when the spec had no `@semverRange` suffix.
 */
data class RecipeDependency(
    val pluginId: String,
    val range: String,
)

/**
 * A dependency that could not be satisfied ([09-marketplace.md §7.4, §8.3]).
 *
 * @param reason `not_in_marketplace` when neither an installed nor a
 *               marketplace version satisfies the range;
 *               `available_in_marketplace` when the marketplace can satisfy it.
 * @param suggestedVersion version to propose when
 *               [reason] is `available_in_marketplace`.
 */
data class MissingDependency(
    val pluginId: String,
    val range: String,
    val reason: String = "",
    val suggestedVersion: String? = null,
)

/**
 * Outcome of [RecipeDependencyResolver.resolve] ([09-marketplace.md §7.4]).
 */
sealed interface RecipeResolveResult {
    /** Every dependency is satisfied by an installed plugin. */
    data object Resolved : RecipeResolveResult

    /** At least one dependency is missing ([09-marketplace.md §8.3] step 1). */
    data class Unresolved(val missing: List<MissingDependency>) : RecipeResolveResult
}

/**
 * A recipe dependency spec could not be interpreted as `pluginId@semverRange`.
 * Maps to the `SCHEMA_VIOLATION` error in [09-marketplace.md §7.4].
 */
class RecipeSchemaException(message: String) : Exception(message)

/**
 * Resolves a recipe's `requiredPlugins` against the local registry and the
 * marketplace ([09-marketplace.md §7.4]).
 */
object RecipeDependencyResolver {

    /**
     * Parses `pluginId@semverRange`; a bare `pluginId` is treated as `*`.
     * Returns null when the plugin id part is empty or blank.
     */
    fun parseDependency(spec: String): RecipeDependency? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return null
        val at = trimmed.indexOf('@')
        return if (at < 0) {
            RecipeDependency(trimmed, "*")
        } else {
            val pluginId = trimmed.substring(0, at).trim()
            if (pluginId.isEmpty()) null else RecipeDependency(pluginId, trimmed.substring(at + 1).trim())
        }
    }

    /**
     * @param installedVersion returns the installed version of a plugin, or null.
     * @param marketplaceLookup returns the marketplace listing for a plugin id
     *                          (latest version), or null when absent.
     * @throws RecipeSchemaException when a dependency spec or range is malformed.
     */
    suspend fun resolve(
        recipe: RecipeEnvelope,
        installedVersion: (String) -> String?,
        marketplaceLookup: suspend (String) -> PackageMetadata?,
    ): RecipeResolveResult {
        val missing = mutableListOf<MissingDependency>()
        for (spec in recipe.requiredPlugins) {
            val dep = parseDependency(spec)
                ?: throw RecipeSchemaException("SCHEMA_VIOLATION: unparseable dependency '$spec'")
            val range = VersionRange(dep.range)
            if (!range.isValid) {
                throw RecipeSchemaException("SCHEMA_VIOLATION: unparseable range '${dep.range}' in '$spec'")
            }
            val installed = installedVersion(dep.pluginId)
            if (installed != null && range.matches(installed)) continue

            val available = marketplaceLookup(dep.pluginId)
            if (available == null) {
                missing += MissingDependency(dep.pluginId, dep.range, reason = "not_in_marketplace")
            } else if (range.matches(available.version)) {
                missing += MissingDependency(dep.pluginId, dep.range, reason = "available_in_marketplace", suggestedVersion = available.version)
            } else {
                missing += MissingDependency(dep.pluginId, dep.range, reason = "not_in_marketplace")
            }
        }
        return if (missing.isEmpty()) RecipeResolveResult.Resolved else RecipeResolveResult.Unresolved(missing)
    }
}
