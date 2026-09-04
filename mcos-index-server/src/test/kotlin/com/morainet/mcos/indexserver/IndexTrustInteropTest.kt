package com.morainet.mcos.indexserver

import com.morainet.mcos.marketplace.BlocklistVerifier
import com.morainet.mcos.marketplace.JdkMarketplaceHttpTransport
import com.morainet.mcos.marketplace.MarketplaceIndex
import com.morainet.mcos.marketplace.MarketplaceIndexException
import com.morainet.mcos.security.KeyStatus
import com.morainet.mcos.security.PublisherKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Trust-chain interop (12-index-server.md §7 / §9): the operator-signed
 * blocklist verifies against the client's pinned key, blocklist edits are
 * reflected in the signed document, and a compromised/rotated key cannot
 * sign further submissions.
 */
class IndexTrustInteropTest {

    @Test
    fun `blocklist is operator-signed and clients verify it`() {
        ServerFixture().use { s ->
            runBlocking {
                val blocklist = s.client().fetchBlocklist()
                assertEquals("0", blocklist.version)
                assertTrue(blocklist.entries.isEmpty())
            }
        }
    }

    @Test
    fun `a client pinned to a different marketplace key rejects the document`() {
        ServerFixture().use { s ->
            val rogue = IndexTestKit.ed25519()
            val rogueKey = PublisherKey(
                keyId = "rogue-operator",
                publisherId = "operator",
                publicKeyFingerprint = IndexTestKit.sha256Hex(rogue.public.encoded),
                algorithm = "Ed25519",
                publicKeyEncoded = IndexTestKit.b64(rogue.public.encoded),
                createdAt = IndexTestKit.nowIso(),
                status = KeyStatus.ACTIVE,
            )
            val client = MarketplaceIndex(
                baseUrl = s.baseUrl,
                transport = JdkMarketplaceHttpTransport(),
                blocklistVerifier = BlocklistVerifier(rogueKey),
            )
            val error = runCatching { runBlocking { client.fetchBlocklist() } }
                .exceptionOrNull() as? MarketplaceIndexException
            assertEquals("BLOCKLIST_SIGNATURE_INVALID", error?.code)
        }
    }

    @Test
    fun `blocklist entries round-trip add remove and re-sign`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"
            val submission = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            submission.response.ok()
            s.publishSubmission(alpha, packageId, submission.submissionId).ok()

            s.adminBlocklistAdd(packageId).ok()

            // A fresh client sees the signed entry and treats the package as blocked.
            runBlocking {
                val entry = s.client().fetchBlocklist().entries.single()
                assertEquals(packageId, entry.packageId)
                assertEquals("*", entry.versionRange)
                assertEquals("POLICY_VIOLATION", entry.reason.name)
                val blocked = s.client().fetchBlocklist().isBlocklisted(packageId, "1.0.0")
                assertTrue(blocked, "client-side VersionRange check flags the entry")
            }

            // Moderation keeps working over a blocklisted package: removal un-blocks it.
            s.adminBlocklistRemove(packageId).ok()
            runBlocking {
                assertTrue(s.client().fetchBlocklist().entries.isEmpty())
            }
        }
    }

    @Test
    fun `routine key rotation revokes the old key and a signature from it is rejected`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"

            // Publisher rotates key-1 → key-2, then keeps publishing with key-2.
            val key2 = s.registerExtraKey(alpha, "key-2")
            s.delete("/v1/publishers/alpha/keys/key-1", alpha.token).ok()

            runBlocking {
                val revoked = s.client().refreshRevokedKeys()
                assertTrue(revoked.any { it.keyId == "key-1" && it.status == KeyStatus.REVOKED }, "$revoked")
            }

            // Signing a NEW submission with the rotated (now REVOKED) key fails gate 8.
            val forged = s.submitPackage(
                alpha,
                pluginManifest(packageId, "1.0.0"),
                signingKey = alpha.key,
                signingKeyId = alpha.keyId,
            )
            forged.response.ok()
                .contains("\"state\":\"CI_REJECTED\"")
                .contains("not an ACTIVE key")

            // The legitimate key-2 signing path still works end to end.
            val ok = s.submitPackage(
                alpha,
                pluginManifest(packageId, "1.0.1"),
                signingKey = key2,
                signingKeyId = "key-2",
            )
            ok.response.ok().contains("\"state\":\"APPROVED\"")
        }
    }

    @Test
    fun `emergency revoke exposes the key in the revoked list`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val key2 = s.registerExtraKey(alpha, "key-2")
            val fingerprint = IndexTestKit.sha256Hex(key2.public.encoded)

            s.adminEmergencyRevokeKey("key-2", reason = "compromised").ok()

            runBlocking {
                val revoked = s.client().refreshRevokedKeys()
                assertTrue(revoked.any { it.keyId == "key-2" }, "$revoked")
            }
            val raw = s.get("/v1/keys/revoked").ok()
            assertTrue(raw.body.contains("key-2"), raw.body)
            assertTrue(raw.body.contains(fingerprint), raw.body)
        }
    }

    @Test
    fun `unauthenticated and cross-publisher writes are refused`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val beta = s.createPublisherSession("beta")

            // Admin surface without a token.
            assertEquals(401, s.get("/v1/admin/submissions").status)

            // A publisher cannot register a key for another publisher.
            val forgedKey = IndexTestKit.ed25519()
            val body = """{"keyId":"key-x","publisherId":"alpha",""" +
                """"publicKeyFingerprint":"${IndexTestKit.sha256Hex(forgedKey.public.encoded)}",""" +
                """"algorithm":"Ed25519",""" +
                """"publicKeyEncoded":"${IndexTestKit.publicKeyB64(forgedKey)}",""" +
                """"createdAt":"${IndexTestKit.nowIso()}"}"""
            assertEquals(403, s.post("/v1/publishers/alpha/keys", beta.token, body).status)

            // Duplicate publisher creation conflicts.
            assertEquals(409, s.post("/v1/admin/publishers", s.adminToken, """{"id":"alpha"}""").status)
        }
    }
}
