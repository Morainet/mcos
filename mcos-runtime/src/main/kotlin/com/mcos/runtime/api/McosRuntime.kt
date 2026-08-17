package com.mcos.runtime.api

import com.mcos.runtime.events.EventBus
import com.mcos.runtime.events.TypedEventBus
import com.mcos.runtime.executor.Command
import com.mcos.runtime.executor.Executor
import com.mcos.runtime.ir.ExecutionIr
import com.mcos.runtime.ir.ParseResult
import com.mcos.runtime.marketplace.PluginInstaller
import com.mcos.runtime.memory.EpisodicMemory
import com.mcos.runtime.memory.EpisodicOutcome
import com.mcos.runtime.memory.MemoryStore
import com.mcos.runtime.memory.RunSummarizer
import com.mcos.runtime.parse.DslParser
import com.mcos.runtime.permission.DefaultPermissionKernel
import com.mcos.runtime.permission.PermissionKernel
import com.mcos.runtime.plugin.LoadResult
import com.mcos.runtime.plugin.PluginLoader
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.runtime.registry.ResolveResult as RegistryResolveResult
import com.mcos.runtime.security.ArtifactSignature
import com.mcos.runtime.security.ArtifactVerifier
import com.mcos.runtime.security.AuthStampSigner
import com.mcos.runtime.security.CrashQuarantine
import com.mcos.runtime.security.EnterprisePolicySource
import com.mcos.runtime.security.HmacAuthStampSigner
import com.mcos.runtime.security.InMemoryPublisherKeyStore
import com.mcos.runtime.security.NullAuditLog
import com.mcos.runtime.security.PluginTrustGate
import com.mcos.runtime.security.ScopeBasedEgressPolicy
import com.mcos.runtime.security.SecurityConfig
import com.mcos.runtime.security.SlidingWindowCrashQuarantine
import com.mcos.runtime.security.TokenBucketRateLimiter
import com.mcos.sdk.McosPlugin
import com.mcos.runtime.workflow.WorkflowEngine
import com.mcos.runtime.workflow.WorkflowJson
import com.mcos.runtime.workflow.WorkflowOutcome
import com.mcos.runtime.workflow.WorkflowResult
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
 * @param executor The command executor.
 * @param memory The memory store.
 * @param eventBus The event bus for publishing runtime events.
 * @param workflowStore The registry of named workflow definitions.
 * @param workflowEngine The engine that executes workflow definitions.
 * @param episodicMemory Archival-tier store for run summaries (§8).
 * @param authStampSigner Signs the retry stamps minted by the confirmation flow.
 * @param confirmationTimeoutMs How long a run suspends awaiting confirmation.
 * @param pluginLoader The trust-gated plugin loader ([09-marketplace.md §7.0]).
 * @param pluginInstaller Optional end-to-end installer ([09-marketplace.md §7]).
 */
class McosRuntime internal constructor(
    private val parser: DslParser,
    private val registry: CommandRegistry,
    private val executor: Executor,
    private val memory: MemoryStore,
    private val eventBus: EventBus,
    private val workflowStore: WorkflowStore,
    private val workflowEngine: WorkflowEngine,
    private val episodicMemory: EpisodicMemory,
    private val authStampSigner: AuthStampSigner,
    private val confirmationTimeoutMs: Long,
    private val pluginLoader: PluginLoader,
    private val pluginInstaller: PluginInstaller?,
) {
    private val summarizer = RunSummarizer(episodicMemory)

    // ─── Confirmation flow (08-security.md §5) ──────────────────────────
    //
    // Commands whose side-effect class requires confirmation return
    // CONFIRMATION_REQUIRED. The run suspends on a deferred until the host
    // answers via respondConfirmation(); an approved command is retried with a
    // signed AuthStamp so the permission kernel is bypassed for exactly that
    // command/run. See [ConfirmationCoordinator].
    private val confirmations = ConfirmationCoordinator(
        eventBus = eventBus,
        signer = authStampSigner,
        registry = registry,
        timeoutMs = confirmationTimeoutMs,
    )

    // ─── Active run tracking ─────────────────────────────────────────────
    //
    // Runs execute as children of an owned, supervised scope so shutdown()
    // cancels them cleanly (P0-C2). See [RunManager].
    private val runManager = RunManager()

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

        // Launch execution as a child of the owned run scope (P0-C2), so the
        // runtime owns the run's lifecycle and shutdown() cancels it cleanly.
        runManager.launch(runId) {
            val startTime = System.currentTimeMillis()
            try {
                when (plan) {
                    is Plan.Commands -> runCommands(runId, plan.commands, startTime, timestamp, request.payload)
                    is Plan.Workflow -> runWorkflow(runId, plan.step, startTime, timestamp, request.payload)
                    is Plan.ParseFailed -> Unit // unreachable: intercepted above
                }
            } catch (e: CancellationException) {
                eventBus.publish(runId, RuntimeEvent.RunCancelled(runId))
            } catch (e: Exception) {
                eventBus.publish(runId, RuntimeEvent.RunFailed(runId, e.message ?: "Unknown error"))
            }
        }

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
        runManager.cancel(runId)
    }

    /**
     * Shut down the runtime: cancel every in-flight run and release the owned
     * coroutine scope (P0-C2). Idempotent; safe to call once the runtime is no
     * longer needed (e.g. from the host's `onDestroy`). After shutdown, new
     * [execute] calls will launch on a cancelled scope and complete immediately
     * as cancelled — callers should not reuse a shut-down runtime.
     */
    fun shutdown() {
        // Cancel every active run; the SupervisorJob's children are cancelled
        // in bulk by cancelling the scope's job as well.
        runManager.shutdown()
    }

    /**
     * Observe runtime events for a specific run as a [Flow].
     */
    fun observe(runId: String): Flow<RuntimeEvent> = eventBus.observe(runId)

    /**
     * Answer a pending confirmation request (08-security.md §5). The run that
     * emitted [RuntimeEvent.ConfirmationNeeded] stays suspended until this is
     * called or the confirmation timeout elapses.
     *
     * @return `true` if a pending request for the given run/command existed and
     *         was answered; `false` if it was already answered or timed out.
     */
    suspend fun respondConfirmation(
        runId: String,
        commandId: String,
        decision: ConfirmationDecision,
    ): Boolean = confirmations.respond(runId, commandId, decision)

    /**
     * Access the command registry.
     */
    fun registry(): CommandRegistry = registry

    /**
     * Access the memory facade.
     */
    fun memory(): MemoryFacade = memory

    /**
     * Access the episodic (run-summary) memory store (§8).
     */
    fun episodicMemory(): EpisodicMemory = episodicMemory

    /**
     * Access the workflow store for registering/loading named workflows.
     */
    fun workflowStore(): WorkflowStore = workflowStore

    /**
     * Load a plugin into the runtime ([09-marketplace.md §7.0]). The plugin
     * is first evaluated by the [PluginTrustGate] (which consults the
     * enterprise policy, incl. `disableSideload`) and only registered when
     * the trust decision allows it.
     *
     * @return [LoadResult] describing the outcome; see [LoadResult.Installed]
     *         and [LoadResult.Denied].
     */
    fun loadPlugin(
        packageId: String,
        version: String,
        payload: ByteArray? = null,
        signature: ArtifactSignature? = null,
        builtin: Boolean = false,
        plugin: McosPlugin,
    ): LoadResult = pluginLoader.load(
        packageId = packageId,
        version = version,
        payload = payload,
        signature = signature,
        builtin = builtin,
        plugin = plugin,
    )

    /**
     * Access the plugin installer (download → verify → stage → load,
     * [09-marketplace.md §7]), or null when the host did not configure one.
     */
    fun pluginInstaller(): PluginInstaller? = pluginInstaller

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
        payload: Payload,
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
                    if (result.code == "CONFIRMATION_REQUIRED") {
                        val decision = confirmations.requestConfirmation(runId, index, cmd, result)
                        when (decision) {
                            is ConfirmationDecision.Approve -> {
                                // Retry exactly this command with a signed, run-scoped
                                // AuthStamp so the permission kernel is bypassed for
                                // the confirmed command only (08-security.md §5.2).
                                when (val retry = executor.execute(cmd.id, cmd.args, auth = confirmations.mintAuthStamp(runId, cmd))) {
                                    is CommandResult.Ok -> {
                                        val retryDuration = System.currentTimeMillis() - stepStart
                                        eventBus.publish(
                                            runId,
                                            RuntimeEvent.StepSucceeded(runId, index, cmd.id, retryDuration)
                                        )
                                        retry.artifacts.forEach { artifact ->
                                            eventBus.publish(
                                                runId,
                                                RuntimeEvent.ArtifactEmitted(
                                                    runId, artifact.type, artifact.uri, artifact.mimeType
                                                )
                                            )
                                        }
                                        continue
                                    }

                                    is CommandResult.Err -> {
                                        eventBus.publish(runId, RuntimeEvent.StepFailed(runId, index, cmd.id, retry.message))
                                        summarize(runId, commands, payload, EpisodicOutcome.FAILED)
                                        eventBus.publish(runId, RuntimeEvent.RunFailed(runId, retry.message))
                                        return
                                    }
                                }
                            }

                            is ConfirmationDecision.Reject -> {
                                val rejection = "Confirmation rejected for '${cmd.id}'"
                                eventBus.publish(runId, RuntimeEvent.StepFailed(runId, index, cmd.id, rejection))
                                summarize(runId, commands, payload, EpisodicOutcome.FAILED)
                                eventBus.publish(runId, RuntimeEvent.RunFailed(runId, rejection))
                                return
                            }
                        }
                    }
                    eventBus.publish(
                        runId,
                        RuntimeEvent.StepFailed(runId, index, cmd.id, result.message)
                    )
                    summarize(runId, commands, payload, EpisodicOutcome.FAILED)
                    val totalDuration = System.currentTimeMillis() - startTime
                    eventBus.publish(runId, RuntimeEvent.RunFailed(runId, result.message))
                    return
                }
            }
        }

        summarize(runId, commands, payload, EpisodicOutcome.SUCCESS)
        val totalDuration = System.currentTimeMillis() - startTime
        eventBus.publish(runId, RuntimeEvent.RunSucceeded(runId, totalDuration))
    }

    // ─── Confirmation helpers live in [ConfirmationCoordinator] ─────────

    /**
     * Record the run in the episodic memory (07-memory.md §9.4). The summary
     * text comes from the raw DSL payload; the JSON/IR payloads fall back to
     * the command-id listing. Entities are memory paths referenced by the
     * command arguments.
     */
    private fun summarize(runId: String, commands: List<Command>, payload: Payload, outcome: EpisodicOutcome) {
        val raw = (payload as? Payload.DslText)?.text
        summarizer.summarize(
            runId = runId,
            summary = raw.orEmpty(),
            commandIds = commands.map { it.id },
            argsByCommand = commands.map { it.args },
            outcome = outcome,
            timestamp = System.currentTimeMillis(),
        )
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
        payload: Payload,
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
            WorkflowOutcome.COMPLETED -> {
                summarizeWorkflow(runId, step, result, payload, EpisodicOutcome.SUCCESS)
                eventBus.publish(runId, RuntimeEvent.RunSucceeded(runId, result.totalDurationMs))
            }
            WorkflowOutcome.FAILED -> {
                summarizeWorkflow(runId, step, result, payload, EpisodicOutcome.FAILED)
                val error = result.steps.lastOrNull { !it.ok }?.message ?: "Workflow failed"
                eventBus.publish(runId, RuntimeEvent.RunFailed(runId, error))
            }
            WorkflowOutcome.CANCELLED -> {
                summarizeWorkflow(runId, step, result, payload, EpisodicOutcome.CANCELLED)
                eventBus.publish(runId, RuntimeEvent.RunCancelled(runId))
            }
        }
    }

    /**
     * Record a finished workflow run in the episodic memory (§9.4). Command
     * ids come from the executed steps; entities are extracted from the
     * workflow definition's command arguments.
     */
    private fun summarizeWorkflow(
        runId: String,
        step: WorkflowStep,
        result: WorkflowResult,
        payload: Payload,
        outcome: EpisodicOutcome,
    ) {
        val raw = (payload as? Payload.DslText)?.text
        summarizer.summarize(
            runId = runId,
            summary = raw.orEmpty(),
            commandIds = result.steps.mapNotNull { it.commandId },
            argsByCommand = collectCommandArgs(step),
            outcome = outcome,
            timestamp = System.currentTimeMillis(),
        )
    }

    /**
     * Collect every command's argument map from a workflow definition,
     * depth-first, for entity extraction.
     */
    private fun collectCommandArgs(step: WorkflowStep): List<JsonObject> {
        return when (step) {
            is WorkflowStep.Command -> listOf(step.args)
            is WorkflowStep.Sequential -> step.steps.flatMap { collectCommandArgs(it) }
            is WorkflowStep.Parallel -> step.steps.flatMap { collectCommandArgs(it) }
            is WorkflowStep.If ->
                collectCommandArgs(step.thenStep) + (step.elseStep?.let { collectCommandArgs(it) } ?: emptyList())
            is WorkflowStep.Loop -> collectCommandArgs(step.body)
            is WorkflowStep.Retry -> collectCommandArgs(step.step)
            is WorkflowStep.Try -> collectCommandArgs(step.step) + step.compensation.flatMap { collectCommandArgs(it) }
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
        private var episodicMemory: EpisodicMemory = EpisodicMemory()
        private var eventBus: EventBus = TypedEventBus()
        private var workflowStore: WorkflowStore = WorkflowStore()
        private var workflowEngine: WorkflowEngine? = null

        // Signed stamps are enabled by default so the production path is
        // secure out of the box; pass TrustingAuthStampSigner to disable
        // (the named, greppable opt-out).
        private var authStampSigner: AuthStampSigner = HmacAuthStampSigner()

        // Crash-loop quarantine (08-security.md §15.3) is on by default.
        // NoopCrashQuarantine is the named opt-out.
        private var quarantine: CrashQuarantine = SlidingWindowCrashQuarantine()

        // How long a run stays suspended awaiting a confirmation response
        // (08-security.md §6.3) before it is treated as rejected.
        private var confirmationTimeoutMs: Long = 60_000

        // Enterprise policy source (08-security.md §13). Defaults to the
        // named no-policy source; enforcement is a deliberate wiring choice.
        private var enterprisePolicySource: EnterprisePolicySource = EnterprisePolicySource.None

        // Plugin trust pipeline (09-marketplace.md §6). When no loader is
        // injected, a default trust gate + verifier + in-memory key store is
        // built so `loadPlugin` is fail-closed out of the box.
        private var pluginLoader: PluginLoader? = null

        // End-to-end install flow (09-marketplace.md §7). Optional: hosts
        // without a download dir / transport skip the installer.
        private var pluginInstaller: PluginInstaller? = null

        fun withParser(parser: DslParser) = apply { this.parser = parser }
        fun withRegistry(registry: CommandRegistry) = apply { this.registry = registry }
        fun withPermissionKernel(kernel: PermissionKernel) = apply { this.permissionKernel = kernel }
        fun withExecutor(executor: Executor) = apply { this.executor = executor }
        fun withMemory(memory: MemoryStore) = apply { this.memory = memory }
        fun withEpisodicMemory(episodicMemory: EpisodicMemory) = apply { this.episodicMemory = episodicMemory }
        fun withEventBus(eventBus: EventBus) = apply { this.eventBus = eventBus }
        fun withWorkflowStore(store: WorkflowStore) = apply { this.workflowStore = store }
        fun withWorkflowEngine(engine: WorkflowEngine) = apply { this.workflowEngine = engine }
        fun withAuthStampSigner(signer: AuthStampSigner) = apply { this.authStampSigner = signer }
        fun withQuarantine(quarantine: CrashQuarantine) = apply { this.quarantine = quarantine }
        fun withConfirmationTimeoutMs(ms: Long) = apply { this.confirmationTimeoutMs = ms }

        /**
         * Enable enterprise policy enforcement with a hot-reloadable source.
         * Call [FileEnterprisePolicySource] or a custom implementation; for a
         * static policy use `EnterprisePolicySource.fixed(...)`.
         */
        fun withEnterprisePolicySource(source: EnterprisePolicySource) = apply { this.enterprisePolicySource = source }

        /**
         * Inject a custom plugin loader. When omitted, the default loader
         * wraps a [PluginTrustGate] (fail-closed: no verifier, `debugBuild=false`)
         * wired to the runtime's registry, so unsigned sideloads are denied
         * unless the enterprise policy explicitly permits them.
         */
        fun withPluginLoader(loader: PluginLoader) = apply { this.pluginLoader = loader }

        /**
         * Attach an end-to-end plugin installer ([09-marketplace.md §7]).
         * Optional — hosts without binary download support omit it.
         */
        fun withPluginInstaller(installer: PluginInstaller?) = apply { this.pluginInstaller = installer }

        fun build(): McosRuntime {
            val reg = registry ?: CommandRegistry()
            val perm = permissionKernel ?: DefaultPermissionKernel()

            // The executor's security posture is assembled from this builder's
            // knobs. [NullAuditLog] preserves the historical default (no audit
            // trail) — hosts pass an audit sink explicitly via a custom
            // Executor/SecurityConfig when they want one.
            val security = SecurityConfig(
                kernel = perm,
                rateLimiter = TokenBucketRateLimiter(),
                egress = ScopeBasedEgressPolicy(),
                signer = authStampSigner,
                quarantine = quarantine,
                enterprisePolicy = enterprisePolicySource,
                auditLog = NullAuditLog,
            )
            val exec = executor ?: Executor(reg, StubHostServices(memory), security)

            // The workflow engine defaults to the same executor, so control
            // flow steps and flat commands share one execution pipeline.
            val wfEngine = workflowEngine ?: WorkflowEngine(exec)

            // Default trust pipeline: fail-closed PluginTrustGate over an
            // empty in-memory key store. Hosts that verify marketplace
            // artifacts inject a real verifier + key store via withPluginLoader.
            val loader = pluginLoader ?: PluginLoader(
                trustGate = PluginTrustGate(
                    verifier = null,
                    debugBuild = false,
                    enterprisePolicy = { enterprisePolicySource?.current() },
                ),
                registry = reg,
            )

            return McosRuntime(
                parser = parser,
                registry = reg,
                executor = exec,
                memory = memory,
                eventBus = eventBus,
                workflowStore = workflowStore,
                workflowEngine = wfEngine,
                episodicMemory = episodicMemory,
                authStampSigner = authStampSigner,
                confirmationTimeoutMs = confirmationTimeoutMs,
                pluginLoader = loader,
                pluginInstaller = pluginInstaller,
            )
        }
    }
}

