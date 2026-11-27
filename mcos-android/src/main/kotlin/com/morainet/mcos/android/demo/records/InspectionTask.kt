package com.morainet.mcos.android.demo.records

import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeGateway
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.sdk.CommandResult
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object InspectionTask {
    const val TITLE = "Export the latest completed inspection"

    suspend fun latestCompleted(runtime: RuntimeGateway): String {
        val probe = runtime.executeProbe(listOf(Command("record.latest_completed", JsonObject(emptyMap())))).single()
        return when (probe) {
            is CommandResult.Ok -> (probe.value as JsonPrimitive).content
            is CommandResult.Err -> error(probe.message)
        }
    }

    fun request(recordId: String): ExecuteRequest =
        ExecuteRequest(Source.CHAT, Payload.DslText("record.export(id=${JsonPrimitive(recordId)})"))

    suspend fun verify(recordId: String, uri: String): String = withContext(Dispatchers.IO) {
        val expected = checkNotNull(RecordExportPlugin.expectedContent(recordId))
        val file = File(URI(uri))
        check(file.isFile && file.length() == expected.encodeToByteArray().size.toLong() && file.readText() == expected) {
            "The exported file did not match the selected inspection."
        }
        "${file.name}\n${expected.trimEnd()}"
    }
}
