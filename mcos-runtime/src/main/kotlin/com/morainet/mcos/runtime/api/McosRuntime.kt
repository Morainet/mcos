package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.core.api.AGENT_PROBE_AUDIT_SOURCE
import com.morainet.mcos.runtime.core.api.ConfirmationDecision
import com.morainet.mcos.runtime.core.api.ExecuteHandle
import com.morainet.mcos.runtime.core.api.ExecuteRequest
import com.morainet.mcos.runtime.core.api.ExecutionStatus
import com.morainet.mcos.runtime.core.api.Payload
import com.morainet.mcos.runtime.core.api.PreviewCommand
import com.morainet.mcos.runtime.core.api.PreviewResult
import com.morainet.mcos.runtime.core.api.RuntimeEvent
import com.morainet.mcos.runtime.core.api.RuntimeGateway
import com.morainet.mcos.runtime.core.api.Source
import com.morainet.mcos.runtime.core.api.StubHostServices
import com.morainet.mcos.runtime.core.events.EventBus
import com.morainet.mcos.runtime.core.events.EventEnvelope
import com.morainet.mcos.runtime.core.events.TypedEventBus
import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.runtime.core.ir.ExecutionIr
import com.morainet.mcos.runtime.core.ir.ParseResult
import com.morainet.mcos.marketplace.PluginInstaller
import com.morainet.mcos.runtime.core.memory.EpisodicMemory
import com.morainet.mcos.runtime.core.memory.EpisodicOutcome
import com.morainet.mcos.runtime.core.memory.MemoryStore
import com.morainet.mcos.runtime.core.memory.RunSummarizer
import com.morainet.mcos.runtime.core.parse.DslParser
import com.morainet.mcos.security.permission.DefaultPermissionKernel
import com.morainet.mcos.security.permission.PermissionKernel
import com.morainet.mcos.runtime.core.plugin.LoadResult
import com.morainet.mcos.runtime.core.plugin.PluginLoader
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.registry.ResolveResult as RegistryResolveResult
import com.morainet.mcos.security.ArtifactSignature
import com.morainet.mcos.security.ArtifactVerifier
import com.morainet.mcos.security.AuthStampSigner
import com.morainet.mcos.security.CrashQuarantine
import com.morainet.mcos.security.EnterprisePolicySource
import com.morainet.mcos.security.HmacAuthStampSigner
import com.morainet.mcos.security.InMemoryPublisherKeyStore
import com.morainet.mcos.security.NullAuditLog
import com.morainet.mcos.security.PluginTrustGate
import com.morainet.mcos.security.ScopeBasedEgressPolicy
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.SlidingWindowCrashQuarantine
import com.morainet.mcos.security.TokenBucketRateLimiter
import com.morainet.mcos.security.audit.AuditLog
import com.morainet.mcos.sdk.McosPlugin
import com.morainet.mcos.runtime.core.workflow.EventTriggerManager
import com.morainet.mcos.runtime.core.workflow.ScheduleTriggerManager
import com.morainet.mcos.runtime.core.workflow.Trigger
import com.morainet.mcos.runtime.core.workflow.TriggerArmResult
import com.morainet.mcos.runtime.core.workflow.WorkflowEngine
import com.morainet.mcos.runtime.core.workflow.WorkflowJson
import com.morainet.mcos.runtime.core.workflow.WorkflowOutcome
import com.morainet.mcos.runtime.core.workflow.WorkflowResult
import com.morainet.mcos.runtime.core.workflow.WorkflowSpec
import com.morainet.mcos.runtime.core.workflow.WorkflowStep
import com.morainet.mcos.runtime.core.workflow.WorkflowStore
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.EventPublisher
import com.morainet.mcos.sdk.HostServices
import com.morainet.mcos.sdk.SideEffectClass
import com.morainet.mcos.sdk.MemoryFacade
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
    private val auditLog: AuditLog = NullAuditLog,
    private val permissionKernel: PermissionKernel = DefaultPermissionKernel(),
) : RuntimeGateway {
    private val summarizer = RunSummarizer(episodicMemory)

    // ─── Event triggers (05-workflow.md §9.2) ───────────────────────────
    //
    // Armed event triggers subscribe to the system event bus and launch their
    // workflow on match. The facade owns the launcher (RunManager launch +
    // runWorkflow with the event payload as __input and EVENT step source).
    private val triggerManager = EventTriggerManager(
        bus = eventBus,
        memory = memory,
        auditLog = auditLog,
    )

    // ─── Schedule triggers (05-workflow.md §9.3) ────────────────────────
    //
    // Armed cron schedules launch their workflow DIRECTLY when a boundary
    // arrives — not via the EventBus, whose subscriptions are at-most-once
    // with no redelivery (03 §11.4) and therefore incompatible with misfire
    // recovery. The facade owns the launcher (RunManager launch +
    // runWorkflow with empty inputs and SCHEDULE step source).
    private val scheduleTriggerManager = ScheduleTriggerManager(
        auditLog = auditLog,
    )

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
    override suspend fun execute(request: ExecuteRequest): ExecuteHandle {
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
                    is Plan.Commands -> runCommands(runId, plan.commands, startTime, timestamp, request.payload, request.source.name)
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
     * Execute a read-only probe batch for the Agent loop (06-agent.md §11.3,
     * RuntimeGateway.executeProbe port contract).
     *
     * Fail-closed semantics: resolve EVERY step first; if any step's
     * `sideEffectClass` is not `read` — including unresolvable commands —
     * the whole batch is rejected without executing anything. Only when the
     * entire batch is read-only does it run through the normal executor
     * pipeline (Stages 3→10), audited with source `AGENT_PROBE`. Read-class
     * commands pass the permission kernel without confirmation
     * (PermissionKernel: `sideEffectClass < write` never confirms), so probes
     * are auto-run yet still rate-limited, egress-checked (n/a for reads)
     * and audited exactly like any other invoke.
     */
    override suspend fun executeProbe(steps: List<Command>): List<CommandResult> {
        // Pre-flight: every step must resolve to a `read`-class command.
        for (step in steps) {
            val resolved = registry.resolve(step.id)
            if (resolved !is RegistryResolveResult.Found) {
                return listOf(
                    CommandResult.Err(
                        code = com.morainet.mcos.runtime.core.error.McosErrorCode.UNKNOWN_COMMAND.name,
                        message = "Probe rejected: unknown command '${step.id}' (probes must be read-only, 06 §11.3)",
                        retryable = false
                    )
                )
            }
            if (resolved.entry.descriptor.sideEffectClass != SideEffectClass.read) {
                return listOf(
                    CommandResult.Err(
                        code = com.morainet.mcos.runtime.core.error.McosErrorCode.PERMISSION_DENIED.name,
                        message = "Probe rejected: '${step.id}' has sideEffectClass " +
                            "'${resolved.entry.descriptor.sideEffectClass}' — only read steps auto-run (06 §11.3)",
                        retryable = false
                    )
                )
            }
        }
        return executor.executeSequence(steps, source = AGENT_PROBE_AUDIT_SOURCE)
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
        // Armed schedules are released first — disarming them cancels the
        // driver coroutine so no boundary tick can fire a run while the
        // scopes below are torn down. Then event triggers (same rationale
        // for in-flight bus events), then the runs themselves.
        scheduleTriggerManager.disarmAll()
        triggerManager.disarmAll()
        // Cancel every active run; the SupervisorJob's children are cancelled
        // in bulk by cancelling the scope's job as well.
        runManager.shutdown()
    }

    /**
     * Observe runtime events for a specific run as a [Flow].
     */
    override fun observe(runId: String): Flow<RuntimeEvent> = eventBus.observe(runId)

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
     * Arm the registered workflow's event trigger (05-workflow.md §9.2):
     * subscribe to the event bus per the trigger's filter; each matching
     * event launches the workflow with the event payload as its `__input`
     * (05 §6.2) and per-step audit source `EVENT`.
     *
     * @param preAuthorized `true` when the user pre-authorized the recipe at
     *        install time (05 §10) — carried to the launcher for the
     *        pre-authorization stamp flow (08 §4.1).
     * @return [TriggerArmResult.Armed], or [TriggerArmResult.Rejected] with a
     *         stable reason (unknown workflow, no trigger; schedule triggers
     *         are additionally validated for cron syntax, timezone, and
     *         satisfiability by the schedule manager).
     */
    suspend fun armTrigger(workflowId: String, preAuthorized: Boolean = false): TriggerArmResult {
        val spec: WorkflowSpec = workflowStore.spec(workflowId)
            ?: return TriggerArmResult.Rejected(workflowId, "workflow_not_found")
        val trigger = spec.trigger
            ?: return TriggerArmResult.Rejected(workflowId, "workflow_has_no_trigger")
        return when (trigger) {
            is Trigger.Schedule -> {
                // Cross-family hygiene: a spec re-registered with a different
                // trigger type must not leave the other family's entry live.
                triggerManager.disarm(workflowId)
                scheduleTriggerManager.arm(workflowId, trigger, preAuthorized) { id, inputs, pre ->
                    fireTriggeredWorkflow(id, inputs, pre, Source.SCHEDULE.name)
                }
            }
            // Event and Manual both route to the event manager: Event arms,
            // Manual is rejected there (manual_triggers_cannot_be_armed).
            else -> {
                scheduleTriggerManager.disarm(workflowId)
                triggerManager.arm(workflowId, trigger, preAuthorized) { id, inputs, pre ->
                    fireTriggeredWorkflow(id, inputs, pre, Source.EVENT.name)
                }
            }
        }
    }

    /** Disarm a previously armed trigger (either family). `true` if [workflowId] was armed. */
    fun disarmTrigger(workflowId: String): Boolean =
        scheduleTriggerManager.disarm(workflowId) || triggerManager.disarm(workflowId)

    /** Currently armed trigger workflow ids, both families (05 §9.2-§9.3). */
    fun armedTriggers(): List<String> =
        (scheduleTriggerManager.armed() + triggerManager.armed()).distinct().sorted()

    /**
     * Launcher the trigger managers invoke on a match (event) or boundary
     * (schedule). The run is launched on the owned run scope (P0-C2) so
     * shutdown cancels it; event payloads become `__input` (schedule runs get
     * the empty object, 05 §6.2) and every step is audited with the trigger
     * family's source (`EVENT` or `SCHEDULE`).
     */
    private fun fireTriggeredWorkflow(
        workflowId: String,
        inputs: JsonObject,
        preAuthorized: Boolean,
        stepSource: String,
    ) {
        // The workflow may have been removed from the store while armed
        // (uninstall disarms explicitly, but a store.clear() racing a fire is
        // possible) — a dangling armed trigger must not crash the handler.
        val step = workflowStore.get(workflowId) ?: return
        val runId = UUID.randomUUID().toString()
        runManager.launch(runId) {
            val startTime = System.currentTimeMillis()
            try {
                runWorkflow(
                    runId = runId,
                    step = step,
                    startTime = startTime,
                    timestamp = startTime,
                    payload = Payload.WorkflowRef(workflowId),
                    inputs = inputs,
                    stepSource = stepSource,
                    preAuthorized = preAuthorized,
                )
            } catch (e: CancellationException) {
                eventBus.publish(runId, RuntimeEvent.RunCancelled(runId))
            } catch (e: Exception) {
                eventBus.publish(runId, RuntimeEvent.RunFailed(runId, e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * How long a trigger-fired run waits for a foreground confirmation
     * before treating it as rejected (08-security.md §6.4.1: background
     * event confirmations time out after 5 minutes).
     */
    private val backgroundConfirmationTimeoutMs: Long = BACKGROUND_CONFIRMATION_TIMEOUT_MS

    /** A pre-authorized trigger run's shared stamp and the commands it covers. */
    private class PreAuthorization(
        val stamp: AuthStamp,
        val coveredCommandIds: Set<String>,
    )

    /**
     * Mint the per-run pre-authorization stamp (05 §10, 08 §4.1): one signed
     * [AuthStamp] whose scopes are the union of the required permissions of
     * every **read/write** command in the workflow. Network/destructive (and
     * control) commands are deliberately NOT covered — they return null from
     * the authFor supplier and go through the kernel's stricter EVENT rules.
     * TTL comes from the kernel's `authStampTtlMs` (§6.3, same source as
     * kernel-minted stamps). Returns null when no command is coverable.
     */
    private fun mintPreAuthorization(runId: String, step: WorkflowStep): PreAuthorization? {
        val scopes = mutableSetOf<String>()
        val covered = mutableSetOf<String>()
        for (commandId in collectCommandIds(step)) {
            val descriptor =
                (registry.resolve(commandId) as? RegistryResolveResult.Found)?.entry?.descriptor
                    ?: continue // unresolvable: leave to the normal path (UNKNOWN_COMMAND)
            if (descriptor.sideEffectClass == SideEffectClass.read ||
                descriptor.sideEffectClass == SideEffectClass.write
            ) {
                descriptor.permissions.forEach { scopes.add(it.name) }
                covered.add(commandId)
            }
        }
        if (covered.isEmpty()) return null
        val now = System.currentTimeMillis()
        val stamp = authStampSigner.sign(
            AuthStamp(
                runId = runId,
                commandId = "workflow.preauth",
                pluginId = "workflow",
                grantsUsed = scopes,
                issuedAt = now,
                expiresAt = now + permissionKernel.authStampTtlMs,
            )
        )
        return PreAuthorization(stamp, covered)
    }

    /**
     * Depth-first flatten of every [WorkflowStep.Command] leaf in the tree,
     * projecting each through [leaf]. The single traversal backs both the
     * command-id and command-args collectors (they differ only in the leaf
     * projection), so the control-flow recursion lives in exactly one place.
     */
    private fun <T> flatMapCommands(step: WorkflowStep, leaf: (WorkflowStep.Command) -> T): List<T> = when (step) {
        is WorkflowStep.Command -> listOf(leaf(step))
        is WorkflowStep.Sequential -> step.steps.flatMap { flatMapCommands(it, leaf) }
        is WorkflowStep.Parallel -> step.steps.flatMap { flatMapCommands(it, leaf) }
        is WorkflowStep.If ->
            flatMapCommands(step.thenStep, leaf) + (step.elseStep?.let { flatMapCommands(it, leaf) } ?: emptyList())
        is WorkflowStep.Loop -> flatMapCommands(step.body, leaf)
        is WorkflowStep.Retry -> flatMapCommands(step.step, leaf)
        is WorkflowStep.Try -> flatMapCommands(step.step, leaf) + step.compensation.flatMap { flatMapCommands(it, leaf) }
    }

    /** Every command id in the workflow tree, depth-first (may repeat). */
    private fun collectCommandIds(step: WorkflowStep): List<String> =
        flatMapCommands(step) { it.commandId }

    /**
     * Confirmation hook for trigger-fired runs (08 §5): surface the step's
     * CONFIRMATION_REQUIRED to the host with the background timeout budget
     * and, on approval, mint the retry stamp. Rejection or timeout returns
     * null and the step keeps its failure.
     */
    private suspend fun confirmTriggeredStep(
        runId: String,
        commandId: String,
        err: CommandResult.Err,
    ): AuthStamp? {
        val cmd = Command(commandId, JsonObject(emptyMap()))
        val decision = confirmations.requestConfirmation(
            runId = runId,
            index = 0,
            cmd = cmd,
            result = err,
            timeoutOverrideMs = backgroundConfirmationTimeoutMs,
        )
        return if (decision is ConfirmationDecision.Approve) {
            confirmations.mintAuthStamp(runId, cmd)
        } else {
            null
        }
    }

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
        source: String,
    ) {
        eventBus.publish(runId, RuntimeEvent.RunStarted(runId, commands.firstOrNull()?.id, timestamp))

        for ((index, cmd) in commands.withIndex()) {
            eventBus.publish(runId, RuntimeEvent.StepStarted(runId, index, cmd.id))

            val stepStart = System.currentTimeMillis()
            val result = executor.execute(cmd.id, cmd.args, source = source)

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
                                when (val retry = executor.execute(cmd.id, cmd.args, auth = confirmations.mintAuthStamp(runId, cmd), source = source)) {
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
     *
     * @param stepSource Audit source label for every command step (08 §14):
     *        `CLI` for manual runs, `EVENT` for trigger-fired runs.
     * @param preAuthorized Trigger-fired runs armed with the user's install-
     *        time consent (05 §10): read/write steps run on one shared
     *        pre-authorization stamp, network/destructive steps still confirm.
     */
    private suspend fun runWorkflow(
        runId: String,
        step: WorkflowStep,
        startTime: Long,
        timestamp: Long,
        payload: Payload,
        inputs: JsonObject = JsonObject(emptyMap()),
        stepSource: String = "CLI",
        preAuthorized: Boolean = false,
    ) {
        eventBus.publish(runId, RuntimeEvent.RunStarted(runId, null, timestamp))

        // Pre-authorization (05 §10, 08 §4.1): one stamp covering the union
        // of read/write steps' required permissions. Supplied-stamp steps
        // skip the kernel; everything else keeps the kernel's EVENT rules.
        val preAuth = if (preAuthorized) mintPreAuthorization(runId, step) else null
        val authFor: (String) -> AuthStamp? = if (preAuth != null) {
            { commandId -> if (commandId in preAuth.coveredCommandIds) preAuth.stamp else null }
        } else {
            { _ -> null }
        }
        // Trigger-fired runs surface mid-run confirmations to the host
        // (network/destructive steps, 08 §4.0 step 4) with the background
        // timeout budget instead of failing the workflow outright. Both
        // background families qualify — EVENT and SCHEDULE.
        val confirmFor: (suspend (String, CommandResult.Err) -> AuthStamp?)? =
            if (stepSource == Source.EVENT.name || stepSource == Source.SCHEDULE.name) {
                { commandId, err -> confirmTriggeredStep(runId, commandId, err) }
            } else {
                null
            }

        val result = workflowEngine.execute(
            step,
            inputs = inputs,
            stepSource = stepSource,
            authFor = authFor,
            confirmFor = confirmFor,
        )

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
    private fun collectCommandArgs(step: WorkflowStep): List<JsonObject> =
        flatMapCommands(step) { it.args }

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

    companion object {
        /** 08-security.md §6.4.1: background event confirmations get 5 minutes. */
        private const val BACKGROUND_CONFIRMATION_TIMEOUT_MS = 300_000L
    }

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

        // Audit sink (03-runtime.md §13). NullAuditLog keeps "no audit trail"
        // as the explicit default; hosts opt in with InMemoryAuditLog (tests)
        // or FileAuditLog (persistence across restarts).
        private var auditLog: AuditLog = NullAuditLog

        fun withParser(parser: DslParser) = apply { this.parser = parser }
        fun withRegistry(registry: CommandRegistry) = apply { this.registry = registry }
        fun withPermissionKernel(kernel: PermissionKernel) = apply { this.permissionKernel = kernel }

        /**
         * Inject a host-built [Executor]. Its SecurityConfig wins over the
         * builder-assembled one — including the permission kernel, stamp
         * signer and audit log. Stateful security components MUST be the
         * same instances the facade uses, or the flows that span both sides
         * break: e.g. a [HmacAuthStampSigner] that differs from the one in
         * the injected executor's SecurityConfig makes every post-approval
         * AuthStamp fail verification ("failed signature verification"),
         * because the ConfirmationCoordinator signs with this builder's
         * signer while the executor verifies with its own. Pass the shared
         * instances explicitly via [withPermissionKernel] /
         * [withAuthStampSigner] / [withAuditLog] AND copy them into the
         * executor's SecurityConfig.
         */
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

        /**
         * Set the audit sink for both wiring paths the builder assembles:
         * the runtime-local [SecurityConfig] (when no [Executor] is injected)
         * and the default [WorkflowEngine]. With [withExecutor], the injected
         * executor's own SecurityConfig wins — wrap your audit log there
         * instead. Defaults to [NullAuditLog].
         */
        fun withAuditLog(auditLog: AuditLog) = apply { this.auditLog = auditLog }

        fun build(): McosRuntime {
            val reg = registry ?: CommandRegistry()
            val perm = permissionKernel ?: DefaultPermissionKernel()

            // The executor's security posture is assembled from this builder's
            // knobs. [NullAuditLog] preserves the historical default (no audit
            // trail) — hosts opt in via [withAuditLog] or by passing their own
            // Executor/SecurityConfig.
            val security = SecurityConfig(
                kernel = perm,
                rateLimiter = TokenBucketRateLimiter(),
                egress = ScopeBasedEgressPolicy(),
                signer = authStampSigner,
                quarantine = quarantine,
                enterprisePolicy = enterprisePolicySource,
                auditLog = auditLog,
            )
            val exec = executor ?: Executor(
                reg,
                // The default services expose the event-bus publisher so
                // sys.event.emit (03 §11 demo event source) works out of the
                // box. Hosts injecting their own Executor attach (or omit)
                // the capability on their own HostServices.
                object : HostServices by StubHostServices(memory) {
                    override val events = object : EventPublisher {
                        override suspend fun publish(type: String, payload: JsonObject) {
                            eventBus.publishEvent(
                                EventEnvelope(
                                    type = type,
                                    timestamp = System.currentTimeMillis(),
                                    payload = payload,
                                    source = "sys.event.emit",
                                )
                            )
                        }
                    }
                },
                security,
            )

            // The workflow engine defaults to the same executor, so control
            // flow steps and flat commands share one execution pipeline.
            val wfEngine = workflowEngine ?: WorkflowEngine(exec, auditLog)

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
                auditLog = auditLog,
                permissionKernel = perm,
            )
        }
    }
}

