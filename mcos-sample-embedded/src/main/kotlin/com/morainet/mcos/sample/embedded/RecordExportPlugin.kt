package com.morainet.mcos.sample.embedded

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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** App-owned business capability: exports an existing record to private storage. */
class RecordExportPlugin(private val directory: File) : McosPlugin {
    override val manifest = PluginManifest(
        id = "sample.records", name = "Record export", version = "1.0.0", minRuntimeVersion = "0.0.6",
        description = "Exports the host application's sample record",
        provider = ProviderInfo("MCOS sample", "https://github.com/Morainet/mcos"),
        entry = "com.morainet.mcos.sample.embedded.RecordExportPlugin",
        commands = listOf(CommandManifestEntry(
            id = "record.export", version = "1.0.0", title = "Export record",
            description = "Write record demo-001 as a text file in app-private storage",
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
    override fun handlers() = mapOf("record.export" to object : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult = withContext(Dispatchers.IO) {
            val id = ctx.args.jsonObject["id"]?.jsonPrimitive?.contentOrNull
            if (id != "demo-001") {
                return@withContext CommandResult.Err("UNAVAILABLE", "Record not found: $id")
            }
            if (!directory.isDirectory && !directory.mkdirs()) {
                return@withContext CommandResult.Err("UNAVAILABLE", "Cannot create export directory")
            }
            var file: File? = null
            try {
                // Fresh file per run: retries do not overwrite an earlier export.
                file = File.createTempFile("record-", ".txt", directory)
                file.writeText(RECORD_TEXT)
                CommandResult.Ok(
                    JsonPrimitive("Record exported"),
                    listOf(Artifact("file", file.toURI().toString(), "text/plain")),
                )
            } catch (failure: IOException) {
                file?.delete()
                CommandResult.Err("UNAVAILABLE", "Export failed: ${failure.message}")
            }
        }
    })
    companion object {
        const val RECORD_TEXT = "Record demo-001\nTitle: Site inspection\nStatus: completed\n"
    }
}
