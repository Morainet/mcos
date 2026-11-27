package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.ConfirmationDecision
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.TypedEventBus
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.security.HmacAuthStampSigner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationPublicationTest {
    @Test fun immediateResponsesAreAcceptedBeforePublishReturns() = runBlocking {
        for (decision in listOf(ConfirmationDecision.Approve(), ConfirmationDecision.Reject)) {
            lateinit var coordinator: ConfirmationCoordinator
            val bus = object : EventBus by TypedEventBus() {
                override fun publish(runId: String, event: RuntimeEvent) {
                    if (event is RuntimeEvent.ConfirmationNeeded) {
                        runBlocking {
                            assertTrue(coordinator.respond(runId, event.commandId, decision))
                        }
                    }
                }
            }
            coordinator = ConfirmationCoordinator(bus, HmacAuthStampSigner(), CommandRegistry(), 100)
            val result = coordinator.requestConfirmation(
                "run", 0, Command("record.export", JsonObject(emptyMap())),
                CommandResult.Err("CONFIRMATION_REQUIRED", "Confirm export"),
            )
            assertEquals(decision, result)
            assertFalse(coordinator.respond("run", "record.export", decision))
        }
    }
}
