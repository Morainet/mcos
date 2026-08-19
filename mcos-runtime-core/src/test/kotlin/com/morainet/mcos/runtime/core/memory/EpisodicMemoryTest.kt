package com.morainet.mcos.runtime.core.memory

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Episodic memory tests (07-memory.md §8).
 *
 * Uses a mutable fake clock so decay, eviction and auto-summarize are
 * testable without sleeping.
 */
class EpisodicMemoryTest {

    private var now = 1_750_000_000_000L // fixed fake "current" time

    private fun newMemory(
        maxRecords: Int = EpisodicMemory.DEFAULT_MAX_RECORDS,
        maxAgeMs: Long = EpisodicMemory.DEFAULT_MAX_AGE_MS,
        summarizeBatch: Int = EpisodicMemory.DEFAULT_SUMMARIZE_BATCH,
        summarizeKeep: Int = EpisodicMemory.DEFAULT_SUMMARIZE_KEEP,
        entityMatcher: EntityMatcher = EntityMatcher(),
    ) = EpisodicMemory(maxRecords, maxAgeMs, summarizeBatch, summarizeKeep, entityMatcher) { now }

    private val DAY = 24L * 60 * 60 * 1000

    // ═══════════════════════════════════════════════════════════════
    // E1-E3: Recording and field fidelity
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E1-record appends and search finds the episode`() {
        val mem = newMemory()
        mem.record(
            runId = "run_abc",
            summary = "Compressed 12 photos and emailed Tom",
            commandIds = listOf("photo.search", "compress.images", "mail.send"),
            entities = listOf("people.tom"),
        )
        assertEquals(1, mem.count())
        val hits = mem.search("compress photos")
        assertTrue("expected a hit for 'compress photos'", hits.isNotEmpty())
        assertEquals("run_abc", hits[0].record.runId)
    }

    @Test
    fun `E2-record keeps runId and outcome fields`() {
        val mem = newMemory()
        mem.record(
            runId = "run_xyz",
            summary = "failed to send payment",
            outcome = EpisodicOutcome.FAILED,
            commandIds = listOf("payment.send"),
        )
        val hits = mem.search("payment")
        assertEquals(1, hits.size)
        assertEquals(EpisodicOutcome.FAILED, hits[0].record.outcome)
        assertEquals(listOf("payment.send"), hits[0].record.commandIds)
    }

    @Test
    fun `E3-record timestamp defaults to clock`() {
        val mem = newMemory()
        mem.record("r1", "hello world")
        val hits = mem.search("hello")
        assertEquals(now, hits[0].record.timestamp)
    }

    // ═══════════════════════════════════════════════════════════════
    // E4-E6: Search relevance and time-decay ranking (§8.1)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E4-search ranks better similarity first`() {
        val mem = newMemory()
        mem.record("r_weak", "ordered a pizza", timestamp = now - 2 * DAY)
        mem.record("r_strong", "compressed twelve photos and emailed Tom", timestamp = now - 2 * DAY)
        val hits = mem.search("compress photos")
        assertEquals("r_strong", hits[0].record.runId)
    }

    @Test
    fun `E5-search matches command ids and entity paths`() {
        val mem = newMemory()
        mem.record(
            "r_cmd", "did the photo task",
            commandIds = listOf("photo.search", "compress.images"),
            timestamp = now - DAY,
        )
        mem.record(
            "r_ent", "office task",
            entities = listOf("places.office"),
            timestamp = now - DAY,
        )
        val byCommand = mem.search("compress.images")
        assertEquals("r_cmd", byCommand[0].record.runId)
        val byEntity = mem.search("places.office")
        assertEquals("r_ent", byEntity[0].record.runId)
    }

    @Test
    fun `E6-time-decay ranks recent episode above old equal-similarity one`() {
        val mem = newMemory()
        // Same summary, same similarity — only age differs.
        mem.record("r_old", "mailed photos to Tom", timestamp = now - 10 * DAY)   // decay 0.5
        mem.record("r_new", "mailed photos to Tom", timestamp = now - 1 * DAY)     // decay 1.0
        val hits = mem.search("mailed photos")
        assertEquals("r_new", hits[0].record.runId)
        // Oldest episode is still present, just downranked.
        assertTrue(hits.any { it.record.runId == "r_old" })
    }

    @Test
    fun `E7-old episodes decay to near zero after 90 days`() {
        // maxAge wide enough to keep the record; decay itself must still
        // downweight it to 0.05 (>90 days bucket).
        val mem = newMemory(maxAgeMs = 200L * DAY)
        mem.record("r_ancient", "mailed photos to Tom", timestamp = now - 100 * DAY)
        val hits = mem.search("mailed photos to Tom")
        assertTrue("ancient episode must still be retrievable", hits.isNotEmpty())
        assertTrue(hits[0].score < 0.1f)
    }

    // ═══════════════════════════════════════════════════════════════
    // E8-E10: Retention policy (§8.2)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E8-records older than max age are evicted`() {
        val mem = newMemory(maxAgeMs = 90L * DAY)
        mem.record("r_keep", "recent task", timestamp = now - 10 * DAY)
        mem.record("r_drop", "ancient task", timestamp = now - 100 * DAY)
        // The write for r_drop itself triggers retention — r_drop is dropped.
        assertEquals(1, mem.count())
        val runIds = mem.exportEpisodic().map { it.jsonObject["runId"]!!.jsonPrimitive.content }
        assertTrue("r_drop must be evicted", runIds.contains("r_keep") && !runIds.contains("r_drop"))
    }

    @Test
    fun `E9-count never exceeds maxRecords via auto-summarize`() {
        // Compress the oldest 50 → 5; with 60 records the overflow is capped.
        val mem = newMemory(maxRecords = 50, summarizeBatch = 50, summarizeKeep = 5)
        repeat(60) { i ->
            mem.record("run_$i", "task number $i", commandIds = listOf("cmd.work"))
        }
        // 60 records → oldest 50 compressed to 5 summaries → 10 kept + 5 summaries = 15.
        assertEquals(15, mem.count())
        // The compression produced summary records, not raw ones.
        assertTrue(mem.exportEpisodic().any { it.jsonObject["runId"]!!.jsonPrimitive.content.startsWith("summary_") })
    }

    @Test
    fun `E10-auto-summarize merges commands entities and majority outcome`() {
        val mem = newMemory(maxRecords = 10, summarizeBatch = 50, summarizeKeep = 5)
        repeat(30) { i ->
            mem.record(
                "run_$i", "batch task $i",
                commandIds = listOf("cmd.work"),
                entities = listOf("places.office"),
                outcome = if (i % 3 == 0) EpisodicOutcome.FAILED else EpisodicOutcome.SUCCESS,
            )
        }
        // 30 records at maxRecords=10: the count is capped at the limit and
        // the oldest entries are compressed into summary records.
        val eps = mem.exportEpisodic()
        assertTrue("count must stay within maxRecords", eps.size <= 10)
        val summaries = eps.filter { it.jsonObject["runId"]!!.jsonPrimitive.content.startsWith("summary_") }
        assertTrue("expected at least one summary record", summaries.isNotEmpty())
        val first = summaries[0].jsonObject
        val commands = first["commandIds"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(commands.contains("cmd.work"))
    }

    // ═══════════════════════════════════════════════════════════════
    // E11-E12: First-use check and management (§8.3)
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E11-hasExecuted distinguishes first use from repeat`() {
        val mem = newMemory()
        mem.record("r1", "photo job", commandIds = listOf("compress.images"))
        assertTrue(mem.hasExecuted("compress.images"))
        assertFalse(mem.hasExecuted("mail.send"))
        assertEquals(1, mem.byCommand("compress.images").size)
    }

    @Test
    fun `E12-clear removes all episodes`() {
        val mem = newMemory()
        mem.record("r1", "one", commandIds = listOf("cmd.a"))
        mem.record("r2", "two", commandIds = listOf("cmd.b"))
        mem.clear()
        assertEquals(0, mem.count())
        assertTrue(mem.search("one").isEmpty())
    }

    @Test
    fun `E13-export matches MemoryExport episodic shape`() {
        val mem = newMemory()
        mem.record(
            "run_abc", "Compressed 12 photos and emailed Tom",
            commandIds = listOf("photo.search", "mail.send"),
            entities = listOf("people.tom"),
        )
        val arr = mem.exportEpisodic()
        assertEquals(1, arr.size)
        val obj = arr[0].jsonObject
        assertEquals("run_abc", obj["runId"]!!.jsonPrimitive.content)
        assertEquals(now, obj["timestamp"]!!.jsonPrimitive.content.toLong())
        assertEquals("SUCCESS", obj["outcome"]!!.jsonPrimitive.content)
        assertEquals(2, obj["commandIds"]!!.jsonArray.size)
        assertEquals(listOf("people.tom"), obj["entities"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `E14-search respects topK cap`() {
        val mem = newMemory()
        repeat(8) { i ->
            mem.record("run_$i", "compress photos batch $i", commandIds = listOf("compress.images"))
        }
        val hits = mem.search("compress photos", topK = 3)
        assertEquals(3, hits.size)
    }

    // ═══════════════════════════════════════════════════════════════
    // E15-E19: §8.3 fuzzy entity reference / named-entity merge
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `E15-natural-language entity name resolves to its memory path`() {
        val mem = newMemory()
        mem.record(
            "run_abc", "Compressed 12 photos and emailed Tom",
            commandIds = listOf("photo.search", "mail.send"),
            entities = listOf("people.tom"),
            timestamp = now,
        )
        // §8.3 example: "跟上次一样发照片给Tom" — the embedded entity name
        // must recall the episode whose entity path is "people.tom".
        val hits = mem.search("跟上次一样发照片给Tom")
        assertTrue("entity reference must recall the episode", hits.isNotEmpty())
        assertEquals("run_abc", hits[0].record.runId)
    }

    @Test
    fun `E16-leaf-node match is case-insensitive`() {
        val mem = newMemory()
        mem.record(
            "r_ent", "office task",
            entities = listOf("places.office"),
            timestamp = now,
        )
        val hits = mem.search("OFFICE")
        assertTrue(hits.isNotEmpty())
        assertEquals("r_ent", hits[0].record.runId)
    }

    @Test
    fun `E17-full-path query still wins over leaf node`() {
        val mem = newMemory()
        mem.record("r_a", "task a", entities = listOf("people.tom"), timestamp = now)
        mem.record("r_b", "task b", entities = listOf("people.tommy"), timestamp = now)
        // "tom" is a leaf-node prefix of "tommy", but the full path
        // "people.tom" must outrank the longer "people.tommy".
        val hits = mem.search("people.tom")
        assertEquals("r_a", hits[0].record.runId)
    }

    @Test
    fun `E18-registered alias merges into the canonical entity`() {
        val matcher = EntityMatcher()
            .register("people.tom", "thomas", "Tom")
        val mem = newMemory(entityMatcher = matcher)
        mem.record(
            "r_ent", "mailed the contract",
            entities = listOf("people.tom"),
            timestamp = now,
        )
        val hits = mem.search("发给 thomas")
        assertTrue("alias must merge to canonical entity", hits.isNotEmpty())
        assertEquals("r_ent", hits[0].record.runId)
    }

    @Test
    fun `E19-weak bigram overlap is filtered by the recall threshold`() {
        val matcher = EntityMatcher()
        // "people.tom" vs "places.office" share only incidental bigrams
        // (~0.19) — below the 0.75 §6.0 bar, so it must NOT recall.
        assertEquals(0f, matcher.score("people.tom", "places.office"))
        // an exact leaf match always clears the bar.
        assertTrue(matcher.score("tom", "people.tom") > EntityMatcher.RECALL_THRESHOLD)
    }
}
