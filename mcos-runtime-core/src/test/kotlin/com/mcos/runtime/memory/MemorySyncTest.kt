package com.mcos.runtime.memory

import com.mcos.runtime.audit.InMemoryAuditLog
import com.mcos.sdk.MemoryCategory
import com.mcos.sdk.WriteStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * Conformance tests for Memory Sync ([07-memory.md 11]).
 *
 * V1-V4: VectorClock semantics ([07-memory.md 11.1]).
 * S1-S15: sync flow — export filtering, LWW import, conflict resolution,
 * enterprise policy ([07-memory.md 11.0 / 11.3]).
 */
class MemorySyncTest {

    private fun clock(vararg entries: Pair<String, Long>): VectorClock =
        VectorClock(entries.toMap())

    private fun newStore(deviceId: String = "local") = MemoryStore(deviceId = deviceId)

    // ════════════════════════════════════════════════════════════════════
    // V1-V4: VectorClock semantics ([07-memory.md 11.1])
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `V1-tick increments the device counter`() {
        val zero = VectorClock.ZERO
        val one = zero.tick("devA")
        assertEquals(1L, one.clocks["devA"])
        assertEquals(2L, one.tick("devA").clocks["devA"])
        assertEquals(1L, one.tick("devB").clocks["devB"])
        assertEquals(1L, one.clocks["devA"]) // original clock untouched (immutable)
    }

    @Test
    fun `V2-isAfter is strict dominance`() {
        val v1 = clock("devA" to 2L, "devB" to 1L)
        val v2 = clock("devA" to 1L, "devB" to 1L)
        assertTrue(v1.isAfter(v2))
        assertFalse(v2.isAfter(v1))
        // Equal clocks dominate nothing.
        assertFalse(v2.isAfter(clock("devA" to 1L, "devB" to 1L)))
        // Every component of `other` must be covered (missing = 0).
        assertFalse(clock("devA" to 3L).isAfter(clock("devA" to 1L, "devB" to 1L)))
        assertTrue(clock("devA" to 3L, "devB" to 2L).isAfter(clock("devA" to 1L, "devB" to 1L)))
    }

    @Test
    fun `V3-concurrent when neither dominates`() {
        val a = clock("devA" to 2L)
        val b = clock("devB" to 1L)
        assertFalse(a.isAfter(b))
        assertFalse(b.isAfter(a))
        assertTrue(a.isConcurrentWith(b))
        // Equal clocks are the same version — not a conflict.
        assertFalse(a.isConcurrentWith(clock("devA" to 2L)))
    }

    @Test
    fun `V4-merge is component-wise max`() {
        val a = clock("devA" to 3L, "devB" to 1L)
        val b = clock("devA" to 2L, "devB" to 4L, "devC" to 2L)
        val m = a.merge(b)
        assertEquals(mapOf("devA" to 3L, "devB" to 4L, "devC" to 2L), m.clocks)
        // Merge is commutative and idempotent.
        assertEquals(m, b.merge(a))
        assertEquals(m, m.merge(b))
    }

    // ════════════════════════════════════════════════════════════════════
    // S1-S6: export filtering + LWW import ([07-memory.md 11.0 / 11.1])
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `S1-export only syncable entries leave the device`() = runBlocking {
        val store = newStore()
        store.putString("prefs.theme", "dark", syncable = true)
        store.putString("places.home.lat", "31.2") // default local_only

        val snapshot = MemorySync(store).exportSnapshot()
        assertEquals(listOf("prefs.theme"), snapshot.map { it.path })
    }

    @Test
    fun `S2-new remote path is applied directly`() = runBlocking {
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("prefs.theme", "dark", syncable = true)

        val report = MemorySync(deviceB).importSnapshot(MemorySync(deviceA).exportSnapshot())
        assertEquals(listOf("prefs.theme"), report.applied)
        assertEquals("dark", deviceB.get("prefs.theme")!!.jsonPrimitive.content)
    }

    @Test
    fun `S3-local dominates remote - remote silently discarded`() = runBlocking {
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("prefs.theme", "dark", syncable = true) // devA:1
        // B pulls A's snapshot (clock merges to {devA:1}), then B edits locally.
        MemorySync(deviceB).importSnapshot(MemorySync(deviceA).exportSnapshot())
        deviceB.putString("prefs.theme", "dark-v2", syncable = true) // {devA:1, devB:1}

        // A has not changed — re-importing its stale {devA:1} snapshot must
        // not clobber B's newer local write.
        val report = MemorySync(deviceB).importSnapshot(MemorySync(deviceA).exportSnapshot())
        assertEquals(emptyList<String>(), report.applied)
        assertEquals(listOf("prefs.theme"), report.keptLocal)
        assertEquals(emptyList<SyncConflict>(), report.conflicts)
        assertEquals("dark-v2", deviceB.get("prefs.theme")!!.jsonPrimitive.content)
    }

    @Test
    fun `S4-remote dominates local - remote silently overwrites`() = runBlocking {
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("prefs.theme", "dark", syncable = true) // devA:1
        // B pulls A's snapshot, then A edits again — A's newer write dominates.
        MemorySync(deviceB).importSnapshot(MemorySync(deviceA).exportSnapshot())
        deviceA.putString("prefs.theme", "dark-v2", syncable = true) // devA:2

        val report = MemorySync(deviceB).importSnapshot(MemorySync(deviceA).exportSnapshot())
        assertEquals(listOf("prefs.theme"), report.applied)
        assertEquals("dark-v2", deviceB.get("prefs.theme")!!.jsonPrimitive.content)
    }

    @Test
    fun `S5-concurrent writes surface to user`() = runBlocking {
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("places.home.address", "北京市朝阳区", syncable = true) // devA:1
        deviceB.putString("places.home.address", "上海市浦东新区", syncable = true) // devB:1

        val report = MemorySync(deviceB).importSnapshot(MemorySync(deviceA).exportSnapshot())
        assertEquals(emptyList<String>(), report.applied)
        assertEquals(emptyList<String>(), report.keptLocal)
        assertEquals(1, report.conflicts.size)
        val c = report.conflicts[0]
        assertEquals("places.home.address", c.path)
        assertEquals("上海市浦东新区", c.localValue.jsonPrimitive.content)
        assertEquals("北京市朝阳区", c.remoteValue.jsonPrimitive.content)
    }

    @Test
    fun `S6-identical clocks are skipped - idempotent re-import`() = runBlocking {
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("prefs.theme", "dark", syncable = true)
        val snapshotA = MemorySync(deviceA).exportSnapshot()
        val syncB = MemorySync(deviceB)

        syncB.importSnapshot(snapshotA)
        val report2 = syncB.importSnapshot(snapshotA) // same clock, same value
        assertEquals(listOf("prefs.theme"), report2.skipped)
        assertEquals(emptyList<String>(), report2.applied)
        assertEquals(emptyList<SyncConflict>(), report2.conflicts)
    }

    // ════════════════════════════════════════════════════════════════════
    // S7-S8, S15: enterprise policy ([07-memory.md 11.3])
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `S7-allowedSyncCategories filters by category`() = runBlocking {
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("prefs.theme", "dark", syncable = true, category = MemoryCategory.PREFERENCE)
        deviceA.putString("pay.card", "4111", syncable = true, category = MemoryCategory.PAYMENT)

        val report = MemorySync(deviceB).importSnapshot(
            MemorySync(deviceA).exportSnapshot(),
            policy = SyncPolicy(allowedCategories = setOf(MemoryCategory.PREFERENCE)),
        )
        assertEquals(listOf("prefs.theme"), report.applied)
        assertEquals(listOf("pay.card"), report.skipped)
        assertEquals("dark", deviceB.get("prefs.theme")!!.jsonPrimitive.content)
        assertNull(deviceB.get("pay.card"))
    }

    @Test
    fun `S8-disableCloudMemorySync aborts sync and logs to audit`() = runBlocking {
        val audit = InMemoryAuditLog()
        audit.start()
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("prefs.theme", "dark", syncable = true)

        val report = MemorySync(deviceB, audit).importSnapshot(
            MemorySync(deviceA).exportSnapshot(),
            policy = SyncPolicy(enabled = false),
        )
        assertEquals(listOf("prefs.theme"), report.skipped)
        assertNull(deviceB.get("prefs.theme"))

        audit.flush()
        assertTrue(audit.getRuns().any { it.ir?.contains("disableCloudMemorySync") == true })
        audit.stop()
    }

    @Test
    fun `S15-category policy violation logs to audit`() = runBlocking {
        val audit = InMemoryAuditLog()
        audit.start()
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("pay.card", "4111", syncable = true, category = MemoryCategory.PAYMENT)

        MemorySync(deviceB, audit).importSnapshot(
            MemorySync(deviceA).exportSnapshot(),
            policy = SyncPolicy(allowedCategories = setOf(MemoryCategory.PREFERENCE)),
        )
        audit.flush()
        assertTrue(audit.getRuns().any { it.ir?.contains("allowedSyncCategories") == true })
        audit.stop()
    }

    // ════════════════════════════════════════════════════════════════════
    // S9-S12: conflict resolution ([07-memory.md 11.1 UX])
    // ════════════════════════════════════════════════════════════════════

    private suspend fun concurrentSetup(): Triple<MemoryStore, MemoryStore, SyncEntry> {
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("places.home.address", "北京", syncable = true) // devA:1
        deviceB.putString("places.home.address", "上海", syncable = true) // devB:1
        return Triple(deviceA, deviceB, MemorySync(deviceA).exportSnapshot().first())
    }

    @Test
    fun `S9-resolve conflict keep remote`() = runBlocking {
        val (deviceA, deviceB, remote) = concurrentSetup()
        val syncB = MemorySync(deviceB)
        val report = syncB.importSnapshot(listOf(remote))
        assertEquals(1, report.conflicts.size)

        val status = syncB.resolveConflict("places.home.address", remote, ConflictResolution.KEEP_REMOTE)
        assertEquals(WriteStatus.UPDATED, status)
        assertEquals("北京", deviceB.get("places.home.address")!!.jsonPrimitive.content)
    }

    @Test
    fun `S10-resolve conflict keep local`() = runBlocking {
        val (deviceA, deviceB, remote) = concurrentSetup()
        val syncB = MemorySync(deviceB)
        syncB.importSnapshot(listOf(remote))

        val status = syncB.resolveConflict("places.home.address", remote, ConflictResolution.KEEP_LOCAL)
        assertNull(status)
        assertEquals("上海", deviceB.get("places.home.address")!!.jsonPrimitive.content)
    }

    @Test
    fun `S11-resolve conflict keep both preserves local in history`() = runBlocking {
        val (deviceA, deviceB, remote) = concurrentSetup()
        val syncB = MemorySync(deviceB)
        syncB.importSnapshot(listOf(remote))

        val status = syncB.resolveConflict("places.home.address", remote, ConflictResolution.KEEP_BOTH)
        assertEquals(WriteStatus.UPDATED, status)
        // Remote value becomes current…
        assertEquals("北京", deviceB.get("places.home.address")!!.jsonPrimitive.content)
        // …and the local value is soft-deleted into history — both retained.
        val history = deviceB.history("places.home.address")
        assertEquals(1, history.size)
        assertEquals("上海", history[0].second.value.jsonPrimitive.content)
    }

    @Test
    fun `S12-applied remote clock merges so local writes stay monotonic`() = runBlocking {
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("prefs.theme", "dark", syncable = true) // devA:1
        deviceA.putString("prefs.theme", "dark2", syncable = true) // devA:2

        val syncB = MemorySync(deviceB)
        syncB.importSnapshot(MemorySync(deviceA).exportSnapshot()) // B clock ← {devA:2}
        // A fresh local write now ticks the merged clock: {devA:2, devB:1}.
        deviceB.putString("prefs.theme", "dark3", syncable = true)

        val entry = deviceB.listEntries("prefs.theme").first().entry
        assertEquals(2L, entry.vectorClock.clocks["devA"])
        assertEquals(1L, entry.vectorClock.clocks["devB"])
    }

    // ════════════════════════════════════════════════════════════════════
    // S13-S14: snapshot payload ([07-memory.md 11.0])
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `S13-exported snapshot carries vector clock, tags and category`() = runBlocking {
        val store = newStore("devA")
        store.putString(
            "prefs.theme", "dark", syncable = true,
            tags = setOf("theme"), category = MemoryCategory.PREFERENCE,
        )

        val entry = MemorySync(store).exportSnapshot().first()
        assertEquals(1L, entry.vectorClock.clocks["devA"])
        assertEquals(setOf("theme"), entry.tags)
        assertEquals(MemoryCategory.PREFERENCE, entry.category)
        assertEquals("prefs.theme", entry.path)
    }

    @Test
    fun `S14-ttl and createdAt propagate through sync`() = runBlocking {
        val deviceA = newStore("devA")
        val deviceB = newStore("devB")
        deviceA.putString("temp.otp", "123456", ttlMs = 30_000, syncable = true)

        val remote = MemorySync(deviceA).exportSnapshot().first()
        MemorySync(deviceB).importSnapshot(listOf(remote))

        val entry = deviceB.listEntries("temp.otp").first().entry
        assertEquals(30_000L, entry.ttlMs)
        assertEquals(remote.createdAt, entry.createdAt)
        assertTrue(remote.value.jsonPrimitive.isString)
    }
}
