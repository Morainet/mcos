package com.morainet.mcos.android

import com.morainet.mcos.plugin.hello.HelloPlugin
import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.events.TypedEventBus
import com.morainet.mcos.runtime.core.plugin.PluginLoader
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.workflow.Trigger
import com.morainet.mcos.runtime.core.workflow.WorkflowCondition
import com.morainet.mcos.runtime.core.workflow.WorkflowSpec
import com.morainet.mcos.runtime.core.workflow.WorkflowStep
import com.morainet.mcos.security.PluginTrustGate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TriggerMaintenance (item 40): the uninstall sweep. An armed trigger whose
 * workflow step tree references a command that no longer resolves (or whose
 * spec vanished from the store) would fire and fail on every matching event —
 * it must be disarmed with the package instead. Extracted from the demo
 * ViewModel; these tests pin the SDK contract.
 */
class TriggerMaintenanceTest {

    private val registry = CommandRegistry()
    private val runtime: McosRuntime = McosRuntime.Builder()
        .withRegistry(registry)
        .withPluginLoader(PluginLoader(trustGate = PluginTrustGate(), registry = registry))
        .withEventBus(TypedEventBus())
        .build()

    private fun eventTrigger() = Trigger.Event(
        filter = buildJsonObject {
            put("type", "test.tick")
        }
    )

    @Test
    fun disarmsArmedTriggerWhoseCommandNoLongerResolves() = runTest {
        registry.register(HelloPlugin())
        runtime.workflowStore().registerSpec(
            "w-missing",
            WorkflowSpec(trigger = eventTrigger(), step = WorkflowStep.Command("example.gone")),
        )
        runtime.armTrigger("w-missing")
        assertTrue(runtime.armedTriggers().contains("w-missing"))

        val disarmed = TriggerMaintenance.disarmTriggersMissingCommands(registry, runtime)

        assertEquals(listOf("w-missing"), disarmed)
        assertTrue(runtime.armedTriggers().isEmpty())
    }

    @Test
    fun keepsArmedTriggerWhoseCommandsStillResolve() = runTest {
        registry.register(HelloPlugin())
        runtime.workflowStore().registerSpec(
            "w-ok",
            WorkflowSpec(
                trigger = eventTrigger(),
                step = WorkflowStep.Sequential(
                    listOf(
                        WorkflowStep.Command("hello.world"),
                        WorkflowStep.Retry(step = WorkflowStep.Command("hello.world")),
                    )
                ),
            ),
        )
        runtime.armTrigger("w-ok")

        val disarmed = TriggerMaintenance.disarmTriggersMissingCommands(registry, runtime)

        assertTrue(disarmed.isEmpty())
        assertTrue(runtime.armedTriggers().contains("w-ok"))
    }

    @Test
    fun disarmsArmedTriggerWhoseSpecVanishedFromTheStore() = runTest {
        runtime.workflowStore().registerSpec(
            "w-nospec",
            WorkflowSpec(trigger = eventTrigger(), step = WorkflowStep.Command("hello.world")),
        )
        runtime.armTrigger("w-nospec")
        runtime.workflowStore().remove("w-nospec")

        val disarmed = TriggerMaintenance.disarmTriggersMissingCommands(registry, runtime)

        assertEquals(listOf("w-nospec"), disarmed)
        assertTrue(runtime.armedTriggers().isEmpty())
    }

    @Test
    fun sweepsDeepStepTreesForAnyMissingCommand() = runTest {
        registry.register(HelloPlugin())
        runtime.workflowStore().registerSpec(
            "w-deep",
            WorkflowSpec(
                trigger = eventTrigger(),
                // One resolvable leaf is not enough — every branch must resolve.
                step = WorkflowStep.Parallel(
                    steps = listOf(
                        WorkflowStep.Command("hello.world"),
                        WorkflowStep.Try(
                            step = WorkflowStep.Command("hello.world"),
                            compensation = listOf(
                                WorkflowStep.If(
                                    condition = WorkflowCondition.Always(value = true),
                                    thenStep = WorkflowStep.Command("hello.world"),
                                    elseStep = WorkflowStep.Command("example.gone"),
                                )
                            ),
                        ),
                    )
                ),
            ),
        )
        runtime.armTrigger("w-deep")

        val disarmed = TriggerMaintenance.disarmTriggersMissingCommands(registry, runtime)

        assertEquals(listOf("w-deep"), disarmed)
    }
}
