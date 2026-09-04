package com.morainet.mcos.indexserver

import com.morainet.mcos.marketplace.BlocklistEntry
import com.morainet.mcos.marketplace.review.CiReviewReport
import com.morainet.mcos.security.PublisherKey
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * In-memory + atomically persisted index registry (12-index-server.md §3).
 *
 * Single process, single writer: every mutation goes through
 * [IndexRegistry.mutate] which applies the change under a lock and durably
 * writes the document (tmp + atomic rename) before serving the new state.
 *
 * Document layout — see {data-dir}/registry.json, recipes.json,
 * blocklist.json, revoked-keys.json, telemetry.ndjson, reports.ndjson,
 * audit.ndjson (12-index-server.md §3).
 */
internal class IndexRegistry private constructor(
    val root: Path,
    private var doc: RegistryDocument,
) {
    companion object {
        fun open(root: Path): IndexRegistry {
            Files.createDirectories(root)
            val file = root.resolve("registry.json")
            if (Files.exists(file)) {
                val doc = IndexJson.document.decodeFromString(
                    RegistryDocument.serializer(),
                    Files.readString(file),
                )
                return IndexRegistry(root, doc)
            }
            return IndexRegistry(root, RegistryDocument()).also { it.persistNow() }
        }
    }

    // ── Read ────────────────────────────────────────────────────────────────

    fun snapshot(): RegistrySnapshot = synchronized(lock) { buildSnapshot() }

    private fun buildSnapshot(): RegistrySnapshot {
        val listedOrApproved = doc.submissions.filter {
            it.state in setOf(SubmissionState.LISTED, SubmissionState.APPROVED)
        }
        val knownCommands = linkedSetOf<String>()
        val latestPerPackage = mutableMapOf<String, Submission>()
        for (submission in listedOrApproved) {
            knownCommands += submission.commandVersions.keys
            val existing = latestPerPackage[submission.packageId]
            if (existing == null || submission.sequence > existing.sequence) {
                latestPerPackage[submission.packageId] = submission
            }
        }
        return RegistrySnapshot(
            document = doc,
            knownCommandIds = knownCommands,
            latestListed = latestPerPackage,
            blocklist = doc.blocklistEntries,
            revokedKeys = doc.revokedKeys,
        )
    }

    // ── Mutations ───────────────────────────────────────────────────────────

    private val lock = Any()

    fun <T> mutate(block: (RegistryDocument) -> Pair<RegistryDocument, T>): T =
        synchronized(lock) {
            val (next, result) = block(doc)
            // Advance the in-memory state BEFORE persisting so that subsequent
            // reads inside this process observe the mutation even if the disk
            // write fails (read-after-write consistency).
            doc = next
            persist(next)
            result
        }

    private fun persistNow() = persist(doc)

    private fun persist(document: RegistryDocument) {
        val file = root.resolve("registry.json")
        val tmp = root.resolve("registry.json.tmp")
        Files.writeString(tmp, IndexJson.document.encodeToString(RegistryDocument.serializer(), document))
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}

/** Frozen registry view used by the HTTP handlers. */
internal class RegistrySnapshot(
    val document: RegistryDocument,
    val knownCommandIds: Set<String>,
    val latestListed: Map<String, Submission>,
    val blocklist: List<BlocklistEntry>,
    val revokedKeys: List<PublisherKey>,
) {
    fun publisher(id: String): Publisher? = document.publishers.firstOrNull { it.id == id }

    fun latestSubmissionFor(packageId: String, publisherId: String): Submission? =
        document.submissions
            .filter { it.packageId == packageId && it.publisherId == publisherId }
            .maxByOrNull { it.sequence }
}

/** Review pipeline state per 09 §5.0 + operator decisions (12-index-server.md §5.4). */
enum class SubmissionState {
    CI_REJECTED,
    HUMAN_REVIEW,
    APPROVED,
    REJECTED,
    LISTED,
    UNLISTED,
    REVOKED,
}

/**
 * Registry document (serialised to {data-dir}/registry.json).
 *
 * Publishers store only the HMAC-SHA256 of their token; the server compares
 * hashes and never stores plaintext tokens (12-index-server.md §4).
 */
@Serializable
internal data class RegistryDocument(
    val schema: String = "mcos-index-registry/v1",
    val nextSequence: Long = 1,
    val publishers: List<Publisher> = emptyList(),
    val submissions: List<Submission> = emptyList(),
    val blocklistEntries: List<BlocklistEntry> = emptyList(),
    val blocklistVersion: Long = 0,
    val blocklistIssuedAt: String = "",
    val revokedKeys: List<PublisherKey> = emptyList(),
)

@Serializable
internal data class Publisher(
    val id: String,
    val name: String,
    /** HMAC-SHA256 of the publisher token; null while a token has not been issued. */
    val tokenHash: String? = null,
    val keys: List<PublisherKey> = emptyList(),
) {
    fun activeKeys(): List<PublisherKey> =
        keys.filter { it.status.name == "ACTIVE" }
}

/** A submission is the immutable record of one review attempt of a plugin version. */
@Serializable
internal data class Submission(
    val submissionId: String,
    val sequence: Long,
    val packageId: String,
    val publisherId: String,
    val state: SubmissionState,
    val version: String,
    val minRuntimeVersion: String,
    /** command id → version, derived from the manifest (gates 5/10/11 input). */
    val commandVersions: Map<String, String>,
    /** SHA-256 (hex) of the artifact bytes. */
    val artifactSha256: String,
    /** Name of the artifact file under {data-dir}/artifacts/. */
    val artifactFile: String,
    /** Artifact file size in bytes. */
    val artifactSizeBytes: Long,
    val reviewReport: CiReviewReport,
    /**
     * Server-derived, authoritative [com.morainet.mcos.marketplace.PackageMetadata]
     * JSON (never the raw publisher payload — commands/permissions previews are
     * recomputed from the manifest so metadata cannot lie).
     */
    val metadataJson: String,
    val submittedAt: String,
    val updatedAt: String,
    val publisherTokenHash: String,
    val reviewer: String? = null,
    val reviewNote: String? = null,
    val downloadCount: Long = 0,
    val safetyScore: Double = 0.0,
    val blocked: Boolean = false,
) {
    fun listed(): Boolean = state == SubmissionState.LISTED && !blocked
}
