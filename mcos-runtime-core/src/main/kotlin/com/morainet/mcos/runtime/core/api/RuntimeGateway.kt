package com.morainet.mcos.runtime.core.api

import com.morainet.mcos.runtime.core.executor.Command
import com.morainet.mcos.sdk.CommandResult
import kotlinx.coroutines.flow.Flow

/**
 * Narrow consumer-side port into the runtime kernel: submit a run and observe
 * its events. All parameter and return types live in this package, so any
 * module that already depends on `mcos-runtime-core` can drive executions
 * without depending on the `mcos-runtime` facade (or anything it re-exports,
 * such as marketplace).
 *
 * The canonical implementation is the facade's `McosRuntime`
 * (`com.morainet.mcos.runtime.api.McosRuntime`); `ChatOrchestrator`
 * (`mcos-llm`) is the reference consumer. Keeping the port here rather than
 * in the facade preserves the acyclic module graph: llm and the facade are
 * sibling clients of the kernel (01-architecture.md §3.2).
 */
/**
 * Audit source label the kernel stamps on read-only Agent probe invocations
 * (06-agent.md §11.3). Lives here so both the facade implementation and the
 * `mcos-llm` Agent (a sibling client of the kernel) reference one constant.
 */
const val AGENT_PROBE_AUDIT_SOURCE = "AGENT_PROBE"

interface RuntimeGateway {

    /**
     * Submit an execution request. Returns as soon as the run is accepted;
     * follow progress via [observe].
     */
    suspend fun execute(request: ExecuteRequest): ExecuteHandle

    /** Observe the [RuntimeEvent] stream of a specific run. */
    fun observe(runId: String): Flow<RuntimeEvent>

    /**
     * Execute a read-only probe batch for the Agent loop (06-agent.md §11.3).
     *
     * Unlike [execute], this returns the full [CommandResult] payloads —
     * probes exist to produce *observations*, and `Ok.value` is the
     * observation. The implementation MUST refuse the whole batch if any
     * step's resolved `sideEffectClass` is not `read`: the Agent may only
     * auto-run reads, everything else waits for explicit user confirmation.
     * Each step still pays the full Stage 3→10 pipeline cost, audited with
     * source `AGENT_PROBE`.
     *
     * @param steps Ordered read-only invocations.
     * @return Per-step results; on a non-read step the single-element list
     *   carries the rejection without executing anything.
     */
    suspend fun executeProbe(steps: List<Command>): List<CommandResult>
}
