package com.morainet.mcos.runtime.core.workflow

import com.morainet.mcos.security.SnapshotFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * The minimum needed to re-arm a schedule trigger after a restart
 * (05-workflow.md §9.3). The cron/tz/misfire policy are *not* stored here —
 * they live on the [WorkflowSpec] and are re-read on re-arm, so this only
 * pins which workflows were armed and whether the user pre-authorized them
 * (08 §4.1). Re-arming therefore depends on the workflow still being
 * registered (e.g. rehydrated by the marketplace) at rehydrate time.
 */
data class PersistedSchedule(val workflowId: String, val preAuthorized: Boolean)

/**
 * Durable home for the armed-schedule set so scheduled workflows survive
 * process death and reboots ([10-roadmap.md §6] durable schedule hosting).
 * The in-process [ScheduleTriggerManager] is lifetime-only; this store lets a
 * fresh runtime re-arm what the user had running.
 */
interface ArmedScheduleStore {
    fun load(): List<PersistedSchedule>
    fun save(records: List<PersistedSchedule>)
}

/**
 * The default: no durability, so schedules are process-lifetime only — the
 * pre-durability behaviour. Named so the opt-out is greppable, matching the
 * project's secure-/honest-by-default convention (`NullAuditLog`, …).
 */
object NullArmedScheduleStore : ArmedScheduleStore {
    override fun load(): List<PersistedSchedule> = emptyList()
    override fun save(records: List<PersistedSchedule>) = Unit
}

/**
 * A [SnapshotFile]-backed store (single-line JSON payload + optional HMAC),
 * mirroring `FileGrantStore` / `InstallRecordStore`. A missing, unreadable or
 * tamper-flagged file loads as empty (fail-closed: nothing re-arms rather than
 * arming a forged workflow id).
 */
class FileArmedScheduleStore(
    private val file: File,
    private val hmacKey: ByteArray?,
) : ArmedScheduleStore {

    private val json = Json { ignoreUnknownKeys = true }

    override fun load(): List<PersistedSchedule> {
        val payload = SnapshotFile.read(file, hmacKey) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(payload).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["workflowId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                PersistedSchedule(id, o["preAuthorized"]?.jsonPrimitive?.booleanOrNull ?: false)
            }
        }.getOrDefault(emptyList())
    }

    override fun save(records: List<PersistedSchedule>) {
        val arr = buildJsonArray {
            records.forEach { r ->
                add(
                    buildJsonObject {
                        put("workflowId", r.workflowId)
                        put("preAuthorized", r.preAuthorized)
                    }
                )
            }
        }
        SnapshotFile.write(file, arr.toString(), hmacKey)
    }
}
