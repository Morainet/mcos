package com.morainet.mcos.security.permission

import com.morainet.mcos.security.SnapshotFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable snapshot of a [PermissionKernel]'s consent state.
 *
 * Session-scoped grants are deliberately **not** representable here — a
 * snapshot is only ever taken from durable state (see
 * [DefaultPermissionKernel]'s session filter), so a restart can never
 * resurrect a session grant.
 */
@Serializable
data class GrantSnapshot(
    /** Granted permissions: pluginId → permission names (durable only). */
    val grants: Map<String, List<String>> = emptyMap(),
    /** Command ids the user marked as auto-approved (lowercase). */
    val autoApprove: List<String> = emptyList(),
    /** Global "always confirm" override. */
    val alwaysConfirm: Boolean = false,
)

/**
 * Persistence port for [DefaultPermissionKernel]'s grant table
 * (08-security.md §5.1 grant table; the file-backed default follows the
 * [com.morainet.mcos.security.audit.FileAuditLog] paradigm: replay on load,
 * atomic rewrite on save, corrupt tolerance).
 *
 * The kernel holds the in-memory state; a store only mirrors it. The no-op
 * default ([NullGrantStore]) keeps the kernel pure-memory, which is the
 * behaviour every pre-existing construction site relies on.
 */
interface GrantStore {

    /**
     * Load the persisted snapshot, or `null` when nothing (valid) is on
     * disk. Corruption must surface as `null`, never as an exception —
     * losing grants fails **closed** (denials), which is the safe direction.
     */
    fun load(): GrantSnapshot?

    /**
     * Persist [snapshot]. Implementations swallow I/O errors: an unwritable
     * store degrades to memory-only, it must never break the grant path.
     */
    fun save(snapshot: GrantSnapshot)
}

/** No-op [GrantStore]: the kernel stays pure-memory (the default). */
object NullGrantStore : GrantStore {
    override fun load(): GrantSnapshot? = null
    override fun save(snapshot: GrantSnapshot) = Unit
}

/**
 * Single-document JSON snapshot of the grant table, written atomically
 * (tmp + rename) with optional HMAC-SHA256 tamper-evidence via
 * [SnapshotFile].
 *
 * When [hmacKey] is provided and the signature does not verify, [load]
 * returns `null` — a tampered grant table grants nothing.
 *
 * Writes are synchronous: the table is tiny and mutations are rare
 * (installs, consent flips), so a single-writer coroutine would be
 * ceremony without benefit — unlike the audit log's per-record throughput.
 */
class FileGrantStore(
    /** Snapshot file. Parent directories are created on save. */
    val file: File,
    /** Tamper-evidence key; if null, the file carries no signature line. */
    val hmacKey: ByteArray? = null,
) : GrantStore {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override fun load(): GrantSnapshot? {
        val payload = SnapshotFile.read(file, hmacKey) ?: return null
        return try {
            json.decodeFromString(GrantSnapshot.serializer(), payload)
        } catch (_: Exception) {
            null
        }
    }

    override fun save(snapshot: GrantSnapshot) {
        SnapshotFile.write(file, json.encodeToString(snapshot), hmacKey)
    }
}

