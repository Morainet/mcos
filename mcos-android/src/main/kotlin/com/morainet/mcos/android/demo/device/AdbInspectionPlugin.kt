package com.morainet.mcos.android.demo.device

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
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Debug tool backed by an ADB-authorized host reading this app's private mailbox. */
internal class AdbInspectionPlugin(private val mailbox: File) : McosPlugin {
    override val manifest = PluginManifest(
        id = "mcos.adb", name = "MCOS device inspection", version = "1.0.0", minRuntimeVersion = "0.0.6",
        description = "Inspect the MCOS screen through a connected development host",
        provider = ProviderInfo("MCOS", "https://github.com/Morainet/mcos"),
        entry = "com.morainet.mcos.android.demo.device.AdbInspectionPlugin",
        commands = listOf(CommandManifestEntry(
            id = COMMAND, version = "1.0.0", title = "Inspect MCOS screen",
            description = "Report the visible MCOS screen's state using the connected ADB host",
            sideEffectClass = SideEffectClass.read,
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(emptyMap()))
                put("additionalProperties", false)
            },
        )),
    )

    override suspend fun onLoad(services: HostServices) {}
    override suspend fun onUnload() {}

    override fun handlers() = mapOf(COMMAND to object : CommandHandler {
        override suspend fun invoke(ctx: ExecutionContext): CommandResult = inspectScreen()
    })

    suspend fun connected(): Boolean = withContext(Dispatchers.IO) {
        System.currentTimeMillis() - File(mailbox, "connected").lastModified() in 0..15_000L
    }

    private suspend fun inspectScreen(): CommandResult = withContext(Dispatchers.IO) {
        if (!connected()) {
            return@withContext CommandResult.Err("UNAVAILABLE", "No ADB host is connected to MCOS")
        }
        val id = UUID.randomUUID().toString()
        val request = File(mailbox, "$id.request")
        val response = File(mailbox, "$id.response")
        try {
            check(request.createNewFile()) { "The ADB request ID already exists" }
            val state = withTimeoutOrNull(12_000L) {
                while (!response.isFile) delay(200)
                if (response.length() <= 32) response.readText() else null
            }
            if (state in SCREEN_STATES) CommandResult.Ok(JsonPrimitive(state))
            else CommandResult.Err("UNAVAILABLE", "ADB did not report an available MCOS screen state")
        } catch (failure: IOException) {
            CommandResult.Err("UNAVAILABLE", "The ADB connection could not read the screen state")
        } finally {
            request.delete()
            response.delete()
        }
    }

    companion object {
        const val COMMAND = "adb.inspect_mcos"
        private val SCREEN_STATES = setOf("agent_workspace", "record_workspace", "approval_required", "outside_mcos")
    }
}
