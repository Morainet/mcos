package com.morainet.mcos.marketplace

import com.morainet.mcos.security.ArtifactSignature
import com.morainet.mcos.security.PublisherKey
import com.morainet.mcos.security.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable record of one marketplace install (09-marketplace.md §7.0).
 *
 * Only terminal, restart-relevant facts are persisted — transient pipeline
 * states (DOWNLOADING/VERIFYING/…) are never written: a crash mid-install
 * reads back as [InstallState.NOT_INSTALLED], matching the pre-persistence
 * behaviour.
 *
 * The publisher key and signature envelope are **pinned** at install time.
 * The in-memory key store starts empty on every launch (§6.3 bootstrap is
 * a follow-up), so without a pinned key a restart could not re-verify the
 * staged artifact — and re-verification is mandatory: a persisted record
 * is a claim, never proof. Rehydration re-runs the full verify pipeline
 * against the pinned key before registering anything.
 */
@Serializable
data class PersistedInstallRecord(
    val packageId: String,
    val version: String,
    /** [InstallState] name; only INSTALLED / DISABLED are ever persisted. */
    val state: String,
    /** [com.morainet.mcos.runtime.core.plugin.TrustLevel] name at install. */
    val trustLevel: String,
    /** Epoch millis when the install pipeline last succeeded. */
    val installedAt: Long,
    /** Staged artifact file name (relative to the installer's download dir). */
    val artifactFileName: String,
    /** Signature envelope that verified the artifact at install. */
    val signature: ArtifactSignature,
    /** Publisher key pinned at install; re-seeds the key store on load. */
    val publisherKey: PublisherKey,
)

/**
 * File-backed store of [PersistedInstallRecord]s: a single JSON snapshot
 * written atomically with optional HMAC-SHA256 tamper-evidence
 * ([SnapshotFile] — same paradigm as the audit log's compaction). A
 * missing, corrupt or tampered file loads as **empty** — records fail
 * closed, no install is resurrected from untrusted bytes.
 */
class InstallRecordStore(
    /** Snapshot file. Parent directories are created on save. */
    val file: File,
    /** Tamper-evidence key; if null, the file carries no signature line. */
    val hmacKey: ByteArray? = null,
) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Load all records, or an empty list when nothing valid is on disk. */
    fun load(): List<PersistedInstallRecord> {
        val payload = SnapshotFile.read(file, hmacKey) ?: return emptyList()
        return try {
            json.decodeFromString(InstallRecordSnapshot.serializer(), payload).records
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Persist [records] atomically (full rewrite; the table is small). */
    fun save(records: List<PersistedInstallRecord>) {
        SnapshotFile.write(file, json.encodeToString(InstallRecordSnapshot(records)), hmacKey)
    }
}

@Serializable
private data class InstallRecordSnapshot(
    val records: List<PersistedInstallRecord> = emptyList(),
)
