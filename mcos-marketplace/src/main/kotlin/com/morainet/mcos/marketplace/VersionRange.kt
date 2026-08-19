package com.morainet.mcos.marketplace

import com.morainet.mcos.runtime.core.registry.SemanticVersion

/**
 * Minimal SemVer range matcher for blocklist entries ([09-marketplace.md §14.0])
 * and recipe dependencies ([09-marketplace.md §7.4]).
 *
 * Supported syntax (whitespace between bounds is a conjunction):
 *  - `"*"` — any version
 *  - `"1.2.3"` — exact
 *  - `">=1.0.0"`, `">1.0.0"`, `"<=2.0.0"`, `"<2.0.0"`, `"=1.2.3"` — bound
 *  - `">=1.0.0 <2.0.0"` — range
 *  - `"^1.0.0"` — caret (compatible, same major; `^0.2.3` → same minor,
 *    `^0.0.3` → same patch, [09-marketplace.md §7.4])
 *  - `"~1.0.0"` — tilde (approximately, same minor; `~1` → same major)
 *
 * An invalid range or a non-parseable version never matches (fail-safe: the
 * entry is ignored rather than over-blocking healthy plugins).
 */
class VersionRange(private val spec: String) {

    /** null when the spec is malformed — such an entry never matches. */
    private val clauses: List<Clause>? = parse(spec)

    /** True when the spec parsed to at least one bound; false when malformed. */
    val isValid: Boolean get() = clauses != null

    fun matches(version: String): Boolean {
        val list = clauses ?: return false
        val parsed = runCatching { SemanticVersion.parse(version) }.getOrNull() ?: return false
        return list.all { it.matches(parsed) }
    }

    private sealed interface Clause {
        fun matches(version: SemanticVersion): Boolean
    }

    private data class Exact(val target: SemanticVersion) : Clause {
        override fun matches(version: SemanticVersion): Boolean = version == target
    }

    private data class Bound(val operator: String, val target: SemanticVersion) : Clause {
        override fun matches(version: SemanticVersion): Boolean {
            val comparison = version.compareTo(target)
            return when (operator) {
                ">=" -> comparison >= 0
                ">" -> comparison > 0
                "<=" -> comparison <= 0
                "<" -> comparison < 0
                "=" -> comparison == 0
                else -> false
            }
        }
    }

    private companion object {
        private val BOUND_OPERATORS = listOf(">=", "<=", ">", "<", "=")

        private fun parse(spec: String): List<Clause>? {
            val trimmed = spec.trim()
            if (trimmed.isEmpty() || trimmed == "*") return emptyList()
            val parsed = mutableListOf<Clause>()
            for (token in trimmed.split(Regex("\\s+"))) {
                when {
                    token.startsWith("^") -> parsed += caretClauses(token.substring(1)) ?: return null
                    token.startsWith("~") -> parsed += tildeClauses(token.substring(1)) ?: return null
                    else -> {
                        val operator = BOUND_OPERATORS.firstOrNull { token.startsWith(it) }
                        val versionPart = if (operator != null) token.substring(operator.length) else token
                        val target = runCatching { SemanticVersion.parse(versionPart) }.getOrNull() ?: return null
                        parsed += if (operator == null) Exact(target) else Bound(operator, target)
                    }
                }
            }
            return parsed
        }

        /** Caret bound, e.g. `^1.2.3` → `>=1.2.3 <2.0.0`, `^0.2.3` → `>=0.2.3 <0.3.0`. */
        private fun caretClauses(versionPart: String): List<Clause>? {
            val raw = versionPart.trim()
            val normalized = if (raw.count { it == '.' } == 0) "$raw.0" else raw
            val v = runCatching { SemanticVersion.parse(normalized) }.getOrNull() ?: return null
            val segmentCount = raw.split(".").size
            val upper = when {
                v.major > 0 -> SemanticVersion(v.major + 1, 0, 0)
                v.minor > 0 -> SemanticVersion(0, v.minor + 1, 0)
                segmentCount >= 3 -> SemanticVersion(0, 0, v.patch + 1)
                segmentCount == 2 -> SemanticVersion(0, 1, 0) // `^0.0`
                else -> SemanticVersion(1, 0, 0)               // `^0`
            }
            return listOf(Bound(">=", v), Bound("<", upper))
        }

        /** Tilde bound, e.g. `~1.2.3` → `>=1.2.3 <1.3.0`, `~1` → `>=1.0.0 <2.0.0`. */
        private fun tildeClauses(versionPart: String): List<Clause>? {
            val raw = versionPart.trim()
            val normalized = if (raw.count { it == '.' } == 0) "$raw.0" else raw
            val v = runCatching { SemanticVersion.parse(normalized) }.getOrNull() ?: return null
            val segmentCount = raw.split(".").size
            val upper = if (segmentCount >= 2) SemanticVersion(v.major, v.minor + 1, 0)
            else SemanticVersion(v.major + 1, 0, 0) // `~1`
            return listOf(Bound(">=", v), Bound("<", upper))
        }
    }
}
