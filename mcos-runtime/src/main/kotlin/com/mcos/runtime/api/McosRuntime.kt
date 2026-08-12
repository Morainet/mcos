package com.mcos.runtime.api

import com.mcos.runtime.events.EventBus
import com.mcos.runtime.events.TypedEventBus
import com.mcos.runtime.executor.Command
import com.mcos.runtime.executor.Executor
import com.mcos.runtime.ir.ExecutionIr
import com.mcos.runtime.ir.ParseResult
import com.mcos.runtime.memory.MemoryStore
import com.mcos.runtime.parse.DslParser
import com.mcos.runtime.permission.PermissionKernel
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.runtime.registry.ResolveResult as RegistryResolveResult
import com.mcos.runtime.security.AuthStampSigner
import com.mcos.runtime.security.NetworkEgressPolicy
import com.mcos.runtime.security.RateLimiter
import com.mcos.runtime.workflow.WorkflowEngine
import com.mcos.runtime.workflow.WorkflowJson
import com.mcos.runtime.workflow.WorkflowOutcome
import com.mcos.runtime.workflow.WorkflowStep
import com.mcos.runtime.workflow.WorkflowStore
import com.mcos.sdk.CommandResult
import com.mcos.sdk.MemoryFacade
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Top-level runtime facade that wires all subsystems together.
 *
 * Implements the logical [McosRuntime] contract defined in [03-runtime.md 4].
 * In P1 (single-process MVP), the App holds a direct reference to this class.
 *
 * ## Usage
 *
 * ```kotlin
 * val runtime = McosRuntime.Builder()
 *     .withRegistry(CommandRegistry())
 *     .withPermissionKernel(PermissionKernel(...))
 *     .withMemory(MemoryStore())
 *     .withAuditFile("/data/mcos/audit.log")
 *     .build()
 *
 * val handle = runtime.execute(ExecuteRequest(
 *     source = Source.CHAT,
 *     payload = Payload.DslText("camera.capture(quality=\"high\")")
 * ))
 *
 * runtime.observe(handle.runId).collect { event ->
 *     when (event) {
 *         is RuntimeEvent.RunSucceeded -> println("Done!")
 *         is RuntimeEvent.RunFailed -> println("Failed: ${event.error}")
 *         else -> { /* progress updates */ }
 *     }
 * }
 * ```
 *
 * @param parser The DSL parser (default: [DslParser]).
 * @param registry The command registry.
 * @param permissionKernel The permission kernel.
 * @param executor The command executor.
 * @param memory The memory store.
 * @param eventBus The event bus for publishing runtime events.
 * @param workflowStore The registry of named workflow definitions.
 * @param workflowEngine The engine that executes workflow definitions.
 */
class McosRuntime internal constructor(
    private val parser: DslParser,
    private val registry: CommandRegistry,
    private val permissionKernel: PermissionKernel,
    private val executor: Executor,
    private val memory: MemoryStore,
    private val eventBus: EventBus,
    private val workflowStore: WorkflowStore,
    private val workflowEngine: WorkflowEngine,
) {
    // ─── Active run tracking ─────────────────────────────────────────────

    private val activeRuns = ConcurrentHashMap<String, Job>()

    /**
     * Internal plan produced by payload parsing.
     *
     * Commands are executed linearly through the executor; workflows are
     * delegated to [WorkflowEngine] which adds control flow on top.
     */
    private sealed interface Plan {
        data class Commands(val commands: List<Command>) : Plan
        data class Workflow(val step: WorkflowStep) : Plan

        /** Payload could not be parsed into an executable plan. */
        data class ParseFailed(
            val code: String,
            val message: String,
            val line: Int = 1,
            val column: Int = 1,
            val token: String? = null,
            val expected: List<String>? = null,
        ) : Plan {
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

    // ─── Public API (matching 03-runtime.md 4) ─────────────────────────

    /**
     * Execute a request. Parses the payload, resolves commands, and runs
     * them through the executor pipeline.
     *
     * @return [ExecuteHandle] that can be used to observe or cancel the run.
     */
    suspend fun execute(request: ExecuteRequest): ExecuteHandle {
        val runId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // Parse payload into an execution plan
        val plan = parsePayload(request.payload)
        if (plan.isEmpty()) {
            val error = when (val p = request.payload) {
                is Payload.WorkflowRef -> "Workflow not found: ${p.workflowId}"
                else -> "No executable commands parsed from payload"
            }
            eventBus.publish(runId, RuntimeEvent.RunFailed(runId, error))
            return ExecuteHandle(runId, ExecutionStatus.FAILED)
        }

        // Parse failures are surfaced synchronously with their details.
        if (plan is Plan.ParseFailed) {
            eventBus.publish(runId, RuntimeEvent.RunFailed(runId, plan.toErrorMessage()))
            return ExecuteHandle(runId, ExecutionStatus.FAILED)
        }

        // Preview/dry-run
        if (request.dryRun) {
            return ExecuteHandle(runId, ExecutionStatus.COMPLETED)
        }

        // Launch execution in a coroutine
        val job = CoroutineScope(Dispatchers.Default).launch {
            val startTime = System.currentTimeMillis()
            try {
                when (plan) {
                    is Plan.Commands -> runCommands(runId, plan.commands, startTime, timestamp)
                    is Plan.Workflow -> runWorkflow(runId, plan.step, startTime, timestamp)
                    is Plan.ParseFailed -> Unit // unreachable: intercepted above
                }
            } catch (e: CancellationException) {
                eventBus.publish(runId, RuntimeEvent.RunCancelled(runId))
            } catch (e: Exception) {
                eventBus.publish(runId, RuntimeEvent.RunFailed(runId, e.message ?: "Unknown error"))
            } finally {
                activeRuns.remove(runId)
            }
        }

        activeRuns[runId] = job
        return ExecuteHandle(runId, ExecutionStatus.RUNNING)
    }

    /**
     * Preview an execution request without running it. Returns parsed
     * commands and any warnings.
     */
    suspend fun preview(request: ExecuteRequest): PreviewResult {
        return when (val plan = parsePayload(request.payload)) {
            is Plan.ParseFailed -> PreviewResult(
                commandCount = 0,
                commands = emptyList(),
                warnings = listOf(plan.toErrorMessage()),
            )
            is Plan.Commands -> {
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
            is Plan.Workflow -> {
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
     * Cancel a running execution by its runId.
     */
    fun cancel(runId: String) {
        activeRuns[runId]?.cancel()
    }

    /**
     * Observe runtime events for a specific run as a [Flow].
     */
    fun observe(runId: String): Flow<RuntimeEvent> = eventBus.observe(runId)

    /**
     * Access the command registry.
     */
    fun registry(): CommandRegistry = registry

    /**
     * Access the permission kernel.
     */
    fun permissions(): PermissionKernel = permissionKernel

    /**
     * Access the memory facade.
     */
    fun memory(): MemoryFacade = memory

    /**
     * Access the event bus.
     */
    fun events(): EventBus = eventBus

    /**
     * Access the workflow store for registering/loading named workflows.
     */
    fun workflowStore(): WorkflowStore = workflowStore

    // ─── Internal helpers ────────────────────────────────────────────────

    private fun parsePayload(payload: Payload): Plan {
        return when (payload) {
            is Payload.DslText -> {
                val result = parser.parse(payload.text)
                when (result) {
                    is ParseResult.Ok -> planFromIr(result.ir)
                    is ParseResult.Err -> Plan.ParseFailed(
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
                    Plan.ParseFailed(code = "invalid_ir", message = "IR JSON is not a valid execution IR")
                }
            }
            is Payload.WorkflowRef -> {
                val step = workflowStore.get(payload.workflowId)
                if (step != null) Plan.Workflow(step) else Plan.Commands(emptyList())
            }
        }
    }

    private fun planFromIr(ir: ExecutionIr): Plan {
        return when (ir) {
            is ExecutionIr.Invoke -> Plan.Commands(listOf(Command(ir.invoke.id, ir.invoke.args)))
            is ExecutionIr.Sequence -> Plan.Commands(ir.sequence.steps.map { Command(it.id, it.args) })
            is ExecutionIr.Workflow -> {
                val step = WorkflowJson.fromJson(ir.body)
                if (step != null) {
                    Plan.Workflow(step)
                } else {
                    Plan.ParseFailed(code = "invalid_workflow", message = "Workflow IR body is not a valid workflow definition")
                }
            }
        }
    }

    /**
     * Run a flat list of commands through the executor, publishing events.
     */
    private suspend fun runCommands(
        runId: String,
        commands: List<Command>,
        startTime: Long,
        timestamp: Long,
    ) {
        eventBus.publish(runId, RuntimeEvent.RunStarted(runId, commands.firstOrNull()?.id, timestamp))

        for ((index, cmd) in commands.withIndex()) {
            eventBus.publish(runId, RuntimeEvent.StepStarted(runId, index, cmd.id))

            val stepStart = System.currentTimeMillis()
            val result = executor.execute(cmd.id, cmd.args)

            val stepDuration = System.currentTimeMillis() - stepStart
            when (result) {
                is CommandResult.Ok -> {
                    eventBus.publish(runId, RuntimeEvent.StepSucceeded(runId, index, cmd.id, stepDuration))
                    result.artifacts.forEach { artifact ->
                        eventBus.publish(
                            runId,
                            RuntimeEvent.ArtifactEmitted(runId, artifact.type, artifact.uri, artifact.mimeType)
                        )
                    }
                }
                is CommandResult.Err -> {
                    eventBus.publish(
                        runId,
                        RuntimeEvent.StepFailed(runId, index, cmd.id, result.message)
                    )
                    if (result.code == "CONFIRMATION_REQUIRED") {
                        eventBus.publish(
                            runId,
                            RuntimeEvent.ConfirmationNeeded(runId, cmd.id, result.message)
                        )
                    }
                    val totalDuration = System.currentTimeMillis() - startTime
                    eventBus.publish(runId, RuntimeEvent.RunFailed(runId, result.message))
                    return
                }
            }
        }

        val totalDuration = System.currentTimeMillis() - startTime
        eventBus.publish(runId, RuntimeEvent.RunSucceeded(runId, totalDuration))
    }

    /**
     * Run a workflow definition via [WorkflowEngine], mapping step results
     * onto runtime events.
     */
    private suspend fun runWorkflow(
        runId: String,
        step: WorkflowStep,
        startTime: Long,
        timestamp: Long,
    ) {
        eventBus.publish(runId, RuntimeEvent.RunStarted(runId, null, timestamp))

        val result = workflowEngine.execute(step)

        result.steps.forEachIndexed { index, stepResult ->
            val commandId = stepResult.commandId ?: "workflow.control"
            if (stepResult.ok) {
                eventBus.publish(
                    runId,
                    RuntimeEvent.StepSucceeded(runId, index, commandId, stepResult.durationMs)
                )
            } else {
                eventBus.publish(
                    runId,
                    RuntimeEvent.StepFailed(
                        runId,
                        index,
                        commandId,
                        stepResult.message ?: stepResult.code ?: "Workflow step failed"
                    )
                )
            }
        }

        when (result.outcome) {
            WorkflowOutcome.COMPLETED ->
                eventBus.publish(runId, RuntimeEvent.RunSucceeded(runId, result.totalDurationMs))
            WorkflowOutcome.FAILED -> {
                val error = result.steps.lastOrNull { !it.ok }?.message ?: "Workflow failed"
                eventBus.publish(runId, RuntimeEvent.RunFailed(runId, error))
            }
            WorkflowOutcome.CANCELLED ->
                eventBus.publish(runId, RuntimeEvent.RunCancelled(runId))
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

    // ─── Builder ─────────────────────────────────────────────────────────

    /**
     * Builder for [McosRuntime] with sensible defaults.
     */
    class Builder {
        private var parser: DslParser = DslParser
        private var registry: CommandRegistry? = null
        private var permissionKernel: PermissionKernel? = null
        private var executor: Executor? = null
        private var memory: MemoryStore = MemoryStore()
        private var eventBus: EventBus = TypedEventBus()
        private var workflowStore: WorkflowStore = WorkflowStore()
        private var workflowEngine: WorkflowEngine? = null

        // Signed stamps are enabled by default so the production path is
        // secure out of the box; pass null to disable.
        private var authStampSigner: AuthStampSigner? = AuthStampSigner()

        fun withParser(parser: DslParser) = apply { this.parser = parser }
        fun withRegistry(registry: CommandRegistry) = apply { this.registry = registry }
        fun withPermissionKernel(kernel: PermissionKernel) = apply { this.permissionKernel = kernel }
        fun withExecutor(executor: Executor) = apply { this.executor = executor }
        fun withMemory(memory: MemoryStore) = apply { this.memory = memory }
        fun withEventBus(eventBus: EventBus) = apply { this.eventBus = eventBus }
        fun withWorkflowStore(store: WorkflowStore) = apply { this.workflowStore = store }
        fun withWorkflowEngine(engine: WorkflowEngine) = apply { this.workflowEngine = engine }
        fun withAuthStampSigner(signer: AuthStampSigner?) = apply { this.authStampSigner = signer }

        fun build(): McosRuntime {
            val reg = registry ?: CommandRegistry()
            val perm = permissionKernel ?: PermissionKernel()
            val exec = executor ?: Executor(
                reg, StubHostServices(memory),
                permissionKernel = perm,
                rateLimiter = RateLimiter(),
                egressPolicy = NetworkEgressPolicy(),
                authStampSigner = authStampSigner,
            )

            // The workflow engine defaults to the same executor, so control
            // flow steps and flat commands share one execution pipeline.
            val wfEngine = workflowEngine ?: WorkflowEngine(exec)

            return McosRuntime(
                parser = parser,
                registry = reg,
                permissionKernel = perm,
                executor = exec,
                memory = memory,
                eventBus = eventBus,
                workflowStore = workflowStore,
                workflowEngine = wfEngine,
            )
        }
    }
}

/**
 * Minimal [com.mcos.sdk.HostServices] stub for tests and default construction.
 * Real Android host should provide a full implementation.
 */
class StubHostServices(
    override val memory: MemoryFacade,
) : com.mcos.sdk.HostServices {
    override val files: com.mcos.sdk.FileService get() = error("FileService not available in stub")
    override val net: com.mcos.sdk.NetService get() = error("NetService not available in stub")
    override val ui: com.mcos.sdk.UiService get() = error("UiService not available in stub")
    override val secureStore: com.mcos.sdk.SecureStore get() = error("SecureStore not available in stub")
    override val clock: com.mcos.sdk.Clock get() = object : com.mcos.sdk.Clock {
        override fun nowMs(): Long = System.currentTimeMillis()
    }
    override val json: com.mcos.sdk.JsonService get() = error("JsonService not available in stub")
}
