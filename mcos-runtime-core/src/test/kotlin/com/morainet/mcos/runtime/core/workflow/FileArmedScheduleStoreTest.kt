package com.morainet.mcos.runtime.core.workflow

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FileArmedScheduleStore — SnapshotFile-backed persistence for durable schedule
 * hosting (10 §6). Round-trip, tamper-evidence, and fail-closed loading.
 */
class FileArmedScheduleStoreTest {

    private val file = File.createTempFile("armed-schedules", ".json").also { it.delete() }
    private val key = "durable-schedule-seed".toByteArray()

    @AfterTest
    fun cleanup() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    @Test fun `AS1 signed round-trip preserves workflow id and preAuthorized`() {
        FileArmedScheduleStore(file, key).save(
            listOf(PersistedSchedule("nightly", preAuthorized = true), PersistedSchedule("hourly", preAuthorized = false)),
        )
        val loaded = FileArmedScheduleStore(file, key).load()
        assertEquals(2, loaded.size)
        assertEquals(PersistedSchedule("nightly", true), loaded.single { it.workflowId == "nightly" })
        assertEquals(PersistedSchedule("hourly", false), loaded.single { it.workflowId == "hourly" })
    }

    @Test fun `AS2 a missing file loads empty (fail-closed)`() {
        val absent = File(file.parentFile, "does-not-exist-${System.nanoTime()}.json")
        assertTrue(FileArmedScheduleStore(absent, key).load().isEmpty())
    }

    @Test fun `AS3 a tampered payload fails the HMAC and loads empty`() {
        FileArmedScheduleStore(file, key).save(listOf(PersistedSchedule("nightly", true)))
        // Forge the payload line, keep the old signature line.
        val lines = file.readLines()
        file.writeText("""[{"workflowId":"evil","preAuthorized":true}]""" + "\n" + lines[1] + "\n")
        assertTrue(
            FileArmedScheduleStore(file, key).load().isEmpty(),
            "a forged workflow id must not re-arm",
        )
    }

    @Test fun `AS4 unsigned store (null key) round-trips`() {
        FileArmedScheduleStore(file, null).save(listOf(PersistedSchedule("nightly", false)))
        assertEquals(listOf(PersistedSchedule("nightly", false)), FileArmedScheduleStore(file, null).load())
    }

    @Test fun `AS5 saving an empty list clears the set`() {
        val store = FileArmedScheduleStore(file, key)
        store.save(listOf(PersistedSchedule("nightly", true)))
        store.save(emptyList())
        assertTrue(FileArmedScheduleStore(file, key).load().isEmpty())
    }
}
