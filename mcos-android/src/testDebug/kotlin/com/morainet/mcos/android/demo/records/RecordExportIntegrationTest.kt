package com.morainet.mcos.android.demo.records

import com.morainet.mcos.android.EmbeddedRuntime
import com.morainet.mcos.runtime.core.api.ConfirmationDecision
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.security.audit.InMemoryAuditLog
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the same assembly and app plugin as the Android sample, without Android APIs. */
class RecordExportIntegrationTest {
    @Test fun approvedExportCreatesRealArtifactAndAudit() = verify("demo-001", true, true)
    @Test fun rejectionCreatesNoFile() = verify("demo-001", false, false)
    @Test fun missingRecordFailsWithoutAnArtifact() = verify("missing", true, false)
    @Test fun missingArgumentFailsBeforeConfirmation() = verify(null, true, false)

    @Test fun cancellationWhileAwaitingApprovalCreatesNoFile() = verify("demo-001", true, false, cancel = true)
    @Test fun probesLatestCompletedThenExportsIt() = verify("demo-002", true, true, lookUpLatest = true)

    private fun verify(
        id: String?,
        approve: Boolean,
        expectedSuccess: Boolean,
        cancel: Boolean = false,
        lookUpLatest: Boolean = false,
    ) = runBlocking {
        val directory = Files.createTempDirectory("mcos-export-").toFile()
        val audit = InMemoryAuditLog().apply { start() }
        val host = EmbeddedRuntime.create(StubHostServices(MemoryStore()), listOf(RecordExportPlugin(directory)), audit)
        try {
            assertEquals(null, host.runtime.pluginInstaller())
            val selected = if (lookUpLatest) {
                val probe = host.runtime.executeProbe(
                    listOf(Command("record.latest_completed", JsonObject(emptyMap()))),
                ).single() as CommandResult.Ok
                (probe.value as JsonPrimitive).content.also { assertEquals(id, it) }
            } else {
                id
            }
            val dsl = if (selected == null) "record.export()" else "record.export(id=\"$selected\")"
            val request = ExecuteRequest(Source.API, Payload.DslText(dsl))
            assertEquals(1, host.runtime.preview(request).commandCount)
            assertTrue(directory.listFiles().orEmpty().isEmpty())
            val events = mutableListOf<RuntimeEvent>()
            val handle = host.runtime.execute(request)
            try {
                withTimeout(5_000) {
                    host.runtime.observe(handle.runId).collect { event ->
                        events += event
                        if (event is RuntimeEvent.ConfirmationNeeded) {
                            assertTrue(directory.listFiles().orEmpty().isEmpty())
                            if (cancel) host.runtime.cancel(event.runId) else host.runtime.respondConfirmation(
                                event.runId, event.commandId,
                                if (approve) ConfirmationDecision.Approve() else ConfirmationDecision.Reject,
                            )
                        }
                    }
                }
            } catch (failure: Exception) {
                throw AssertionError("Run did not terminate: $events; files=${directory.listFiles()?.toList()}", failure)
            }
            assertEquals(events.toString(), expectedSuccess, events.any { it is RuntimeEvent.RunSucceeded })
            assertEquals(!expectedSuccess && !cancel, events.any { it is RuntimeEvent.RunFailed })
            assertEquals(cancel, events.any { it is RuntimeEvent.RunCancelled })
            if (id == null) assertFalse(events.any { it is RuntimeEvent.ConfirmationNeeded })
            val artifacts = events.filterIsInstance<RuntimeEvent.ArtifactEmitted>()
            if (expectedSuccess) {
                assertEquals(1, artifacts.size)
                val title = if (lookUpLatest) "Equipment inspection" else "Site inspection"
                assertEquals(
                    "Record $id\nTitle: $title\nStatus: completed\n",
                    directory.listFiles()!!.single().readText(),
                )
            } else {
                assertTrue(artifacts.isEmpty())
                assertTrue(directory.listFiles().orEmpty().isEmpty())
            }
            audit.flush()
            if (approve && id != null && !cancel) {
                assertTrue("Executed handler must be auditable: $events", audit.count() > 0)
            }
        } finally {
            host.runtime.shutdown()
            audit.stop()
            directory.deleteRecursively()
        }
    }
}
