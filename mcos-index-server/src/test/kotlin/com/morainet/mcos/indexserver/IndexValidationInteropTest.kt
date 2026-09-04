package com.morainet.mcos.indexserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Review-gate interop (12-index-server.md §6 / §9): submissions that violate
 * a CI gate land in CI_REJECTED at submit time — no listing, no admin inbox
 * noise — because the server runs the same shared [com.morainet.mcos.marketplace.review.CiGateEngine]
 * the author-side conformance suite drives locally.
 */
class IndexValidationInteropTest {

    @Test
    fun `gate 2 reserved namespace command id is rejected at submit`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val submission = s.submitPackage(
                alpha,
                reservedNamespaceManifest("com.example.alpha"),
            )
            submission.response.ok()
                .contains("\"state\":\"CI_REJECTED\"")
                .contains("mcos.kernel")
        }
    }

    @Test
    fun `gate 5 monotonic version - a downgrade submission is rejected once a version is listed`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"

            val first = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            first.response.ok()
            s.publishSubmission(alpha, packageId, first.submissionId).ok()

            val downgrade = s.submitPackage(alpha, downgradeManifest(packageId))
            downgrade.response.ok()
                .contains("\"state\":\"CI_REJECTED\"")
                .contains("must increase monotonically")
        }
    }

    @Test
    fun `re-submitting an already listed version returns 409 ALREADY_EXISTS`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val packageId = "com.example.alpha"

            val first = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            first.response.ok()
            s.publishSubmission(alpha, packageId, first.submissionId).ok()

            val duplicate = s.submitPackage(alpha, pluginManifest(packageId, "1.0.0"))
            assertEquals(409, duplicate.response.status)
            assertTrue(duplicate.response.body.contains("ALREADY_EXISTS"), duplicate.response.body)
        }
    }

    @Test
    fun `gate 10 namespace arbitration - a second plugin claiming a listed command id is rejected`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val alphaPkg = "com.example.alpha"
            val first = s.submitPackage(alpha, pluginManifest(alphaPkg, "1.0.0"))
            first.response.ok()
            s.publishSubmission(alpha, alphaPkg, first.submissionId).ok()

            // A different publisher ships a different package id but reuses the
            // already-claimed command id: first-published wins.
            val beta = s.createPublisherSession("beta")
            val conflict = s.submitPackage(
                beta,
                pluginManifest(id = "com.example.beta", version = "1.0.0", commandId = "hello.world"),
            )
            conflict.response.ok()
                .contains("\"state\":\"CI_REJECTED\"")
                .contains("first-published wins")
        }
    }

    @Test
    fun `gate 7 secret containment - literal placeholder is rejected`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val manifest = """
                {
                  "id": "com.example.leaky",
                  "entry": "com.example.LeakyPlugin",
                  "version": "1.0.0",
                  "minRuntimeVersion": "0.2.0",
                  "commands": [
                    {"id": "leaky.run", "sideEffectClass": "read", "version": "1.0.0",
                     "inputSchema": {"type": "object", "properties": {"k": {"description": "{{secret.openai_api_key}}"}}}}
                  ]
                }
            """.trimIndent()
            val submission = s.submitPackage(alpha, manifest)
            submission.response.ok()
                .contains("\"state\":\"CI_REJECTED\"")
                .contains("secret")
        }
    }

    @Test
    fun `non zip bytes and missing parts are schema violations`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")

            // Not a zip at all.
            val badZip = s.postMultipart(
                "/v1/publishers/alpha/plugins",
                alpha.token,
                listOf(
                    "artifact" to "not a zip".toByteArray(),
                    "metadata" to DEFAULT_METADATA.toByteArray(),
                    "signature" to IndexTestKit.signatureJson(ByteArray(1), alpha.key, alpha.keyId).toByteArray(),
                ),
            )
            assertEquals(400, badZip.status)

            // Missing multipart parts.
            val missing = s.postMultipart(
                "/v1/publishers/alpha/plugins",
                alpha.token,
                listOf("artifact" to IndexTestKit.mcosPackage(pluginManifest("com.example.x", "1.0.0"))),
            )
            assertEquals(400, missing.status)
        }
    }

    @Test
    fun `key registration validates the fingerprint against the public key`() {
        ServerFixture().use { s ->
            val alpha = s.createPublisherSession("alpha")
            val other = IndexTestKit.ed25519()
            val bogusFingerprint = IndexTestKit.sha256Hex(other.public.encoded)
            val body = """{"keyId":"key-bad","publisherId":"alpha",""" +
                """"publicKeyFingerprint":"$bogusFingerprint","algorithm":"Ed25519",""" +
                """"publicKeyEncoded":"${IndexTestKit.publicKeyB64(alpha.key)}",""" +
                """"createdAt":"${IndexTestKit.nowIso()}"}"""
            assertEquals(400, s.post("/v1/publishers/alpha/keys", alpha.token, body).status)
        }
    }
}
