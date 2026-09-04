package com.morainet.mcos.indexserver

import java.security.KeyPair

/** A signed-in publisher session (admin-created + one ACTIVE Ed25519 key). */
internal data class PublisherSession(
    val id: String,
    val token: String,
    val key: KeyPair,
    val keyId: String,
)

/** A submitted package: the upload response plus the artifact for later comparison. */
internal data class SubmissionRecord(
    val artifact: ByteArray,
    val response: ServerFixture.Raw,
) {
    val submissionId: String
        get() = response.field("submissionId") ?: error("no submissionId in ${response.body}")
}

/** A `plugin.json` that passes gates 2/4/5/6/10/11 when published first (12-index-server §9). */
internal fun pluginManifest(
    id: String,
    version: String,
    commandId: String = "hello.world",
): String = """
    {
      "id": "$id",
      "entry": "com.example.DemoPlugin",
      "version": "$version",
      "minRuntimeVersion": "0.2.0",
      "name": "$id",
      "description": "interop test plugin",
      "commands": [
        {"id": "$commandId", "sideEffectClass": "read", "version": "$version"}
      ],
      "tags": ["interop"]
    }
""".trimIndent()

/** A manifest whose command id violates gate 2 (reserved namespace). */
internal fun reservedNamespaceManifest(
    id: String,
    version: String = "1.0.0",
): String = """
    {
      "id": "$id",
      "entry": "com.example.DemoPlugin",
      "version": "$version",
      "minRuntimeVersion": "0.2.0",
      "commands": [
        {"id": "mcos.kernel", "sideEffectClass": "read", "version": "1.0.0"}
      ]
    }
""".trimIndent()

/** A manifest that triggers gate 5's monotonic-version failure once 1.0.0 is listed. */
internal fun downgradeManifest(id: String): String =
    pluginManifest(id = id, version = "0.9.0")

internal val DEFAULT_METADATA: String =
    """{"name":"Demo Plugin","summary":"an interop test plugin","justifications":{}}"""

// ── Flow helpers ─────────────────────────────────────────────────────────────

/** Admin-creates [publisherId], registers a fresh Ed25519 key under it. */
internal fun ServerFixture.createPublisherSession(publisherId: String, keyId: String = "key-1"): PublisherSession {
    val created = post("/v1/admin/publishers", adminToken, """{"id":"$publisherId","name":"$publisherId"}""").ok()
    val token = created.field("token") ?: error("no token in ${created.body}")
    val key = IndexTestKit.ed25519()
    val fingerprint = IndexTestKit.sha256Hex(key.public.encoded)
    val keyJson = """{"keyId":"$keyId","publisherId":"$publisherId",""" +
        """"publicKeyFingerprint":"$fingerprint","algorithm":"Ed25519",""" +
        """"publicKeyEncoded":"${IndexTestKit.publicKeyB64(key)}",""" +
        """"createdAt":"${IndexTestKit.nowIso()}"}"""
    post("/v1/publishers/$publisherId/keys", token, keyJson).ok()
    return PublisherSession(publisherId, token, key, keyId)
}

/** Registers a second key for [session]; returns the new keypair. */
internal fun ServerFixture.registerExtraKey(session: PublisherSession, keyId: String): KeyPair {
    val key = IndexTestKit.ed25519()
    val fingerprint = IndexTestKit.sha256Hex(key.public.encoded)
    val keyJson = """{"keyId":"$keyId","publisherId":"${session.id}",""" +
        """"publicKeyFingerprint":"$fingerprint","algorithm":"Ed25519",""" +
        """"publicKeyEncoded":"${IndexTestKit.publicKeyB64(key)}",""" +
        """"createdAt":"${IndexTestKit.nowIso()}"}"""
    post("/v1/publishers/${session.id}/keys", session.token, keyJson).ok()
    return key
}

/** Uploads a signed `.mcos` package for [session] (publisher submit, §5.2). */
internal fun ServerFixture.submitPackage(
    session: PublisherSession,
    manifest: String,
    metadata: String = DEFAULT_METADATA,
    signingKey: KeyPair = session.key,
    signingKeyId: String = session.keyId,
): SubmissionRecord {
    val artifact = IndexTestKit.mcosPackage(manifest)
    val signature = IndexTestKit.signatureJson(artifact, signingKey, signingKeyId)
    val raw = postMultipart(
        path = "/v1/publishers/${session.id}/plugins",
        token = session.token,
        fields = listOf(
            "artifact" to artifact,
            "metadata" to metadata.toByteArray(Charsets.UTF_8),
            "signature" to signature.toByteArray(Charsets.UTF_8),
        ),
    )
    return SubmissionRecord(artifact, raw)
}

/** Publisher publishes an APPROVED submission (→ LISTED). */
internal fun ServerFixture.publishSubmission(
    session: PublisherSession,
    packageId: String,
    submissionId: String,
): ServerFixture.Raw =
    post(
        "/v1/publishers/${session.id}/plugins/$packageId/submissions/$submissionId/publish",
        session.token,
        "{}",
    )

internal fun ServerFixture.adminApprove(packageId: String, submissionId: String): ServerFixture.Raw =
    post("/v1/admin/plugins/$packageId/submissions/$submissionId/approve", adminToken, "{}")

internal fun ServerFixture.adminReject(packageId: String, submissionId: String): ServerFixture.Raw =
    post("/v1/admin/plugins/$packageId/submissions/$submissionId/reject", adminToken, "{}")

internal fun ServerFixture.adminPublish(packageId: String): ServerFixture.Raw =
    post("/v1/admin/plugins/$packageId/publish", adminToken, "{}")

internal fun ServerFixture.adminUnlist(packageId: String): ServerFixture.Raw =
    post("/v1/admin/plugins/$packageId/unlist", adminToken, """{"reason":"investigation"}""")

internal fun ServerFixture.adminRevoke(packageId: String, reason: String = "POLICY_VIOLATION"): ServerFixture.Raw =
    post("/v1/admin/plugins/$packageId/revoke", adminToken, """{"reason":"$reason"}""")

internal fun ServerFixture.adminBlocklistAdd(packageId: String, reason: String = "POLICY_VIOLATION"): ServerFixture.Raw =
    post("/v1/admin/blocklist/entries", adminToken, """{"packageId":"$packageId","reason":"$reason"}""")

internal fun ServerFixture.adminBlocklistRemove(packageId: String): ServerFixture.Raw =
    delete("/v1/admin/blocklist/entries?packageId=$packageId&versionRange=*", adminToken)

internal fun ServerFixture.adminEmergencyRevokeKey(keyId: String, reason: String = "compromised"): ServerFixture.Raw =
    post("/v1/admin/keys/$keyId/emergency-revoke", adminToken, """{"reason":"$reason"}""")
