package com.morainet.mcos.conformance.trust

import com.morainet.mcos.conformance.api.ConformanceCase
import com.morainet.mcos.conformance.api.ConformanceSuite
import com.morainet.mcos.conformance.crypto.TestKeys
import com.morainet.mcos.marketplace.BlocklistVerifier
import com.morainet.mcos.security.ArtifactSignature
import com.morainet.mcos.security.ArtifactVerifier
import com.morainet.mcos.security.InMemoryPublisherKeyStore
import com.morainet.mcos.security.KeyStatus
import com.morainet.mcos.security.PluginTrustGate
import com.morainet.mcos.security.PublisherKey
import com.morainet.mcos.security.TrustDecision
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.security.VerifyResult
import java.security.MessageDigest

/**
 * Trust conformance (spec 09 §5.1 gate 8 + 08 §6/§7).
 *
 * Mirror of the artifact signature verification + plugin trust gate
 * decision matrix. Each sub-case constructs an in-memory scenario and
 * asserts the runtime response:
 *
 *  - ArtifactVerifier: hash mismatch / unknown key / revoked key /
 *    algorithm mismatch / wrong-key signature / cache hit / blocklisted;
 *  - PluginTrustGate: BUILTIN / MARKETPLACE_VERIFIED /
 *    SIDELOAD_DEBUG / verifier-not-configured / sideload-disabled-by-policy;
 *  - BlocklistVerifier: valid signature / tampered / missing.
 *
 * Keypairs are in-process and live only for the duration of the run
 * ([TestKeys]); no private material is ever serialised.
 */
class TrustConformanceSuite : ConformanceSuite {
    override val id = "trust"
    override val title = "Trust + signature verification"
    override val spec = "09 §5.1 gate 8 + 08 §6/§7 + 09 §6.2/§6.5"

    override fun cases(): List<ConformanceCase> = buildList {
        // ─── ArtifactVerifier ─────────────────────────────────────────
        addAll(artifactVerifierCases())

        // ─── PluginTrustGate decision matrix ──────────────────────────
        addAll(pluginTrustGateCases())

        // ─── BlocklistVerifier ────────────────────────────────────────
        addAll(blocklistVerifierCases())
    }

    // ─── ArtifactVerifier ────────────────────────────────────────────────

    private fun artifactVerifierCases(): List<ConformanceCase> {
        val ed = TestKeys.ed25519
        val rsa = TestKeys.rsaPss4096
        val payload = "conformance-payload".toByteArray()
        val sha256Hex = payload.sha256Hex()
        val edSignature = ArtifactSignature(
            payloadSha256 = sha256Hex,
            signature = ed.sign(payload),
            signingKeyId = ed.keyId,
            algorithm = ed.algorithm,
            signedAt = "2026-09-03T00:00:00Z",
        )
        val rsaSignature = ArtifactSignature(
            payloadSha256 = sha256Hex,
            signature = rsa.sign(payload),
            signingKeyId = rsa.keyId,
            algorithm = rsa.algorithm,
            signedAt = "2026-09-03T00:00:00Z",
        )
        // Negative cases that must exercise the CRYPTO path get their own
        // payloads: the offline verification cache is keyed by
        // (signingKeyId, payloadSha256) and never sees the signature bytes,
        // so sharing the payload signed above would let ed25519-valid's
        // cached Verified short-circuit these cases entirely.
        fun edSignatureFor(case: String): Pair<ByteArray, ArtifactSignature> {
            val bytes = "conformance-payload:$case".toByteArray()
            return bytes to ArtifactSignature(
                payloadSha256 = bytes.sha256Hex(),
                signature = ed.sign(bytes),
                signingKeyId = ed.keyId,
                algorithm = ed.algorithm,
                signedAt = "2026-09-03T00:00:00Z",
            )
        }
        val (wrongKeyPayload, wrongKeySignature) = edSignatureFor("wrong-key")
        val (malformedPayload, malformedSignature) = edSignatureFor("malformed-signature")
        val edKey = PublisherKey(
            keyId = ed.keyId,
            publisherId = "pub_test",
            publicKeyFingerprint = ed.publicKeyFingerprint(),
            algorithm = ed.algorithm,
            publicKeyEncoded = ed.publicKeyEncodedB64,
            createdAt = "2026-09-03T00:00:00Z",
        )
        val rsaKey = PublisherKey(
            keyId = rsa.keyId,
            publisherId = "pub_test",
            publicKeyFingerprint = rsa.publicKeyFingerprint(),
            algorithm = rsa.algorithm,
            publicKeyEncoded = rsa.publicKeyEncodedB64,
            createdAt = "2026-09-03T00:00:00Z",
        )
        val activeStore = InMemoryPublisherKeyStore().apply {
            put(edKey)
            put(rsaKey)
        }
        val revokedStore = InMemoryPublisherKeyStore().apply {
            put(edKey.copy(status = KeyStatus.REVOKED))
        }
        val verifier = ArtifactVerifier(activeStore)
        val revokedVerifier = ArtifactVerifier(revokedStore)
        val blocklistedBlocklist = com.morainet.mcos.security.Blocklist { _, _ -> true }
        val blocklistedVerifier = ArtifactVerifier(activeStore, blocklist = blocklistedBlocklist)

        fun expected(reason: String) = ConformanceCase.Result.Pass
        fun rejected(reasonFragment: String): (VerifyResult) -> ConformanceCase.Result = { result ->
            if (result is VerifyResult.Rejected && result.reason.contains(reasonFragment, ignoreCase = true)) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "expected Rejected('$reasonFragment'), got $result",
                )
            }
        }
        fun verified(): (VerifyResult) -> ConformanceCase.Result = { result ->
            if (result is VerifyResult.Verified) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(message = "expected Verified, got $result")
            }
        }

        return listOf(
            // ── valid signatures ──────────────────────────────────────
            verifierCase(
                "trust-artifact-ed25519-valid",
                "valid Ed25519 signature → Verified",
                verifier, payload, edSignature,
                verified(),
            ),
            verifierCase(
                "trust-artifact-rsa-pss-4096-valid",
                "valid RSA-PSS-4096 signature → Verified",
                verifier, payload, rsaSignature,
                verified(),
            ),
            // ── hash mismatch ─────────────────────────────────────────
            verifierCase(
                "trust-artifact-hash-mismatch",
                "tampered payload → Rejected('hash_mismatch')",
                verifier, payload, edSignature.copy(payloadSha256 = "0".repeat(64)),
                rejected("hash_mismatch"),
            ),
            // ── unknown key ───────────────────────────────────────────
            verifierCase(
                "trust-artifact-unknown-key",
                "unknown signing key → Rejected('unknown_key')",
                verifier, payload, edSignature.copy(signingKeyId = "key_does_not_exist"),
                rejected("unknown_key"),
            ),
            // ── revoked key ───────────────────────────────────────────
            verifierCase(
                "trust-artifact-revoked-key",
                "REVOKED key → Rejected('key_revoked')",
                revokedVerifier, payload, edSignature,
                rejected("key_revoked"),
            ),
            // ── algorithm mismatch ────────────────────────────────────
            verifierCase(
                "trust-artifact-algorithm-mismatch",
                "Ed25519 sig claiming RSA → Rejected('algorithm_mismatch')",
                verifier, payload, edSignature.copy(algorithm = "RSA-PSS-4096"),
                rejected("algorithm_mismatch"),
            ),
            // ── wrong-key signature (sign with key not in store) ──────
            verifierCase(
                "trust-artifact-wrong-key",
                "signature from an unrelated key → Rejected('signature_invalid')",
                // Own payload → cache miss → the corrupted signature must
                // fail the actual crypto check, not ride a cached result.
                verifier, wrongKeyPayload,
                wrongKeySignature.copy(
                    signature = wrongKeySignature.signature.dropLast(2) + "AA",
                ),
                rejected("signature_invalid"),
            ),
            // ── blocklisted (packageId, version) ──────────────────────
            verifierCase(
                "trust-artifact-blocklisted",
                "blocklisted (packageId, version) → Rejected('blocklisted')",
                blocklistedVerifier, payload, edSignature,
                rejected("blocklisted"),
                packageId = "com.example.x",
                version = "1.0.0",
            ),
            // ── cache hit (verify twice → second time fromCache) ──────
            object : ConformanceCase {
                override val id = "trust-artifact-cache-hit"
                override val title = "second verify of same payload → fromCache=true"
                override val spec = "09 §6.2 + 03 §16.2"
                override val category = "trust"
                override fun run(): ConformanceCase.Result {
                    val first = verifier.verify(payload, edSignature)
                    val second = verifier.verify(payload, edSignature)
                    return when {
                        first !is VerifyResult.Verified -> ConformanceCase.Result.Fail(
                            message = "first verify did not succeed: $first",
                        )
                        second is VerifyResult.Verified && second.fromCache -> ConformanceCase.Result.Pass
                        else -> ConformanceCase.Result.Fail(
                            message = "expected second verify to be Verified(fromCache=true), got $second",
                        )
                    }
                }
            },
            // ── malformed signature base64 ───────────────────────────
            verifierCase(
                "trust-artifact-malformed-signature",
                "non-base64 signature bytes → Rejected('signature_invalid')",
                // Own payload → cache miss → the undecodable signature must
                // reach the crypto path and fail there.
                verifier, malformedPayload, malformedSignature.copy(signature = "!!!not-base64!!!"),
                rejected("signature_invalid"),
            ),
        )
    }

    private fun verifierCase(
        id: String,
        title: String,
        verifier: ArtifactVerifier,
        payload: ByteArray,
        signature: ArtifactSignature,
        check: (VerifyResult) -> ConformanceCase.Result,
        packageId: String? = null,
        version: String? = null,
    ): ConformanceCase = object : ConformanceCase {
        override val id = id
        override val title = title
        override val spec = "09 §6.2 + 08 §6"
        override val category = "trust"

        override fun run(): ConformanceCase.Result {
            val result = verifier.verify(payload, signature, packageId, version)
            return check(result)
        }
    }

    // ─── PluginTrustGate ─────────────────────────────────────────────────

    private fun pluginTrustGateCases(): List<ConformanceCase> {
        val ed = TestKeys.ed25519
        val payload = "conformance-payload".toByteArray()
        val sha256Hex = payload.sha256Hex()
        val signedSig = ArtifactSignature(
            payloadSha256 = sha256Hex,
            signature = ed.sign(payload),
            signingKeyId = ed.keyId,
            algorithm = ed.algorithm,
            signedAt = "2026-09-03T00:00:00Z",
        )
        val activeStore = InMemoryPublisherKeyStore().apply {
            put(
                PublisherKey(
                    keyId = ed.keyId,
                    publisherId = "pub_test",
                    publicKeyFingerprint = ed.publicKeyFingerprint(),
                    algorithm = ed.algorithm,
                    publicKeyEncoded = ed.publicKeyEncodedB64,
                    createdAt = "2026-09-03T00:00:00Z",
                ),
            )
        }
        val verifier = ArtifactVerifier(activeStore)

        fun gateCase(
            id: String,
            title: String,
            gate: PluginTrustGate,
            packageId: String,
            version: String,
            payload: ByteArray?,
            signature: ArtifactSignature?,
            builtin: Boolean,
            expected: (TrustDecision) -> ConformanceCase.Result,
        ): ConformanceCase = object : ConformanceCase {
            override val id = id
            override val title = title
            override val spec = "08 §7.1/§7.2 + 09 §6.5"
            override val category = "trust"

            override fun run(): ConformanceCase.Result {
                val decision = gate.evaluate(packageId, version, payload, signature, builtin)
                return expected(decision)
            }
        }

        return listOf(
            // ── builtin always allowed ─────────────────────────────────
            gateCase(
                "trust-gate-builtin",
                "builtin plugin → Allow(BUILTIN)",
                gate = PluginTrustGate(verifier, debugBuild = false),
                packageId = "com.example.builtin",
                version = "1.0.0",
                payload = null,
                signature = null,
                builtin = true,
            ) { decision ->
                if (decision is TrustDecision.Allow && decision.trustLevel == TrustLevel.BUILTIN) {
                    ConformanceCase.Result.Pass
                } else {
                    ConformanceCase.Result.Fail("expected Allow(BUILTIN), got $decision")
                }
            },
            // ── signed + verified ──────────────────────────────────────
            gateCase(
                "trust-gate-signed-verified",
                "signed + verified → Allow(MARKETPLACE_VERIFIED)",
                gate = PluginTrustGate(verifier, debugBuild = false),
                packageId = "com.example.signed",
                version = "1.0.0",
                payload = payload,
                signature = signedSig,
                builtin = false,
            ) { decision ->
                if (decision is TrustDecision.Allow && decision.trustLevel == TrustLevel.MARKETPLACE_VERIFIED) {
                    ConformanceCase.Result.Pass
                } else {
                    ConformanceCase.Result.Fail("expected Allow(MARKETPLACE_VERIFIED), got $decision")
                }
            },
            // ── signed but verifier absent ─────────────────────────────
            gateCase(
                "trust-gate-signed-no-verifier",
                "signed artifact + no verifier configured → Deny(verifier_not_configured)",
                gate = PluginTrustGate(verifier = null, debugBuild = false),
                packageId = "com.example.signed",
                version = "1.0.0",
                payload = payload,
                signature = signedSig,
                builtin = false,
            ) { decision ->
                if (decision is TrustDecision.Deny && decision.code == "verifier_not_configured") {
                    ConformanceCase.Result.Pass
                } else {
                    ConformanceCase.Result.Fail("expected Deny(verifier_not_configured), got $decision")
                }
            },
            // ── unsigned + debug + no policy ──────────────────────────
            gateCase(
                "trust-gate-sideload-debug",
                "unsigned + debug build + no policy → Allow(SIDELOAD_DEBUG)",
                gate = PluginTrustGate(verifier, debugBuild = true),
                packageId = "com.example.sideload",
                version = "0.1.0",
                payload = payload,
                signature = null,
                builtin = false,
            ) { decision ->
                if (decision is TrustDecision.Allow && decision.trustLevel == TrustLevel.SIDELOAD_DEBUG) {
                    ConformanceCase.Result.Pass
                } else {
                    ConformanceCase.Result.Fail("expected Allow(SIDELOAD_DEBUG), got $decision")
                }
            },
            // ── unsigned + production ──────────────────────────────────
            gateCase(
                "trust-gate-sideload-production",
                "unsigned + production → Deny(sideload_production_denied)",
                gate = PluginTrustGate(verifier, debugBuild = false),
                packageId = "com.example.sideload",
                version = "0.1.0",
                payload = payload,
                signature = null,
                builtin = false,
            ) { decision ->
                if (decision is TrustDecision.Deny && decision.code == "sideload_production_denied") {
                    ConformanceCase.Result.Pass
                } else {
                    ConformanceCase.Result.Fail("expected Deny(sideload_production_denied), got $decision")
                }
            },
            // ── unsigned + enterprise disableSideload ───────────────────
            gateCase(
                "trust-gate-sideload-disabled-by-policy",
                "unsigned + enterprise disableSideload → Deny(sideload_disabled_by_policy)",
                gate = PluginTrustGate(
                    verifier = verifier,
                    debugBuild = true,
                    enterprisePolicy = {
                        com.morainet.mcos.security.EnterprisePolicy(
                            disableSideload = true,
                        )
                    },
                ),
                packageId = "com.example.sideload",
                version = "0.1.0",
                payload = payload,
                signature = null,
                builtin = false,
            ) { decision ->
                if (decision is TrustDecision.Deny && decision.code == "sideload_disabled_by_policy") {
                    ConformanceCase.Result.Pass
                } else {
                    ConformanceCase.Result.Fail("expected Deny(sideload_disabled_by_policy), got $decision")
                }
            },
        )
    }

    // ─── BlocklistVerifier ───────────────────────────────────────────────

    private fun blocklistVerifierCases(): List<ConformanceCase> {
        val ed = TestKeys.ed25519
        val marketplaceKey = PublisherKey(
            keyId = "marketplace-key",
            publisherId = "mcos_marketplace",
            publicKeyFingerprint = ed.publicKeyFingerprint(),
            algorithm = ed.algorithm,
            publicKeyEncoded = ed.publicKeyEncodedB64,
            createdAt = "2026-09-03T00:00:00Z",
        )
        val verifier = BlocklistVerifier(marketplaceKey)
        val payload = """{"version":"1","entries":[]}""".toByteArray()
        val validSignature = ed.sign(payload)
        val tamperedSignature = validSignature.dropLast(2) + "AA"

        fun expected(reason: String): () -> ConformanceCase.Result = {
            ConformanceCase.Result.Pass
        }

        return listOf(
            object : ConformanceCase {
                override val id = "trust-blocklist-signature-valid"
                override val title = "valid blocklist signature → verified"
                override val spec = "09 §14.3"
                override val category = "trust"
                override fun run(): ConformanceCase.Result =
                    if (verifier.verify(payload, validSignature)) ConformanceCase.Result.Pass
                    else ConformanceCase.Result.Fail("valid signature was rejected")
            },
            object : ConformanceCase {
                override val id = "trust-blocklist-signature-tampered"
                override val title = "tampered blocklist signature → rejected"
                override val spec = "09 §14.3"
                override val category = "trust"
                override fun run(): ConformanceCase.Result =
                    if (!verifier.verify(payload, tamperedSignature)) ConformanceCase.Result.Pass
                    else ConformanceCase.Result.Fail("tampered signature was accepted")
            },
            object : ConformanceCase {
                override val id = "trust-blocklist-signature-missing"
                override val title = "missing blocklist signature → rejected"
                override val spec = "09 §14.3"
                override val category = "trust"
                override fun run(): ConformanceCase.Result =
                    if (!verifier.verify(payload, null)) ConformanceCase.Result.Pass
                    else ConformanceCase.Result.Fail("missing signature was accepted")
            },
            object : ConformanceCase {
                override val id = "trust-blocklist-signature-blank"
                override val title = "blank blocklist signature → rejected"
                override val spec = "09 §14.3"
                override val category = "trust"
                override fun run(): ConformanceCase.Result =
                    if (!verifier.verify(payload, "")) ConformanceCase.Result.Pass
                    else ConformanceCase.Result.Fail("blank signature was accepted")
            },
        )
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private fun ByteArray.sha256Hex(): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(this).joinToString("") { "%02x".format(it) }
    }
}