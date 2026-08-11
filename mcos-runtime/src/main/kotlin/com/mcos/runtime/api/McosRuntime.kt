package com.mcos.runtime.api

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
import com.mcos.sdk.CommandResult
import com.mcos.sdk.MemoryFacade
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Minimal event bus stub for P1. Enables [McosRuntime.observe] to work
 * without requiring the full P2 EventBus subsystem.
 *
 * Upgrade path: replace with full typed pub/sub in P2 ([03-runtime.md 11]).
 */
interface EventBus {
    /** Publish an event for a specific run. */
    fun publish(runId: String, event: RuntimeEvent)

    /** Observe events for a specific run as a cold [Flow]. */
    fun observe(runId: String): Flow<RuntimeEvent>
}

class SimpleEventBus : EventBus {
    private val sharedFlow = MutableSharedFlow<RuntimeEvent>(replay = 256, extraBufferCapacity = 64)

    override fun publish(runId: String, event: RuntimeEvent) {
        sharedFlow.tryEmit(event)
    }

    override fun observe(runId: String): Flow<RuntimeEvent> {
        return sharedFlow.filter { event ->
            when (event) {
                is RuntimeEvent.RunStarted -> event.runId == runId
                is RuntimeEvent.StepStarted -> event.runId == runId
                is RuntimeEvent.Progress -> event.runId == runId
                is RuntimeEvent.ArtifactEmitted -> event.runId == runId
                is RuntimeEvent.LogEmitted -> event.runId == runId
                is RuntimeEvent.ConfirmationNeeded -> event.runId == runId
                is RuntimeEvent.StepSucceeded -> event.runId == runId
                is RuntimeEvent.StepFailed -> event.runId == runId
                is RuntimeEvent.RunSucceeded -> event.runId == runId
                is RuntimeEvent.RunFailed -> event.runId == runId
                is RuntimeEvent.RunCancelled -> event.runId == runId
            }
        }
    }
}

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
 */
class McosRuntime internal constructor(
    private val parser: DslParser,
    private val registry: CommandRegistry,
    private val permissionKernel: PermissionKernel,
    private val executor: Executor,
    private val memory: MemoryStore,
    private val eventBus: EventBus,
) {
    // ─── Active run tracking ─────────────────────────────────────────────

    private val activeRuns = ConcurrentHashMap<String, Job>()

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

        // Parse payload into commands
        val commands = parsePayload(request.payload)
        if (commands.isEmpty()) {
            eventBus.publish(runId, RuntimeEvent.RunFailed(runId, "No executable commands parsed from payload"))
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
                            activeRuns.remove(runId)
                            return@launch
                        }
                    }
                }

                val totalDuration = System.currentTimeMillis() - startTime
                eventBus.publish(runId, RuntimeEvent.RunSucceeded(runId, totalDuration))
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
        val commands = parsePayload(request.payload)
        val warnings = mutableListOf<String>()

        val previewCommands = commands.map { cmd ->
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
                args = cmd.args.mapValues { it.value.jsonPrimitive.content },
                sideEffectClass = sideEffectClass,
            )
        }

        return PreviewResult(
            commandCount = previewCommands.size,
            commands = previewCommands,
            warnings = warnings,
        )
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

    // ─── Internal helpers ────────────────────────────────────────────────

    private fun parsePayload(payload: Payload): List<Command> {
        return when (payload) {
            is Payload.DslText -> {
                val result = parser.parse(payload.text)
                when (result) {
                    is ParseResult.Ok -> extractCommands(result.ir)
                    is ParseResult.Err -> emptyList()
                }
            }
            is Payload.IrJson -> {
                val ir = DslParser.fromJsonElement(payload.json)
                if (ir != null) extractCommands(ir) else emptyList()
            }
            is Payload.WorkflowRef -> {
                // P2: load workflow from workflow engine
                emptyList()
            }
        }
    }

    private fun extractCommands(ir: ExecutionIr): List<Command> {
        return when (ir) {
            is ExecutionIr.Invoke -> listOf(Command(ir.invoke.id, ir.invoke.args))
            is ExecutionIr.Sequence -> ir.sequence.steps.map { Command(it.id, it.args) }
            is ExecutionIr.Workflow -> emptyList() // Workflow IR not yet supported
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
        private var eventBus: EventBus = SimpleEventBus()

        // Signed stamps are enabled by default so the production path is
        // secure out of the box; pass null to disable.
        private var authStampSigner: AuthStampSigner? = AuthStampSigner()

        fun withParser(parser: DslParser) = apply { this.parser = parser }
        fun withRegistry(registry: CommandRegistry) = apply { this.registry = registry }
        fun withPermissionKernel(kernel: PermissionKernel) = apply { this.permissionKernel = kernel }
        fun withExecutor(executor: Executor) = apply { this.executor = executor }
        fun withMemory(memory: MemoryStore) = apply { this.memory = memory }
        fun withEventBus(eventBus: EventBus) = apply { this.eventBus = eventBus }
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

            return McosRuntime(
                parser = parser,
                registry = reg,
                permissionKernel = perm,
                executor = exec,
                memory = memory,
                eventBus = eventBus,
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
