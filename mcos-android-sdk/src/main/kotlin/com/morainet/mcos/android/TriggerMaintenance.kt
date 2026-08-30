package com.morainet.mcos.android

import com.morainet.mcos.runtime.api.McosRuntime
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.registry.ResolveResult
import com.morainet.mcos.runtime.core.workflow.WorkflowStep

/**
 * Host-side trigger hygiene (05 §9): sweeps that keep the armed-trigger set
 * consistent with the live registry. Extracted from the demo shell's
 * ViewModel (item 40) so any integrating app runs the same maintenance
 * behind its own UI.
 */
object TriggerMaintenance {

    /**
     * Disarm armed triggers whose workflow depends on commands that no longer
     * resolve in the registry (an uninstalled package provided them), or
     * whose spec vanished from the store. Such a trigger would fire and fail
     * on every matching event — disarming it with the package is the honest
     * state. Returns the disarmed workflow ids (for the user-facing
     * "disarmed trigger: …" note).
     */
    fun disarmTriggersMissingCommands(registry: CommandRegistry, runtime: McosRuntime): List<String> {
        val disarmed = mutableListOf<String>()
        runtime.armedTriggers().forEach { workflowId ->
            val spec = runtime.workflowStore().spec(workflowId)
            val missing = spec == null || collectCommandIds(spec.step).any {
                registry.resolve(it) !is ResolveResult.Found
            }
            if (missing && runtime.disarmTrigger(workflowId)) disarmed += workflowId
        }
        return disarmed
    }

    /** Every command id a step tree can reach (trigger-dependency sweep). */
    private fun collectCommandIds(step: WorkflowStep): List<String> = when (step) {
        is WorkflowStep.Command -> listOf(step.commandId)
        is WorkflowStep.Sequential -> step.steps.flatMap(::collectCommandIds)
        is WorkflowStep.Parallel -> step.steps.flatMap(::collectCommandIds)
        is WorkflowStep.If ->
            collectCommandIds(step.thenStep) +
                (step.elseStep?.let(::collectCommandIds) ?: emptyList())
        is WorkflowStep.Loop -> collectCommandIds(step.body)
        is WorkflowStep.Retry -> collectCommandIds(step.step)
        is WorkflowStep.Try ->
            collectCommandIds(step.step) + step.compensation.flatMap(::collectCommandIds)
    }
}
