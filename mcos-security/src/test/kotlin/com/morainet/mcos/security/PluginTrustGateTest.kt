package com.morainet.mcos.security

import java.util.Base64
import kotlin.test.*

/**
 * Unit tests for [PluginTrustGate] — the load-time trust decision matrix of
 * [08-security.md §7.1/§7.2], [09-marketplace.md §6.5] and the enterprise
 * policy `disableSideload` flag ([08-security.md §13.2]).
 */
class PluginTrustGateTest {

    private val fakePayload = byteArrayOf(1, 2, 3, 4)
    private val keyPair = run {
        val kpg = java.security.KeyPairGenerator.getInstance("Ed25519")
        kpg.generateKeyPair()
    }

    // ═══════════════════════════════════════════════════════════════
    // T1-T2: Builtin plugins
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T1-builtin plugin always allowed regardless of build mode`() {
        val gate = PluginTrustGate(debugBuild = false)

        val decision = gate.evaluate("com.example.core", "1.0.0", null, null, builtin = true)

        assertIs<TrustDecision.Allow>(decision)
        assertEquals(TrustLevel.BUILTIN, decision.trustLevel)
    }

    @Test
    fun `T2-non-builtin without payload is denied`() {
        val gate = PluginTrustGate(debugBuild = true)

        val decision = gate.evaluate("com.example.core", "1.0.0", null, null)

        assertIs<TrustDecision.Deny>(decision)
        assertEquals("missing_payload", decision.code)
    }

    // ═══════════════════════════════════════════════════════════════
    // T3-T7: Signed artifacts (MARKETPLACE_VERIFIED)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T3-valid signature allows marketplace verified`() {
        val store = InMemoryPublisherKeyStore().apply {
            put(pubKey())
        }
        val verifier = ArtifactVerifier(store)
        val gate = PluginTrustGate(verifier = verifier, debugBuild = false)

        val decision = gate.evaluate("com.example.plugin", "1.0.0", fakePayload, goodSig())

        assertIs<TrustDecision.Allow>(decision)
        assertEquals(TrustLevel.MARKETPLACE_VERIFIED, decision.trustLevel)
    }

    @Test
    fun `T4-invalid signature denied even in debug build`() {
        val store = InMemoryPublisherKeyStore().apply {
            put(pubKey())
        }
        val verifier = ArtifactVerifier(store)
        val gate = PluginTrustGate(verifier = verifier, debugBuild = true)

        val bad = goodSig().copy(payloadSha256 = "ff".repeat(32))
        val decision = gate.evaluate("com.example.plugin", "1.0.0", fakePayload, bad)

        assertIs<TrustDecision.Deny>(decision)
        assertEquals("signature_hash_mismatch", decision.code)
    }

    @Test
    fun `T5-revoked key denied`() {
        val store = InMemoryPublisherKeyStore().apply {
            put(pubKey().copy(status = KeyStatus.REVOKED))
        }
        val verifier = ArtifactVerifier(store)
        val gate = PluginTrustGate(verifier = verifier, debugBuild = true)

        val decision = gate.evaluate("com.example.plugin", "1.0.0", fakePayload, goodSig())

        assertIs<TrustDecision.Deny>(decision)
        assertEquals("signature_key_revoked", decision.code)
    }

    @Test
    fun `T6-blocklisted package denied`() {
        val store = InMemoryPublisherKeyStore().apply {
            put(pubKey())
        }
        val verifier = ArtifactVerifier(store, blocklist = Blocklist { pkg, _ -> pkg == "com.example.plugin" })
        val gate = PluginTrustGate(verifier = verifier, debugBuild = false)

        val decision = gate.evaluate("com.example.plugin", "1.0.0", fakePayload, goodSig())

        assertIs<TrustDecision.Deny>(decision)
        assertEquals("signature_blocklisted", decision.code)
    }

    @Test
    fun `T7-signed artifact with no verifier configured is denied`() {
        val gate = PluginTrustGate(verifier = null, debugBuild = true)

        val decision = gate.evaluate("com.example.plugin", "1.0.0", fakePayload, goodSig())

        assertIs<TrustDecision.Deny>(decision)
        assertEquals("verifier_not_configured", decision.code)
    }

    // ═══════════════════════════════════════════════════════════════
    // T8-T11: Unsigned sideloads (SIDELOAD_DEBUG)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T8-unsigned sideload allowed in debug build`() {
        val gate = PluginTrustGate(verifier = null, debugBuild = true)

        val decision = gate.evaluate("com.example.hack", "1.0.0", fakePayload, null)

        assertIs<TrustDecision.Allow>(decision)
        assertEquals(TrustLevel.SIDELOAD_DEBUG, decision.trustLevel)
    }

    @Test
    fun `T9-unsigned sideload denied in production build`() {
        val gate = PluginTrustGate(verifier = null, debugBuild = false)

        val decision = gate.evaluate("com.example.hack", "1.0.0", fakePayload, null)

        assertIs<TrustDecision.Deny>(decision)
        assertEquals("sideload_production_denied", decision.code)
    }

    @Test
    fun `T10-disableSideload enterprise policy blocks sideload even in debug`() {
        val policy = EnterprisePolicy(
            allowCommands = emptyList(),
            denyCommands = emptyList(),
            forceConfirm = emptyList(),
            networkAllow = emptyList(),
            networkDeny = emptyList(),
            disableSideload = true,
            disableCloudMemorySync = false,
            auditFailClosed = false,
            disableAllPluginNetwork = false,
            secretTtlDays = 90,
            version = "1.0",
            issuedAt = "2026-08-15T00:00:00Z",
            issuedBy = "mdm.corp",
        )
        val gate = PluginTrustGate(verifier = null, debugBuild = true, enterprisePolicy = { policy })

        val decision = gate.evaluate("com.example.hack", "1.0.0", fakePayload, null)

        assertIs<TrustDecision.Deny>(decision)
        assertEquals("sideload_disabled_by_policy", decision.code)
    }

    @Test
    fun `T11-disableSideload does not block verified marketplace plugins`() {
        val policy = EnterprisePolicy(
            allowCommands = emptyList(),
            denyCommands = emptyList(),
            forceConfirm = emptyList(),
            networkAllow = emptyList(),
            networkDeny = emptyList(),
            disableSideload = true,
            disableCloudMemorySync = false,
            auditFailClosed = false,
            disableAllPluginNetwork = false,
            secretTtlDays = 90,
            version = "1.0",
            issuedAt = "2026-08-15T00:00:00Z",
            issuedBy = "mdm.corp",
        )
        val store = InMemoryPublisherKeyStore().apply {
            put(pubKey())
        }
        val verifier = ArtifactVerifier(store)
        val gate = PluginTrustGate(verifier = verifier, debugBuild = false, enterprisePolicy = { policy })

        val decision = gate.evaluate("com.example.plugin", "1.0.0", fakePayload, goodSig())

        assertIs<TrustDecision.Allow>(decision)
        assertEquals(TrustLevel.MARKETPLACE_VERIFIED, decision.trustLevel)
    }

    // ═══════════════════════════════════════════════════════════════
    // T12: Cached verification note
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `T12-cached verification is noted as cached`() {
        val store = InMemoryPublisherKeyStore().apply {
            put(pubKey())
        }
        val verifier = ArtifactVerifier(store)
        val gate = PluginTrustGate(verifier = verifier, debugBuild = false)

        gate.evaluate("com.example.plugin", "1.0.0", fakePayload, goodSig())
        val second = gate.evaluate("com.example.plugin", "1.0.0", fakePayload, goodSig())

        assertIs<TrustDecision.Allow>(second)
        assertEquals(TrustLevel.MARKETPLACE_VERIFIED, second.trustLevel)
        assertEquals("cached verification", second.note)
    }

    // ═══════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════

    private fun pubKey(keyId: String = "key_2026_01"): PublisherKey =
        PublisherKey(
            keyId = keyId,
            publisherId = "pub.example",
            publicKeyFingerprint = "fingerprint",
            algorithm = "Ed25519",
            publicKeyEncoded = Base64.getEncoder().encodeToString(keyPair.public.encoded),
            createdAt = "2026-01-01T00:00:00Z",
        )

    private fun goodSig(): ArtifactSignature = ArtifactSignature(
        payloadSha256 = sha256Hex(fakePayload),
        signature = Base64.getEncoder().encodeToString(
            java.security.Signature.getInstance("Ed25519").let { s ->
                s.initSign(keyPair.private)
                s.update(fakePayload)
                s.sign()
            }
        ),
        signingKeyId = "key_2026_01",
        algorithm = "Ed25519",
        signedAt = "2026-08-15T00:00:00Z",
    )

    private fun sha256Hex(data: ByteArray): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(data).joinToString("") { "%02x".format(it) }
    }
}
