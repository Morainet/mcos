package com.mcos.runtime.security

/**
 * Trust level of a plugin artifact, as defined in [08-security.md §7.0]
 * and [09-marketplace.md §6.5].
 *
 * Trust level is *derived* by the runtime from the artifact's signature
 * status, the build mode, and enterprise policy — it is never self-asserted
 * by the plugin. The matrix:
 *
 * | TrustLevel          | Derived when                                                        |
 * |--------------------|---------------------------------------------------------------------|
 * | [BUILTIN]           | Plugin ships with the runtime (no external artifact bytes)          |
 * | [MARKETPLACE_VERIFIED] | Signature valid against a known, ACTIVE publisher key           |
 * | [SIDELOAD_DEBUG]    | Debug build + unsigned sideload (warned, developer-only)            |
 * | [UNTRUSTED]         | Signature missing/revoked/blocklisted, or sideload in production    |
 */
enum class TrustLevel {
    /** Ships with the runtime; loaded without external verification. */
    BUILTIN,

    /** Signature verified against a known publisher key (09 §6.2). */
    MARKETPLACE_VERIFIED,

    /** Unsigned sideload accepted only in debug builds (08 §7.2). */
    SIDELOAD_DEBUG,

    /** Failed verification or disallowed by policy; must not load. */
    UNTRUSTED,
}
