package com.morainet.mcos.android.demo.records

import com.morainet.mcos.sdk.Artifact
import com.morainet.mcos.sdk.CommandHandler
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.ExecutionContext
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** App-owned business capability: exports an existing record to private storage. */
class RecordExportPlugin(private val directory: File) : McosPlugin {
    private data class Record(val id: String, val title: String, val order: Int, val completed: Boolean) {
        fun asText(): String = "Record $id\nTitle: $title\nStatus: completed\n"
    }

    internal companion object {
        private val records = listOf(
            Record("demo-001", "Site inspection", 1, completed = true),
            Record("demo-002", "Equipment inspection", 2, completed = true),
            Record("demo-003", "Safety inspection", 3, completed = false),
        )

        private fun completedRecord(id: String?) = records.singleOrNull { it.id == id && it.completed }

        fun expectedContent(id: String): String? = completedRecord(id)?.asText()
    }

    override val manifest = PluginManifest(
        id = "sample.records", name = "Record export", version = "1.0.0", minRuntimeVersion = "0.0.6",
        description = "Exports the host application's sample record",
        provider = ProviderInfo("MCOS sample", "https://github.com/Morainet/mcos"),
        entry = "com.morainet.mcos.android.demo.records.RecordExportPlugin",
        commands = listOf(CommandManifestEntry(
            id = "record.latest_completed", version = "1.0.0", title = "Find latest completed record",
            description = "Read the ID of the most recent completed inspection",
            sideEffectClass = SideEffectClass.read,
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(emptyMap()))
                put("additionalProperties", false)
            },
        ), CommandManifestEntry(
            id = "record.export", version = "1.0.0", title = "Export record",
            description = "Write a completed inspection record as a text file in app-private storage",
            sideEffectClass = SideEffectClass.write,
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("id", buildJsonObject { put("type", "string") })
                })
                put("required", buildJsonArray { add(JsonPrimitive("id")) })
                put("additionalProperties", false)
            },
        )),
    )
    override suspend fun onLoad(services: HostServices) {}
    override suspend fun onUnload() {}
    override fun handlers() = mapOf(
        "record.latest_completed" to object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                val latest = records.filter { it.completed }.maxByOrNull { it.order }
                    ?: return CommandResult.Err("UNAVAILABLE", "No completed record found")
                return CommandResult.Ok(JsonPrimitive(latest.id))
            }
        },
        "record.export" to object : CommandHandler {
            override suspend fun invoke(ctx: ExecutionContext): CommandResult = withContext(Dispatchers.IO) {
                val id = ctx.args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                val record = completedRecord(id)
                    ?: return@withContext CommandResult.Err("UNAVAILABLE", "Completed record not found: $id")
                if (!directory.isDirectory && !directory.mkdirs()) {
                    return@withContext CommandResult.Err("UNAVAILABLE", "Cannot create export directory")
                }
                var file: File? = null
                try {
                    // Fresh file per run: retries do not overwrite an earlier export.
                    file = File.createTempFile("record-", ".txt", directory)
                    file.writeText(record.asText())
                    CommandResult.Ok(
                        JsonPrimitive("Record exported"),
                        listOf(Artifact("file", file.toURI().toString(), "text/plain")),
                    )
                } catch (failure: IOException) {
                    file?.delete()
                    CommandResult.Err("UNAVAILABLE", "Export failed: ${failure.message}")
                }
            }
        },
    )
}
