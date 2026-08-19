package com.morainet.mcos.marketplace

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/**
 * Client-side search ranking and recommendation logic
 * ([09-marketplace.md §9.1, §9.2]).
 *
 * The marketplace serves the composite `safetyScore` pre-computed at index
 * time, but the client re-computes the same formulas here so it can
 * re-rank local result sets (offline cache, recommendations) with an
 * identical definition of "relevant and safe".
 *
 * Design intent of the safety weight ([09-marketplace.md §9.1]):
 * it **dampens** but never hides a plugin — the weight is floored at 0.3 so
 * a photo plugin that needs camera access still appears, just below a photo
 * plugin that needs less.
 */
object SearchRanking {

    /**
     * §9.1 `computeSafetyWeight` — more high-risk permissions → lower weight.
     *
     * @return 0.3–1.0; the floor keeps every plugin discoverable.
     */
    fun computeSafetyWeight(permissions: List<MarketplacePermissionEntry>): Float {
        val destructive = permissions.count { it.riskTier == "destructive" }
        val elevated = permissions.count { it.riskTier == "elevated" }
        val normal = permissions.count { it.riskTier == "normal" }
        val penalty = destructive * 0.15f + elevated * 0.05f + normal * 0.01f
        return max(0.3f, 1.0f - penalty)
    }

    /**
     * §9.1 `rank` — composite score balancing relevance, category,
     * popularity, and safety.
     */
    fun rank(query: String, metadata: PackageMetadata): Float {
        val textScore = textScore(query, metadata)
        val categoryBonus = if (metadata.categories.any { it.equals(query, ignoreCase = true) }) 0.2f else 0.0f
        val popularity = min(1.0, log10(metadata.downloadCount + 1.0) / 6.0).toFloat()
        val safetyWeight = computeSafetyWeight(metadata.permissionsPreview)
        return (textScore * 0.5f + categoryBonus * 0.2f + popularity * 0.3f) * safetyWeight
    }

    /**
     * §9.1 text relevance (BM25-style, 0.0–1.0) over name, summary,
     * description, and `commandsPreview`.
     *
     * An exact command-ID match in `commandsPreview` scores highest (1.0),
     * so a plugin that *provides* the searched command ranks above one that
     * merely mentions the word.
     */
    fun textScore(query: String, metadata: PackageMetadata): Float {
        val q = query.trim()
        if (q.isEmpty()) return 0f
        if (metadata.commandsPreview.any { it.equals(q, ignoreCase = true) }) return 1.0f

        val terms = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return 0f

        val fieldText = listOf(metadata.name, metadata.summary, metadata.description.orEmpty())
            .joinToString(" ")
            .lowercase()
        var hits = 0
        for (term in terms) {
            if (fieldText.contains(term.lowercase())) hits++
            if (metadata.commandsPreview.any { it.lowercase().contains(term.lowercase()) }) hits++
        }
        // Each term can hit either the prose fields or the command list (≤ 2 hits/term).
        return hits.toFloat() / (terms.size * 2).toFloat()
    }

    /**
     * §9.2 recommendation strategy — recommend plugins that provide command
     * IDs the user has referenced but does not have installed.
     *
     * Candidates are ranked by safety weight, plus a +0.1 familiarity bonus
     * when the user already has other plugins from the same publisher.
     * Returns at most [topN] packages.
     *
     * This is client-side and privacy-preserving: the client sends only the
     * missing command IDs to the marketplace, never usage history.
     */
    fun recommendPlugins(
        missingCommandIds: Set<String>,
        candidates: List<PackageMetadata>,
        isInstalled: (String) -> Boolean = { false },
        isSamePublisher: (PackageMetadata) -> Boolean = { false },
        topN: Int = 5,
    ): List<PackageMetadata> {
        val missingLower = missingCommandIds.map(String::lowercase).toSet()
        return candidates
            .asSequence()
            .filter { candidate ->
                candidate.commandsPreview.any { it.lowercase() in missingLower }
            }
            .filterNot { isInstalled(it.packageId) }
            .sortedByDescending { candidate ->
                computeSafetyWeight(candidate.permissionsPreview) +
                    if (isSamePublisher(candidate)) 0.1f else 0f
            }
            .take(topN)
            .toList()
    }
}
