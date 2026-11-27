package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.PreviewCommand
import com.morainet.mcos.runtime.core.api.PreviewResult
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.runtime.core.ir.ExecutionIr
import com.morainet.mcos.runtime.core.ir.ParseResult
import com.morainet.mcos.runtime.core.parse.DslParser
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.registry.ResolveResult as RegistryResolveResult
import com.morainet.mcos.runtime.core.workflow.WorkflowJson
import com.morainet.mcos.runtime.core.workflow.WorkflowStep
import com.morainet.mcos.runtime.core.workflow.WorkflowStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Internal plan produced by payload parsing.
 *
 * Commands are executed linearly through the executor; workflows are
 * delegated to the workflow engine which adds control flow on top.
 */
internal sealed interface RuntimePlan {
    data class Commands(val commands: List<Command>) : RuntimePlan
    data class Workflow(val step: WorkflowStep) : RuntimePlan

    /** Payload could not be parsed into an executable plan. */
    data class ParseFailed(
        val code: String,
        val message: String,
        val line: Int = 1,
        val column: Int = 1,
        val token: String? = null,
        val expected: List<String>? = null,
    ) : RuntimePlan {
        /** Human-readable error with location details, e.g. "Invalid payload: syntax at line 2, column 5: ..." */
        fun toErrorMessage(): String {
            val loc = if (line > 1 || column > 1) " at line $line, column $column" else ""
            return "Invalid payload: $code$loc: $message"
        }
    }

    fun isEmpty(): Boolean = when (this) {
        is Commands -> commands.isEmpty()
        is Workflow -> false
        is ParseFailed -> false
    }
}

/**
 * Translates all runtime input formats into the same execution plan and builds
 * previews without invoking handlers, requesting approval, or scheduling runs.
 * Named workflows are resolved on each request so registry/store updates remain visible.
 */
internal class RuntimeRequestCompiler(
    private val parser: DslParser,
    private val registry: CommandRegistry,
    private val workflowStore: WorkflowStore,
) {
    fun parse(payload: Payload): RuntimePlan {
        return when (payload) {
            is Payload.DslText -> {
                val result = parser.parse(payload.text)
                when (result) {
                    is ParseResult.Ok -> planFromIr(result.ir)
                    is ParseResult.Err -> RuntimePlan.ParseFailed(
                        code = result.code,
                        message = result.message,
                        line = result.line,
                        column = result.column,
                        token = result.token,
                        expected = result.expected,
                    )
                }
            }
            is Payload.IrJson -> {
                val ir = DslParser.fromJsonElement(payload.json)
                if (ir != null) {
                    planFromIr(ir)
                } else {
                    RuntimePlan.ParseFailed(code = "invalid_ir", message = "IR JSON is not a valid execution IR")
                }
            }
            is Payload.WorkflowRef -> {
                val step = workflowStore.get(payload.workflowId)
                if (step != null) RuntimePlan.Workflow(step) else RuntimePlan.Commands(emptyList())
            }
        }
    }

    private fun planFromIr(ir: ExecutionIr): RuntimePlan {
        return when (ir) {
            is ExecutionIr.Invoke -> RuntimePlan.Commands(listOf(Command(ir.invoke.id, ir.invoke.args)))
            is ExecutionIr.Sequence -> RuntimePlan.Commands(ir.sequence.steps.map { Command(it.id, it.args) })
            is ExecutionIr.Workflow -> {
                val step = WorkflowJson.fromJson(ir.body)
                if (step != null) {
                    RuntimePlan.Workflow(step)
                } else {
                    RuntimePlan.ParseFailed(code = "invalid_workflow", message = "Workflow IR body is not a valid workflow definition")
                }
            }
        }
    }

    fun preview(payload: Payload): PreviewResult {
        return when (val plan = parse(payload)) {
            is RuntimePlan.ParseFailed -> PreviewResult(
                commandCount = 0,
                commands = emptyList(),
                warnings = listOf(plan.toErrorMessage()),
            )
            is RuntimePlan.Commands -> {
                val warnings = mutableListOf<String>()

                val previewCommands = plan.commands.map { cmd ->
                    val resolved = registry.resolve(cmd.id)
                    val sideEffectClass = when (resolved) {
                        is RegistryResolveResult.Found -> resolved.entry.descriptor.sideEffectClass.name
                        else -> "unknown"
                    }
                    if (resolved !is RegistryResolveResult.Found) {
                        warnings.add("Unknown command: ${cmd.id}")
                    }
                    PreviewCommand(
                        id = cmd.id,
                        // DSL args may be JsonNull (e.g. `meta=null`); keep them as
                        // the literal string "null" instead of crashing on .content.
                        args = cmd.args.mapValues { (_, v) ->
                            v.jsonPrimitive.takeUnless { it is JsonNull }?.content ?: "null"
                        },
                        sideEffectClass = sideEffectClass,
                    )
                }

                PreviewResult(
                    commandCount = previewCommands.size,
                    commands = previewCommands,
                    warnings = warnings,
                )
            }
            is RuntimePlan.Workflow -> {
                val count = countCommands(plan.step)
                PreviewResult(
                    commandCount = count,
                    commands = listOf(
                        PreviewCommand(
                            id = "workflow",
                            args = mapOf("estimatedCommands" to count.toString()),
                            sideEffectClass = "workflow",
                        )
                    ),
                    warnings = if (count == 0) listOf("Workflow contains no commands") else emptyList(),
                )
            }
        }
    }

    /**
     * Estimate how many commands a workflow may execute (upper bound for
     * loops/retries based on their configured limits).
     */
    private fun countCommands(step: WorkflowStep): Int {
        return when (step) {
            is WorkflowStep.Command -> 1
            is WorkflowStep.Sequential -> step.steps.sumOf { countCommands(it) }
            is WorkflowStep.Parallel -> step.steps.sumOf { countCommands(it) }
            is WorkflowStep.If -> countCommands(step.thenStep) + (step.elseStep?.let { countCommands(it) } ?: 0)
            is WorkflowStep.Loop -> countCommands(step.body) * step.maxIterations
            is WorkflowStep.Retry -> countCommands(step.step) * (step.maxRetries + 1)
            is WorkflowStep.Try -> countCommands(step.step) + step.compensation.sumOf { countCommands(it) }
        }
    }

}
