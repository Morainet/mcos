package com.morainet.mcos.android.demo.shell

import com.morainet.mcos.llm.AgentSessionStore
import com.morainet.mcos.sdk.SecureStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray

/**
 * [AgentSessionStore.Persistence] backed by the host [SecureStore] (06 §11.4),
 * mirroring the demo's other persistence classes (`SkillStore`,
 * `McpServerController`): one JSON list under a single key, this instance the
 * writer. A suspended agent turn survives process death here so a scheduled
 * wake-up can resume it — no plaintext goal ever leaves the encrypted store.
 *
 * The [AgentSessionStore] persistence seam is synchronous; the SecureStore API
 * is `suspend`. The store calls these inside its monitor and the payload is a
 * handful of short strings, so we bridge with [runBlocking] — the same pragmatic
 * choice the demo makes elsewhere for tiny secure-store reads on non-UI paths.
 */
class SecureStoreAgentSuspension(
    private val secureStore: SecureStore,
) : AgentSessionStore.Persistence {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun save(snapshot: AgentSessionStore.Snapshot) {
        val all = loadMap()
        all[snapshot.sessionId] = snapshot
        write(all)
    }

    override fun delete(sessionId: String) {
        val all = loadMap()
        if (all.remove(sessionId) != null) write(all)
    }

    override fun loadAll(): List<AgentSessionStore.Snapshot> = loadMap().values.toList()

    private fun loadMap(): MutableMap<String, AgentSessionStore.Snapshot> {
        val bytes = runBlocking { secureStore.get(KEY) } ?: return mutableMapOf()
        return runCatching {
            json.parseToJsonElement(bytes.decodeToString()).jsonArray.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["session_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val goal = o["goal"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val obs = o["observations"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?: emptyList()
                AgentSessionStore.Snapshot(id, goal, obs)
            }.associateByTo(mutableMapOf()) { it.sessionId }
        }.getOrElse { mutableMapOf() } // corrupt store fails closed to "nothing suspended"
    }

    private fun write(all: Map<String, AgentSessionStore.Snapshot>) {
        val array = buildJsonArray {
            all.values.forEach { snap ->
                add(
                    buildJsonObject {
                        put("session_id", snap.sessionId)
                        put("goal", snap.goal)
                        put("observations", JsonArray(snap.observationLog.map { JsonPrimitive(it) }))
                    },
                )
            }
        }
        runBlocking { secureStore.put(KEY, array.toString().encodeToByteArray()) }
    }

    companion object {
        /** SecureStore key for the suspended-session snapshot list. */
        const val KEY = "agent_suspensions"
    }
}
