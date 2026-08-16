package com.mcos.runtime.marketplace

import com.mcos.runtime.registry.SemanticVersion

/**
 * Minimal SemVer range matcher for blocklist entries ([09-marketplace.md §14.0]).
 *
 * Supported syntax (whitespace between bounds is a conjunction):
 *  - `"*"` — any version
 *  - `"1.2.3"` — exact
 *  - `">=1.0.0"`, `">1.0.0"`, `"<=2.0.0"`, `"<2.0.0"`, `"=1.2.3"` — bound
 *  - `">=1.0.0 <2.0.0"` — range
 *
 * An invalid range or a non-parseable version never matches (fail-safe: the
 * entry is ignored rather than over-blocking healthy plugins).
 */
class VersionRange(private val spec: String) {

    /** null when the spec is malformed — such an entry never matches. */
    private val clauses: List<Clause>? = parse(spec)

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
                val operator = BOUND_OPERATORS.firstOrNull { token.startsWith(it) }
                val versionPart = if (operator != null) token.substring(operator.length) else token
                val target = runCatching { SemanticVersion.parse(versionPart) }.getOrNull() ?: return null
                parsed += if (operator == null) Exact(target) else Bound(operator, target)
            }
            return parsed
        }
    }
}
