package com.mcos.runtime.security

/**
 * Decision produced by [PluginTrustGate.evaluate] for a plugin artifact.
 */
sealed class TrustDecision {
    /** Artifact may be loaded under the given [trustLevel]. */
    data class Allow(
        val trustLevel: TrustLevel,
        /** Human-readable note (e.g. "cached verification" / "debug build"). */
        val note: String? = null,
    ) : TrustDecision()

    /** Artifact must not be loaded. */
    data class Deny(
        val reason: String,
        /** Machine-readable reason code, e.g. "sideload_disabled_by_policy". */
        val code: String,
    ) : TrustDecision()
}

/**
 * Gate that decides whether a plugin artifact may be loaded, per
 * [08-security.md §7.1/§7.2], [09-marketplace.md §6.5] and the enterprise
 * policy `disableSideload` flag ([08-security.md §13.2]).
 *
 * Decision matrix (fail-closed):
 *
 * | Case                                             | Decision                    |
 * |--------------------------------------------------|-----------------------------|
 * | Builtin plugin (no artifact bytes)               | Allow(BUILTIN)              |
 * | Signature verified (marketplace or key registry) | Allow(MARKETPLACE_VERIFIED) |
 * | Signature invalid / revoked key / blocklisted    | Deny(UNTRUSTED)             |
 * | No signature, debug build, sideload allowed      | Allow(SIDELOAD_DEBUG)       |
 * | No signature, production build                   | Deny                        |
 * | No signature, enterprise policy disables sideload| Deny                        |
 * | Signature present but no verifier configured     | Deny                        |
 *
 * @param verifier signature verifier; null disables signature verification
 *                  (then only BUILTIN plugins pass).
 * @param debugBuild whether the runtime is a debug build (allows
 *                  [TrustLevel.SIDELOAD_DEBUG]).
 * @param enterprisePolicy provider for the current enterprise policy
 *                  (null → no enterprise policy enforcement).
 */
class PluginTrustGate(
    private val verifier: ArtifactVerifier? = null,
    private val debugBuild: Boolean = false,
    private val enterprisePolicy: () -> EnterprisePolicy? = { null },
) {
    /**
     * Evaluate whether [packageId]@[version] may be loaded.
     *
     * @param payload artifact bytes; null for builtin plugins (ships with runtime).
     * @param signature signature envelope; null when unsigned.
     * @param builtin true if the plugin ships with the runtime (BUILTIN).
     */
    fun evaluate(
        packageId: String,
        version: String,
        payload: ByteArray?,
        signature: ArtifactSignature?,
        builtin: Boolean = false,
    ): TrustDecision {
        // Builtin plugins ship with the runtime; nothing to verify.
        if (builtin) {
            return TrustDecision.Allow(TrustLevel.BUILTIN, note = "ships with runtime")
        }
        if (payload == null) {
            return TrustDecision.Deny(
                reason = "plugin has no artifact payload and is not builtin",
                code = "missing_payload",
            )
        }

        // Signed artifact path.
        if (signature != null) {
            val v = verifier
            if (v == null) {
                return TrustDecision.Deny(
                    reason = "artifact is signed but no signature verifier is configured",
                    code = "verifier_not_configured",
                )
            }
            return when (val result = v.verify(payload, signature, packageId, version)) {
                is VerifyResult.Verified -> TrustDecision.Allow(
                    TrustLevel.MARKETPLACE_VERIFIED,
                    note = if (result.fromCache) "cached verification" else "verified",
                )
                is VerifyResult.Rejected -> TrustDecision.Deny(
                    reason = "signature rejected: ${result.reason}",
                    code = "signature_${result.reason}",
                )
            }
        }

        // Unsigned (sideload) path.
        val policy = enterprisePolicy()
        if (policy?.disableSideload == true) {
            return TrustDecision.Deny(
                reason = "sideloading disabled by enterprise policy",
                code = "sideload_disabled_by_policy",
            )
        }
        if (!debugBuild) {
            return TrustDecision.Deny(
                reason = "unsigned sideload is only allowed in debug builds",
                code = "sideload_production_denied",
            )
        }
        return TrustDecision.Allow(
            TrustLevel.SIDELOAD_DEBUG,
            note = "debug build; unsigned sideload accepted",
        )
    }
}
