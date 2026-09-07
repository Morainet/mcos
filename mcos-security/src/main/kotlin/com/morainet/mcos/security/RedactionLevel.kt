package com.morainet.mcos.security

/**
 * How aggressively the audit sink scrubs the `ir` field before storage
 * ([03-runtime.md §13.3], [03-runtime.md §19] hot-reloadable `auditRedaction`).
 *
 * Ordered least → most restrictive so precedence logic can take the maximum
 * across sources (a user may raise redaction, never lower it below what an
 * enterprise policy demands):
 *
 * - [OFF] — no redaction. The loud, greppable opt-out: raw `ir` is stored
 *   verbatim (secrets included). Dangerous; only for local debugging where the
 *   audit store is throwaway. Enterprise presence force-upgrades away from OFF.
 * - [DEFAULT] — the historical deterministic JSON walk (redact known secret
 *   field names + `x-mcos-secret`-marked objects; regex fallback for non-JSON
 *   `ir`). This is byte-for-byte the pre-§19 behaviour.
 * - [STRICT] — the [DEFAULT] walk **plus** an additional regex pass over the
 *   serialized result, catching `key: value` secret pairs that survived the
 *   structured walk (defense-in-depth for non-JSON-shaped leakage).
 *
 * The ordinal ordering (OFF < DEFAULT < STRICT) is load-bearing for the
 * most-restrictive-wins merge in `RuntimeConfigManager`; keep it.
 */
enum class RedactionLevel {
    OFF,
    DEFAULT,
    STRICT,
}
