package com.morainet.mcos.runtime.core.memory

/**
 * Named-entity matching for episodic recall (07-memory.md §8.3).
 *
 * Episodic records reference entities as **memory paths** ("people.tom",
 * "places.office"), but users query them by **natural language** ("Tom",
 * "跟上次一样发照片给Tom"). A raw [fuzzyScore] between the full query and the
 * full path scores near zero for such pairs, so [EntityMatcher] bridges the
 * gap with three signals, taking the maximum:
 *
 *  1. **Full-path match** — unchanged fallback; keeps "places.office"
 *     queries hitting the "places.office" record exactly.
 *  2. **Leaf-node match** — the last path segment ("tom") is compared
 *     case-insensitively against every token of the query, so "Tom" or
 *     "发照片给Tom" resolve to `people.tom`. Query tokens split Han runs
 *     from ASCII alphanumeric runs, so "发照片给Tom" yields ["发照片给", "Tom"].
 *  3. **Alias merge** — caller-registered aliases for a path (e.g. the
 *     profile's display label "Tom", a nickname "Thomas") all resolve to
 *     the same entity, implementing §8.3 "named-entity merge".
 *
 * Case-insensitive: every comparison lowercases both sides.
 */
class EntityMatcher {

    /**
     * Minimum similarity for an entity reference to be recalled. Mirrors the
     * §6.0 Step 3 fuzzy bar (0.75) so unrelated paths do not produce noisy
     * hits; alias/leaf exact matches score 1.0 and comfortably clear it.
     */
    companion object {
        const val RECALL_THRESHOLD: Float = 0.75f
    }

    /** path -> registered aliases (stored lower-cased). */
    private val aliases = mutableMapOf<String, MutableSet<String>>()

    /**
     * Register [alias] names for [path] (e.g. a person's display label or
     * nickname). Aliases are matched case-insensitively against query tokens.
     */
    fun register(path: String, vararg alias: String): EntityMatcher {
        aliases.getOrPut(path) { mutableSetOf() }.addAll(alias.map { it.lowercase() })
        return this
    }

    /** All registered alias sets, for tests/inspection. */
    fun aliasesFor(path: String): Set<String> = aliases[path].orEmpty()

    /**
     * Entity-recall score of [query] against the entity [path] (§8.3).
     * Returns the best of: full-path fuzzy score, leaf-node token score,
     * alias token score. Weak signals below [RECALL_THRESHOLD] are dropped
     * (same 0.75 bar as [MemoryStore] reference resolution, §6.0 Step 3) so
     * unrelated entity paths do not recall spuriously.
     */
    fun score(query: String, path: String): Float {
        if (query.isBlank() || path.isBlank()) return 0f
        val leaf = path.substringAfterLast('.').lowercase()
        val targets = buildList {
            add(leaf)
            aliases[path].orEmpty().forEach { add(it) }
        }.distinct()
        var best = fuzzyScore(query, path)
        for (token in tokens(query)) {
            for (target in targets) {
                best = maxOf(best, fuzzyScore(token, target))
            }
        }
        return if (best >= RECALL_THRESHOLD) best else 0f
    }

    /**
     * Split a query into recall tokens: Han runs and ASCII alphanumeric runs
     * are separate tokens, so mixed-language queries ("发照片给Tom") resolve
     * the embedded entity name ("Tom") independently of the surrounding text.
     */
    private fun tokens(query: String): List<String> =
        Regex("[\\p{IsHan}]+|[a-zA-Z0-9]+")
            .findAll(query)
            .map { it.value.lowercase() }
            .toList()
}
