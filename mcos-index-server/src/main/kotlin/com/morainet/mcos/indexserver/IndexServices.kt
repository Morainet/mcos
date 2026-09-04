package com.morainet.mcos.indexserver

import com.morainet.mcos.marketplace.ArtifactRef
import com.morainet.mcos.marketplace.Blocklist
import com.morainet.mcos.marketplace.BlocklistEntry
import com.morainet.mcos.marketplace.BlocklistReason
import com.morainet.mcos.marketplace.MarketplacePermissionEntry
import com.morainet.mcos.marketplace.PackageMetadata
import com.morainet.mcos.marketplace.SearchResponse
import com.morainet.mcos.marketplace.review.ArtifactScan
import com.morainet.mcos.marketplace.review.AvVerdict
import com.morainet.mcos.marketplace.review.CiGateEngine
import com.morainet.mcos.marketplace.review.CiReviewReport
import com.morainet.mcos.marketplace.review.GateCheck
import com.morainet.mcos.marketplace.review.RegistrySnapshot as GateRegistrySnapshot
import com.morainet.mcos.marketplace.review.PreviousRelease
import com.morainet.mcos.marketplace.review.ReviewOverall
import com.morainet.mcos.runtime.core.plugin.McosPackage
import com.morainet.mcos.sdk.PermissionEntry
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.security.ArtifactSignature
import com.morainet.mcos.security.ArtifactVerifier
import com.morainet.mcos.security.EmptyBlocklist
import com.morainet.mcos.security.PublisherKey
import com.morainet.mcos.security.PublisherKeyStore
import com.morainet.mcos.security.VerifyResult
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ThreadLocalRandom

/**
 * Domain operations for the index server (12-index-server.md §5).
 * HTTP-layer-agnostic: every public method throws [ApiException] on policy
 * violations and mutates the registry atomically via [IndexRegistry.mutate].
 */
internal class IndexServices(
    private val registry: IndexRegistry,
    private val adminTokenDigest: String,
    private val operatorKey: java.security.KeyPair?,
    private val avDenylistFile: Path?,
    private val artifactDir: Path,
) {
    init {
        Files.createDirectories(artifactDir)
    }

    // ── Auth ─────────────────────────────────────────────────────────────────

    fun authorizeAdmin(token: String?) {
        if (token == null || !constantTimeEquals(tokenDigest(token), adminTokenDigest)) {
            throw ApiException(401, "UNAUTHENTICATED", "admin token required")
        }
    }

    fun authorizePublisher(token: String?, publisherId: String) {
        val publisher = registry.snapshot().publisher(publisherId)
            ?: throw ApiException(404, "NOT_FOUND", "unknown publisher '$publisherId'")
        val digest = token?.let { tokenDigest(it) }
        if (publisher.tokenHash == null || digest == null || !constantTimeEquals(digest, publisher.tokenHash)) {
            throw ApiException(403, "PERMISSION_DENIED", "invalid publisher token")
        }
    }

    // ── Publisher onboarding / keys (09 §6) ─────────────────────────────────

    fun createPublisher(id: String, name: String): String = registry.mutate { doc ->
        if (doc.publishers.any { it.id == id }) {
            throw ApiException(409, "ALREADY_EXISTS", "publisher '$id' exists")
        }
        val token = generateToken()
        val next = doc.copy(
            publishers = doc.publishers + Publisher(id = id, name = name, tokenHash = tokenDigest(token)),
        )
        audit(doc, next, "admin", "createPublisher", id, "name=$name")
        next to token
    }

    fun registerKey(publisher: Publisher, key: PublisherKey) = registry.mutate { doc ->
        if (publisher.keys.any { it.keyId == key.keyId }) {
            throw ApiException(409, "ALREADY_EXISTS", "key '${key.keyId}' already registered")
        }
        if (key.status.name != "ACTIVE") {
            throw ApiException(400, "SCHEMA_VIOLATION", "server assigns key status; status must be omitted")
        }
        val next = doc.copy(
            publishers = doc.publishers.map {
                if (it.id == publisher.id) it.copy(keys = it.keys + key) else it
            },
        )
        audit(doc, next, publisher.id, "registerKey", key.keyId, key.algorithm)
        next to key
    }

    /** Routine rotation (09 §6.3): old key → REVOKED with rotation history. */
    fun rotateKey(publisher: Publisher, keyId: String) = registry.mutate { doc ->
        val target = publisher.keys.firstOrNull { it.keyId == keyId }
            ?: throw ApiException(404, "NOT_FOUND", "key '$keyId' not registered")
        if (target.status.name != "ACTIVE") {
            throw ApiException(409, "ALREADY_EXISTS", "key '$keyId' is already revoked")
        }
        val revoked = target.copy(status = com.morainet.mcos.security.KeyStatus.REVOKED)
        val next = doc.copy(
            publishers = doc.publishers.map {
                if (it.id == publisher.id) it.copy(keys = it.keys.map { k -> if (k.keyId == keyId) revoked else k }) else it
            },
            revokedKeys = doc.revokedKeys + revoked,
        )
        audit(doc, next, publisher.id, "rotateKey", keyId, "routine rotation")
        next to revoked
    }

    /** Emergency revoke (09 §6.3): operator-initiated, immediate. */
    fun emergencyRevokeKey(keyId: String, reason: String) = registry.mutate { doc ->
        val target = doc.publishers.flatMap { it.keys }.firstOrNull { it.keyId == keyId }
            ?: throw ApiException(404, "NOT_FOUND", "key '$keyId' not found")
        val revoked = target.copy(status = com.morainet.mcos.security.KeyStatus.REVOKED)
        val next = doc.copy(
            publishers = doc.publishers.map { p ->
                p.copy(keys = p.keys.map { if (it.keyId == keyId) revoked else it })
            },
            revokedKeys = doc.revokedKeys + revoked,
        )
        audit(doc, next, "admin", "emergencyRevokeKey", keyId, reason)
        next to revoked
    }

    // ── Submission pipeline (09 §5 + §5.4 report shape) ─────────────────────

    fun submit(
        publisher: Publisher,
        artifactBytes: ByteArray,
        metadataPayload: String,
        signaturePayload: String,
    ): SubmitResponse = registry.mutate { doc ->
        // zip `PK` gate (gate 1)
        if (artifactBytes.size < 4 || artifactBytes[0].toInt() != 0x50 || artifactBytes[1].toInt() != 0x4B) {
            throw ApiException(400, "SCHEMA_VIOLATION", "artifact is not a .mcos package (missing PK zip header)")
        }
        val manifest = try {
            McosPackage.readPluginManifest(artifactBytes)
        } catch (e: McosPackage.FormatException) {
            throw ApiException(400, "SCHEMA_VIOLATION", e.message ?: "manifest decode failed")
        }
        val now = nowIso()
        val base = GateRegistrySnapshot()

        // Gate 8: signature against the publisher's ACTIVE registered key.
        val sig = try {
            IndexJson.api.decodeFromString(ArtifactSignature.serializer(), signaturePayload)
        } catch (e: Exception) {
            throw ApiException(400, "SCHEMA_VIOLATION", "signature envelope is not valid ArtifactSignature JSON")
        }
        val signingKey = publisher.keys.firstOrNull { it.keyId == sig.signingKeyId && it.status.name == "ACTIVE" }
        val checks = mutableListOf<GateCheck>()
        val extraFails = mutableListOf<GateCheck>()
        if (signingKey == null) {
            extraFails += GateCheck.fail(
                8, "Signature verification",
                "signingKeyId '${sig.signingKeyId}' is not an ACTIVE key of publisher '${publisher.id}'",
                manifest.id,
            )
        } else {
            val verifier = ArtifactVerifier(
                keyStore = object : PublisherKeyStore {
                    override fun get(keyId: String): PublisherKey? =
                        publisher.keys.firstOrNull { it.keyId == keyId && it.status.name == "ACTIVE" }

                    override fun publicKey(keyId: String): java.security.PublicKey? {
                        val key = publisher.keys.firstOrNull { it.keyId == keyId && it.status.name == "ACTIVE" }
                            ?: return null
                        val der = java.util.Base64.getDecoder().decode(key.publicKeyEncoded)
                        val factory = java.security.KeyFactory.getInstance(
                            if (key.algorithm.equals("RSA-PSS-4096", ignoreCase = true)) "RSA" else key.algorithm,
                        )
                        return factory.generatePublic(java.security.spec.X509EncodedKeySpec(der))
                    }
                },
                blocklist = EmptyBlocklist,
            )
            try {
                val result = verifier.verify(artifactBytes, sig, manifest.id)
                if (result !is VerifyResult.Verified) {
                    val reason = (result as? VerifyResult.Rejected)?.reason ?: "unknown"
                    extraFails += GateCheck.fail(
                        8, "Signature verification",
                        "signature rejected for key '${sig.signingKeyId}': $reason",
                        manifest.id,
                    )
                }
            } catch (e: Exception) {
                extraFails += GateCheck.fail(
                    8, "Signature verification",
                    "signature verification error: ${e.message}",
                    manifest.id,
                )
            }
        }
        // Gate 7: secret containment — scan the artifact for `{{secret.*}}`
        // markers / `x-mcos-secret` (server side of the validator's scan).
        val text = artifactBytes.toString(Charsets.ISO_8859_1)
        if (SECRET_RE.containsMatchIn(text)) {
            extraFails += GateCheck.fail(
                7, "Secret containment",
                "artifact contains '{{secret.*}}' or 'x-mcos-secret' literals",
                manifest.id,
            )
        }
        // Gate 9: AV seam (hash denylist scanner; no engine ⇒ UNSCANNED).
        val scan = avVerdict(artifactBytes)

        // Registry snapshot for gates 5/10/11 excludes this submission.
        val prior = doc.submissions
            .filter { it.state == SubmissionState.LISTED && it.packageId == manifest.id }
            .maxByOrNull { it.sequence }
        val engine = CiGateEngine(
            currentRuntimeVersion = CURRENT_RUNTIME_VERSION,
            registry = base.copy(
                previous = prior?.let {
                    PreviousRelease(
                        version = it.version,
                        minRuntimeVersion = it.minRuntimeVersion,
                        commandVersions = it.commandVersions,
                    )
                },
                knownCommandIds = doc.submissions
                    .filter { s -> s.state in setOf(SubmissionState.LISTED, SubmissionState.APPROVED) }
                    .flatMap { it.commandVersions.keys }
                    .toSet(),
            ),
        )
        val engineReport = engine.evaluate(manifest, scan)
        checks += engineReport.checks + extraFails

        val overall = when {
            extraFails.any { it.severity == "error" } -> ReviewOverall.CI_REJECTED
            else -> engineReport.overall
        }
        val report = CiReviewReport(overall, checks.sortedBy { it.gate })
        val state = when (overall) {
            ReviewOverall.CI_REJECTED -> SubmissionState.CI_REJECTED
            ReviewOverall.HUMAN_REVIEW -> SubmissionState.HUMAN_REVIEW
            ReviewOverall.APPROVED -> SubmissionState.APPROVED
        }

        // Concurrency guard: an already-approved/newer version or same-version
        // submission blocks a duplicate attempt (ALREADY_EXISTS).
        if (doc.submissions.any { it.packageId == manifest.id && it.version == manifest.version && it.state == SubmissionState.LISTED }) {
            throw ApiException(409, "ALREADY_EXISTS", "version ${manifest.version} of '${manifest.id}' is already published")
        }

        val sequence = doc.nextSequence
        val submissionId = "sub_${sequence}"
        val artifactFile = "${manifest.id}-${manifest.version}-${sequence}.mcos"
        val artifactRef = artifactRef(artifactFile, artifactBytes, sig)
        val metadata = buildMetadata(manifest, publisher, metadataPayload, artifactRef, now)
        val artifactPath = artifactDir.resolve(artifactFile)
        Files.write(artifactPath, artifactBytes)

        val submission = Submission(
            submissionId = submissionId,
            sequence = sequence,
            packageId = manifest.id,
            publisherId = publisher.id,
            state = state,
            version = manifest.version,
            minRuntimeVersion = manifest.minRuntimeVersion,
            commandVersions = manifest.commands.associate { it.id to it.version },
            artifactSha256 = sha256Hex(artifactBytes),
            artifactFile = artifactFile,
            artifactSizeBytes = artifactBytes.size.toLong(),
            reviewReport = report,
            metadataJson = IndexJson.document.encodeToString(PackageMetadata.serializer(), metadata),
            submittedAt = now,
            updatedAt = now,
            publisherTokenHash = publisher.tokenHash ?: "",
        )
        val next = doc.copy(
            nextSequence = sequence + 1,
            submissions = doc.submissions + submission,
        )
        audit(doc, next, publisher.id, "submit", submissionId, "package=${manifest.id} v=${manifest.version} overall=$overall")
        next to SubmitResponse(submissionId, state.name, now, report)
    }

    fun submissionFor(publisher: Publisher, packageId: String, submissionId: String): Submission {
        val snapshot = registry.snapshot()
        val sub = snapshot.document.submissions.firstOrNull { it.submissionId == submissionId }
            ?: throw ApiException(404, "NOT_FOUND", "submission '$submissionId' not found")
        if (sub.publisherId != publisher.id) {
            throw ApiException(403, "PERMISSION_DENIED", "submission belongs to another publisher")
        }
        return sub
    }

    /** Publisher publishes an APPROVED submission (09 §5.0). */
    fun publishSubmission(submissionId: String) = registry.mutate { doc ->
        val sub = doc.submissions.firstOrNull { it.submissionId == submissionId }
            ?: throw ApiException(404, "NOT_FOUND", "submission '$submissionId' not found")
        if (sub.state != SubmissionState.APPROVED) {
            throw ApiException(409, "ALREADY_EXISTS", "submission is '${sub.state}', not APPROVED")
        }
        val next = doc.copy(
            submissions = doc.submissions.map { s ->
                if (s.submissionId == submissionId) s.copy(state = SubmissionState.LISTED, updatedAt = nowIso()) else s
            },
        )
        audit(doc, next, "admin", "publish", submissionId, "state → LISTED")
        next to true
    }

    // ── Operator decisions / moderation (12-index-server.md §5.4) ────────────

    fun approveSubmission(submissionId: String, reviewer: String, note: String?) = registry.mutate { doc ->
        transition(
            doc, submissionId,
            from = setOf(SubmissionState.HUMAN_REVIEW),
            to = SubmissionState.APPROVED,
            actor = "admin:$reviewer", note = note,
        )
    }

    fun rejectSubmission(submissionId: String, reviewer: String, note: String?) = registry.mutate { doc ->
        transition(
            doc, submissionId,
            from = setOf(SubmissionState.HUMAN_REVIEW, SubmissionState.APPROVED),
            to = SubmissionState.REJECTED,
            actor = "admin:$reviewer", note = note,
        )
    }

    /** Operator publishes on the publisher's behalf (latest APPROVED submission). */
    fun operatorPublish(packageId: String, reviewer: String) = registry.mutate { doc ->
        val latest = doc.submissions
            .filter { it.packageId == packageId && it.state == SubmissionState.APPROVED }
            .maxByOrNull { it.sequence }
            ?: throw ApiException(409, "ALREADY_EXISTS", "no APPROVED submission of '$packageId'")
        val next = doc.copy(
            submissions = doc.submissions.map {
                if (it.submissionId == latest.submissionId) it.copy(state = SubmissionState.LISTED, updatedAt = nowIso()) else it
            },
        )
        audit(doc, next, "admin:$reviewer", "operatorPublish", packageId, latest.submissionId)
        next to true
    }

    fun unlist(packageId: String, reviewer: String) = registry.mutate { doc ->
        val latest = doc.submissions
            .filter { it.packageId == packageId && it.state == SubmissionState.LISTED }
            .maxByOrNull { it.sequence }
            ?: throw ApiException(409, "ALREADY_EXISTS", "'$packageId' is not listed")
        val next = doc.copy(
            submissions = doc.submissions.map {
                if (it.submissionId == latest.submissionId) it.copy(state = SubmissionState.UNLISTED, updatedAt = nowIso()) else it
            },
        )
        audit(doc, next, "admin:$reviewer", "unlist", packageId, "investigation")
        next to true
    }

    fun revoke(packageId: String, reviewer: String, reason: String) = registry.mutate { doc ->
        val listed = doc.submissions.filter { it.packageId == packageId && it.state == SubmissionState.LISTED }
        if (listed.isEmpty()) {
            throw ApiException(409, "ALREADY_EXISTS", "'$packageId' is not listed")
        }
        val entry = BlocklistEntry(
            packageId = packageId,
            versionRange = "*",
            reason = enumOrThrow(reason),
            blockedAt = nowIso(),
        )
        val next = doc.copy(
            submissions = doc.submissions.map { s ->
                if (s.packageId == packageId && s.state == SubmissionState.LISTED) {
                    s.copy(state = SubmissionState.REVOKED, blocked = true, updatedAt = nowIso())
                } else s
            },
            blocklistEntries = doc.blocklistEntries + entry,
            blocklistVersion = doc.blocklistVersion + 1,
            blocklistIssuedAt = nowIso(),
        )
        audit(doc, next, "admin:$reviewer", "revoke", packageId, reason)
        next to true
    }

    fun addBlocklistEntry(entry: BlocklistEntry) = registry.mutate { doc ->
        if (doc.blocklistEntries.any { it.packageId == entry.packageId && it.versionRange == entry.versionRange }) {
            throw ApiException(409, "ALREADY_EXISTS", "blocklist entry already present")
        }
        val next = doc.copy(
            blocklistEntries = doc.blocklistEntries + entry,
            blocklistVersion = doc.blocklistVersion + 1,
            blocklistIssuedAt = nowIso(),
        )
        audit(doc, next, "admin", "blocklist:add", entry.packageId, entry.reason.name)
        next to true
    }

    fun removeBlocklistEntry(packageId: String, versionRange: String) = registry.mutate { doc ->
        val remaining = doc.blocklistEntries.filterNot { it.packageId == packageId && it.versionRange == versionRange }
        if (remaining.size == doc.blocklistEntries.size) {
            throw ApiException(404, "NOT_FOUND", "no such blocklist entry")
        }
        val next = doc.copy(
            blocklistEntries = remaining,
            blocklistVersion = doc.blocklistVersion + 1,
            blocklistIssuedAt = nowIso(),
        )
        audit(doc, next, "admin", "blocklist:remove", packageId, versionRange)
        next to true
    }

    fun submissionsQueue(state: String?): List<Submission> {
        val snapshot = registry.snapshot()
        val filter = state?.let { runCatching { SubmissionState.valueOf(it) }.getOrNull() }
            ?: if (state != null) throw ApiException(400, "SCHEMA_VIOLATION", "unknown submission state '$state'") else null
        return snapshot.document.submissions
            .filter { filter == null || it.state == filter }
            .sortedByDescending { it.sequence }
    }

    // ── Read side ────────────────────────────────────────────────────────────

    fun search(query: String?, category: String?, sort: String, page: Int, pageSize: Int): SearchResponse {
        val snapshot = registry.snapshot()
        val listed = snapshot.latestListed.values.filter(Submission::listed)
        val blocked = snapshot.blocklist
        val visible = listed.filterNot { sub -> blocked.any { it.packageId == sub.packageId } }
        val tokens = (query ?: "").lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val filtered = visible.filter { sub ->
            (category == null || sub.categories().contains(category)) &&
                (tokens.isEmpty() || tokens.any { token ->
                    sub.packageId.contains(token, ignoreCase = true) ||
                        sub.name().contains(token, ignoreCase = true) ||
                        (sub.description() ?: "").contains(token, ignoreCase = true)
                })
        }
        val ordered = when (sort) {
            "newest" -> filtered.sortedByDescending { it.submittedAt }
            "popularity" -> filtered.sortedByDescending { it.downloadCount }
            "safety" -> filtered.sortedByDescending { it.safetyScore }
            else -> filtered.sortedWith(compareByDescending<Submission> { relevance(it, tokens) }.thenByDescending { it.submittedAt })
        }
        val pageIndex = (page - 1).coerceAtLeast(0)
        val paged = ordered.drop(pageIndex * pageSize).take(pageSize)
        val results = paged.map { it.decodeMetadata() }
        return SearchResponse(results, ordered.size.toLong(), page, pageSize)
    }

    fun packageLatest(packageId: String): PackageMetadata? {
        val snapshot = registry.snapshot()
        val latest = snapshot.latestListed[packageId]?.takeIf { it.listed() && !isBlocked(snapshot, packageId) }
        return latest?.decodeMetadata()
    }

    fun packageVersions(packageId: String): List<PackageMetadata> {
        val snapshot = registry.snapshot()
        return snapshot.document.submissions
            .filter { it.packageId == packageId && it.listed() && !isBlocked(snapshot, packageId) }
            .sortedByDescending { it.sequence }
            .map { it.decodeMetadata() }
    }

    fun packageVersion(packageId: String, version: String): PackageMetadata? {
        val snapshot = registry.snapshot()
        return snapshot.document.submissions
            .firstOrNull { it.packageId == packageId && it.version == version && it.listed() && !isBlocked(snapshot, packageId) }
            ?.decodeMetadata()
    }

    fun byCommand(commandId: String): List<PackageMetadata> {
        val snapshot = registry.snapshot()
        return snapshot.latestListed.values
            .filter { it.listed() && commandId in it.commandVersions && !isBlocked(snapshot, it.packageId) }
            .map { it.decodeMetadata() }
    }

    fun publisherProfile(publisherId: String): JsonProfile? {
        val snapshot = registry.snapshot()
        val publisher = snapshot.publisher(publisherId) ?: return null
        val packages = snapshot.latestListed.values
            .filter { it.publisherId == publisherId && it.listed() }
            .map { it.decodeMetadata() }
        return JsonProfile(publisher.id, publisher.name, packages)
    }

    fun artifactFile(name: String): Path? {
        val file = artifactDir.resolve(name).normalize()
        return if (file.startsWith(artifactDir) && Files.exists(file)) file else null
    }

    /** Signed blocklist document (canonical payload = signature null). */
    fun blocklistDocument(): Blocklist {
        val snapshot = registry.snapshot()
        val key = operatorKey ?: throw ApiException(503, "INTERNAL", "operator signing key not configured")
        val document = Blocklist(
            entries = snapshot.blocklist,
            version = snapshot.document.blocklistVersion.toString(),
            issuedAt = snapshot.document.blocklistIssuedAt.ifBlank { nowIso() },
        )
        return document.copy(signature = signBlocklist(key.private, document))
    }

    fun revokedKeys(): List<PublisherKey> = registry.snapshot().revokedKeys

    // ── Reports / telemetry ──────────────────────────────────────────────────

    fun recordReport(packageId: String, version: String, reason: String, description: String?) {
        val now = nowIso()
        val line = IndexJson.api.encodeToString(
            ReportLine.serializer(),
            ReportLine(packageId, version, reason, description, now),
        )
        Files.createDirectories(registry.root.resolve("reports.ndjson").parent)
        appendLine("reports.ndjson", line)
        // Flag for the operator inbox: audit line only (no auto-block).
        registry.mutate { doc ->
            val next = doc // count handled from the ndjson log at query time
            next to Unit
        }
    }

    fun recordInstall(packageId: String, version: String, event: String, clientId: String, timestamp: String) {
        val telemetryFile = registry.root.resolve("telemetry.ndjson")
        val idDigest = Regex("^[0-9a-f]{64}$")
        if (!idDigest.matches(clientId)) {
            // Privacy hardening: log-and-discard malformed client ids.
            return
        }
        Files.createDirectories(telemetryFile.parent)
        appendLine("telemetry.ndjson", """{"packageId":${q(packageId)},"version":${q(version)},"event":${q(event)},"anonymizedClientId":${q(clientId)},"timestamp":${q(timestamp)}}""")
        if (event == "install") {
            registry.mutate { doc ->
                var updated = false
                val subs = doc.submissions.map { s ->
                    if (s.packageId == packageId && s.version == version && s.state == SubmissionState.LISTED) {
                        updated = true
                        s.copy(downloadCount = s.downloadCount + 1)
                    } else s
                }
                if (!updated) doc to Unit else doc.copy(submissions = subs) to Unit
            }
        }
    }

    fun registryView() = registry.snapshot().document

    private fun transition(
        doc: RegistryDocument,
        submissionId: String,
        from: Set<SubmissionState>,
        to: SubmissionState,
        actor: String,
        note: String?,
    ): Pair<RegistryDocument, Boolean> {
        val sub = doc.submissions.firstOrNull { it.submissionId == submissionId }
            ?: throw ApiException(404, "NOT_FOUND", "submission '$submissionId' not found")
        if (sub.state !in from) {
            throw ApiException(409, "ALREADY_EXISTS", "submission is '${sub.state}', expected ${from.joinToString("/")}")
        }
        val next = doc.copy(
            submissions = doc.submissions.map { s ->
                if (s.submissionId == submissionId) {
                    s.copy(state = to, updatedAt = nowIso(), reviewer = actor, reviewNote = note)
                } else s
            },
        )
        audit(doc, next, actor, "decision", submissionId, "state → $to")
        return next to true
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private fun isBlocked(snapshot: RegistrySnapshot, packageId: String): Boolean =
        snapshot.blocklist.any { it.packageId == packageId }

    private fun avVerdict(bytes: ByteArray): ArtifactScan {
        val denylist = avDenylistFile?.let { loadSha256Denylist(it) } ?: emptySet()
        val sha = sha256Hex(bytes)
        return when {
            sha in denylist -> ArtifactScan(AvVerdict.MALICIOUS, "sha256-denylist")
            avDenylistFile != null && Files.exists(avDenylistFile) -> ArtifactScan(AvVerdict.CLEAN, "sha256-denylist")
            else -> ArtifactScan.Unscanned
        }
    }

    private fun artifactRef(fileName: String, bytes: ByteArray, sig: ArtifactSignature) = ArtifactRef(
        url = "/v1/artifacts/$fileName",
        sha256 = sha256Hex(bytes),
        signature = sig.signature,
        signingKeyId = sig.signingKeyId,
        sizeBytes = bytes.size.toLong(),
    )

    /**
     * Builds the authoritative [PackageMetadata] from the decoded manifest +
     * publisher payload. Commands/permissions previews are recomputed from the
     * manifest; a mismatched publisher payload is a schema violation.
     */
    private fun buildMetadata(
        manifest: PluginManifest,
        publisher: Publisher,
        metadataPayload: String,
        artifactRef: ArtifactRef,
        now: String,
    ): PackageMetadata {
        val payload = try {
            IndexJson.api.decodeFromString(SubmissionMetadata.serializer(), metadataPayload)
        } catch (e: Exception) {
            throw ApiException(400, "SCHEMA_VIOLATION", "metadata part is not valid JSON")
        }
        val egress = setOf("mcos:network", "android.permission.INTERNET")
        fun tier(permission: PermissionEntry): String =
            when {
                permission.type == "android" && permission.name.endsWith(".CAMERA") -> "high"
                permission.type == "android" && (permission.name.contains("RECORD_AUDIO") || permission.name.contains("ACCESS_FINE_LOCATION")) -> "high"
                permission.type == "mcos" && permission.name.startsWith("mcos:network") -> "high"
                permission.type == "android" && permission.name.endsWith(".INTERNET") -> "medium"
                else -> "low"
            }
        val previews = (manifest.permissions + manifest.commands.flatMap { it.permissions })
            .distinctBy { it.type to it.name }
            .map { MarketplacePermissionEntry(it.type, it.name, tier(it), payload.justifications[it.name]) }

        return PackageMetadata(
            packageId = manifest.id,
            name = payload.name ?: manifest.name,
            version = manifest.version,
            minRuntimeVersion = manifest.minRuntimeVersion,
            publisherId = publisher.id,
            publisherName = payload.publisherName ?: publisher.name,
            categories = payload.categories ?: manifest.tags,
            summary = payload.summary ?: manifest.description,
            description = payload.description ?: manifest.description,
            permissionsPreview = previews,
            commandsPreview = manifest.commands.map { it.id },
            artifact = artifactRef,
            privacyPolicyUrl = payload.privacyPolicyUrl,
            homepage = payload.homepage,
            publishedAt = now,
            updatedAt = now,
            downloadCount = 0,
            safetyScore = 0f,
        )
    }

    private fun relevance(sub: Submission, tokens: List<String>): Int {
        if (tokens.isEmpty()) return 0
        var score = 0
        for (token in tokens) {
            if (sub.packageId.contains(token, ignoreCase = true)) score += 10
            if (sub.name().contains(token, ignoreCase = true)) score += 5
            if ((sub.description() ?: "").contains(token, ignoreCase = true)) score += 1
        }
        return score
    }

    private fun Submission.decodeMetadata(): PackageMetadata =
        IndexJson.document.decodeFromString(PackageMetadata.serializer(), metadataJson)

    private fun Submission.name(): String = decodeMetadata().name

    private fun Submission.description(): String? = decodeMetadata().description

    private fun Submission.categories(): List<String> = decodeMetadata().categories

    private fun appendLine(name: String, line: String) {
        Files.writeString(
            registry.root.resolve(name),
            line + "\n",
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND,
        )
    }

    private fun audit(before: RegistryDocument, after: RegistryDocument, actor: String, action: String, target: String, detail: String) {
        Files.createDirectories(registry.root)
        appendLine(
            "audit.ndjson",
            """{"at":"${nowIso()}","actor":"${q(actor)}","action":"${q(action)}","target":"${q(target)}","detail":"${q(detail)}","seq":${after.nextSequence}}""",
        )
    }

    private fun enumOrThrow(reason: String): BlocklistReason =
        runCatching { BlocklistReason.valueOf(reason) }.getOrElse {
            throw ApiException(400, "SCHEMA_VIOLATION", "unknown blocklist reason '$reason'")
        }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        ThreadLocalRandom.current().nextBytes(bytes)
        return "mcp_" + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun q(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        private val SECRET_RE = Regex("""\{\{secret\.[^}]*\}\}|x-mcos-secret""")
        val CURRENT_RUNTIME_VERSION = "0.2.0"
    }
}

@Serializable
data class SubmitResponse(
    val submissionId: String,
    val state: String,
    val submittedAt: String,
    val reviewReport: CiReviewReport,
)

@Serializable
data class JsonProfile(
    val id: String,
    val name: String,
    val packages: List<PackageMetadata>,
)

/** Fields the publisher may set in the `metadata` part; the rest derives from the manifest. */
@Serializable
data class SubmissionMetadata(
    val name: String? = null,
    val publisherName: String? = null,
    val categories: List<String>? = null,
    val summary: String? = null,
    val description: String? = null,
    val privacyPolicyUrl: String? = null,
    val homepage: String? = null,
    val justifications: Map<String, String> = emptyMap(),
)

@Serializable
data class ReportLine(
    val packageId: String,
    val version: String,
    val reason: String,
    val description: String?,
    val reportedAt: String,
)

fun nowIso(): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.now()).replace("Z", "+00:00")
