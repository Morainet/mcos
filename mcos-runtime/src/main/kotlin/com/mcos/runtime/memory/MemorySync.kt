package com.mcos.runtime.memory

import com.mcos.runtime.audit.AuditLog
import com.mcos.runtime.audit.RunOutcome
import com.mcos.runtime.audit.RunRecord
import com.mcos.sdk.MemoryCategory
import com.mcos.sdk.WriteStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A single syncable memory entry in transit between devices.
 * Matches [07-memory.md 11.0]: the server stores opaque blobs only — value
 * and vector clock travel together; E2E encryption/decryption is device-local.
 */
@Serializable
data class SyncEntry(
    val path: String,
    val value: JsonElement,
    val createdAt: Long,
    val tags: Set<String> = emptySet(),
    val category: MemoryCategory = MemoryCategory.OTHER,
    val vectorClock: VectorClock = VectorClock(),
    val ttlMs: Long? = null,
)

/**
 * Enterprise / OEM sync policy ([07-memory.md 11.3]).
 */
data class SyncPolicy(
    /** `disableCloudMemorySync` — blocks all memory sync globally. */
    val enabled: Boolean = true,
    /** `allowedSyncCategories` — `null` = all categories allowed. */
    val allowedCategories: Set<MemoryCategory>? = null,
)

/**
 * A concurrent-write conflict surfaced to the user ([07-memory.md 11.1]):
 * "Conflict: '公司地址' was changed on both devices. Keep local, remote, or both?"
 */
data class SyncConflict(
    val path: String,
    val localValue: JsonElement,
    val remoteValue: JsonElement,
    val category: MemoryCategory,
)

/** User's resolution for a [SyncConflict]. */
enum class ConflictResolution { KEEP_LOCAL, KEEP_REMOTE, KEEP_BOTH }

/**
 * Outcome of [MemorySync.importSnapshot].
 */
data class SyncReport(
    /** Paths written from the remote side (new path, or remote clock dominates). */
    val applied: List<String>,
    /** Paths where the local clock dominates — remote value discarded (silent). */
    val keptLocal: List<String>,
    /** Concurrent writes surfaced to the user for resolution. */
    val conflicts: List<SyncConflict>,
    /** Paths not processed: identical clocks, or policy-filtered. */
    val skipped: List<String>,
) {
    val total: Int get() = applied.size + keptLocal.size + conflicts.size + skipped.size
}

/**
 * Device-to-device memory sync ([07-memory.md 11]).
 *
 * Vector-clock last-writer-wins with user-surfaced concurrent conflicts:
 *
 * | Relationship | Resolution | UX |
 * |--------------|------------|-----|
 * | `local.isAfter(remote)` | keep local | silent |
 * | `remote.isAfter(local)` | apply remote | silent |
 * | concurrent | surface to user | keep local / remote / both |
 *
 * The server (Phase 3) is out of scope here: [exportSnapshot] produces the
 * payload a device would push (syncable entries only), [importSnapshot]
 * consumes the peer payload. Policy violations ([07-memory.md 11.3]) abort
 * the affected paths and are logged to [AuditLog].
 */
class MemorySync(
    private val store: MemoryStore,
    private val audit: AuditLog? = null,
) {

    /**
     * All entries allowed to leave this device — `syncable = true` only
     * ([07-memory.md 11.0]); `local_only` entries never leave the device.
     */
    suspend fun exportSnapshot(): List<SyncEntry> = store.listEntries()
        .filter { it.entry.syncable }
        .map {
            SyncEntry(
                path = it.path,
                value = it.entry.value,
                createdAt = it.entry.createdAt,
                tags = it.entry.tags,
                category = it.entry.category,
                vectorClock = it.entry.vectorClock,
                ttlMs = it.entry.ttlMs,
            )
        }

    /**
     * Merge a remote snapshot into this store, per the vector-clock LWW table.
     */
    suspend fun importSnapshot(
        remote: List<SyncEntry>,
        policy: SyncPolicy = SyncPolicy(),
    ): SyncReport = coroutineScope {
        val applied = mutableListOf<String>()
        val keptLocal = mutableListOf<String>()
        val conflicts = mutableListOf<SyncConflict>()
        val skipped = mutableListOf<String>()

        // §11.3 disableCloudMemorySync — global block, aborts the whole sync.
        if (!policy.enabled) {
            logPolicyViolation("disableCloudMemorySync")
            return@coroutineScope SyncReport(applied, keptLocal, conflicts, remote.map { it.path })
        }

        val local = store.listEntries().associateBy { it.path }

        for (entry in remote) {
            // §11.3 allowedSyncCategories — restrict to low-sensitivity categories.
            if (policy.allowedCategories != null && entry.category !in policy.allowedCategories) {
                logPolicyViolation("allowedSyncCategories:${entry.category}")
                skipped.add(entry.path)
                continue
            }

            val mine = local[entry.path]?.entry
            when {
                // New path — nothing to resolve against.
                mine == null -> {
                    store.applySyncEntry(entry.path, entry)
                    applied.add(entry.path)
                }
                // Same version — nothing changed (idempotent re-import).
                entry.vectorClock == mine.vectorClock -> skipped.add(entry.path)
                // Remote dominates — remote is newer, silent overwrite.
                entry.vectorClock.isAfter(mine.vectorClock) -> {
                    store.applySyncEntry(entry.path, entry)
                    applied.add(entry.path)
                }
                // Local dominates — local is newer, remote discarded silently.
                mine.vectorClock.isAfter(entry.vectorClock) -> keptLocal.add(entry.path)
                // Concurrent — neither dominates: surface to the user.
                else -> conflicts.add(
                    SyncConflict(entry.path, mine.value, entry.value, entry.category)
                )
            }
        }
        SyncReport(applied, keptLocal, conflicts, skipped)
    }

    /**
     * Apply a user's resolution for a previously recorded [SyncConflict]
     * ([07-memory.md 11.1]: "Keep local, remote, or both?").
     *
     * @return [WriteStatus.UPDATED] when the store changed, `null` for
     *   [ConflictResolution.KEEP_LOCAL] (nothing written).
     */
    suspend fun resolveConflict(
        path: String,
        remote: SyncEntry,
        choice: ConflictResolution,
    ): WriteStatus? = when (choice) {
        ConflictResolution.KEEP_LOCAL -> null
        ConflictResolution.KEEP_REMOTE -> {
            store.applySyncEntry(path, remote, preserveLocalHistory = false)
            WriteStatus.UPDATED
        }
        ConflictResolution.KEEP_BOTH -> {
            // Remote value becomes current; the local value is soft-deleted
            // into history so both are retained.
            store.applySyncEntry(path, remote, preserveLocalHistory = true)
            WriteStatus.UPDATED
        }
    }

    private fun logPolicyViolation(rule: String) {
        audit?.append(
            RunRecord(
                runId = "sync:${System.currentTimeMillis()}",
                timestamp = System.currentTimeMillis(),
                source = "MEMORY_SYNC",
                ir = "POLICY_VIOLATION:$rule",
                outcome = RunOutcome.FAILED,
            )
        )
    }
}
