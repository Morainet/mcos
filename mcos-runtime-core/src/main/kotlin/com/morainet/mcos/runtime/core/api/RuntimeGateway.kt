package com.morainet.mcos.runtime.core.api

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
interface RuntimeGateway {

    /**
     * Submit an execution request. Returns as soon as the run is accepted;
     * follow progress via [observe].
     */
    suspend fun execute(request: ExecuteRequest): ExecuteHandle

    /** Observe the [RuntimeEvent] stream of a specific run. */
    fun observe(runId: String): Flow<RuntimeEvent>
}
