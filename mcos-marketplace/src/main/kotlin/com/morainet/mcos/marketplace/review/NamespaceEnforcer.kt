package com.morainet.mcos.marketplace.review

/**
 * Reserved-namespace gate (spec 09 §5.1 gate 2 + 02 §4.3).
 *
 * Shared **production** implementation used by:
 *  - the index server review pipeline (`mcos-index-server`),
 *  - the conformance "market" suite (`mcos-conformance`), so authors get the
 *    exact same verdict locally that the marketplace CI produces.
 *
 * Third-party command ids must not start with a reserved prefix.
 */
object NamespaceEnforcer {
    private val RESERVED = listOf("mcos.", "sys.", "mcp.", "std.")

    /** Reserved prefixes a third-party command id must never use. */
    val reservedPrefixes: List<String> = RESERVED

    /** Returns the reserved command ids present in [commandIds]. */
    fun findReserved(commandIds: List<String>): List<String> =
        commandIds.filter { id -> RESERVED.any { id.startsWith(it) } }

    /** True when [commandId] is a reserved-prefixed id. */
    fun isReserved(commandId: String): Boolean =
        RESERVED.any { commandId.startsWith(it) }
}
