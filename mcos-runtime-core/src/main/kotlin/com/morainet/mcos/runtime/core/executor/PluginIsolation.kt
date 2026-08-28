package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandResult
import kotlinx.serialization.json.JsonObject

/**
 * Where a command handler runs relative to the MCOS main (trusted) process
 * ([08-security.md §8]).
 */
enum class IsolationMode {
    /** In the main process, sharing its memory space. Only for [TrustLevel.BUILTIN]. */
    IN_PROCESS,

    /**
     * In a separate, sandboxed process behind an identity-checked boundary
     * ([08-security.md §8.1]). Targeted for every non-builtin trust level.
     */
    ISOLATED,
}

/**
 * Pure trust-level → isolation-strategy decision ([08-security.md §7.2]).
 *
 * `BUILTIN` plugins always run in-process because they ship with the runtime
 * and share the platform key — they are treated as part of MCOS itself. Every
 * other trust level *targets* process isolation, because an adversarial
 * in-process plugin could otherwise read the runtime's grant cache or the
 * `AuthStamp` signing key ([08-security.md §7.2], §5.2 MVP-limitation note).
 *
 * This is a decision, not an enforcement: whether an `ISOLATED` command can
 * actually be isolated depends on an [IsolationHost] being wired. When none is
 * present the [Executor] falls back to a best-effort in-process invocation —
 * the documented MVP posture — recorded in the audit trail.
 *
 * `UNTRUSTED` never reaches here: [com.morainet.mcos.runtime.core.registry.CommandRegistry.register]
 * refuses to register it. It maps to [IsolationMode.ISOLATED] for exhaustiveness
 * only.
 */
object IsolationPolicy {
    fun modeFor(trustLevel: TrustLevel): IsolationMode = when (trustLevel) {
        TrustLevel.BUILTIN -> IsolationMode.IN_PROCESS
        TrustLevel.MARKETPLACE_VERIFIED,
        TrustLevel.SIDELOAD_DEBUG,
        TrustLevel.UNTRUSTED -> IsolationMode.ISOLATED
    }
}

/**
 * A fully-marshalable invocation request handed across the process boundary to
 * an [IsolationHost] ([08-security.md §8.1]). It deliberately carries **no**
 * [com.morainet.mcos.sdk.HostServices] and no handler reference: in the isolated
 * model the plugin process receives a Binder-stub `HostServices` proxy and the
 * main process keeps the real facade, so only identity + arguments + the signed
 * [AuthStamp] cross the boundary.
 *
 * @param pluginId reverse-DNS plugin id.
 * @param pluginVersion the resolved plugin version at dispatch time.
 * @param commandId fully-qualified command id, e.g. `camera.capture`.
 * @param args validated JSON arguments (secret templates are still unresolved;
 *        the isolated facade resolves them on the way out, [08-security.md §9.2]).
 * @param auth the run-bound, signed authorization stamp the isolated facade
 *        verifies before performing any privileged OS call ([08-security.md §8.2]).
 * @param runId the audit run id already minted by the [Executor].
 * @param deadlineMs wall-clock epoch millis by which the invocation must finish.
 * @param source audit source label (CLI/CHAT/EVENT/SCHEDULE/…).
 */
data class IsolatedInvocation(
    val pluginId: String,
    val pluginVersion: String,
    val commandId: String,
    val args: JsonObject,
    val auth: AuthStamp?,
    val runId: String,
    val deadlineMs: Long,
    val source: String,
)

/**
 * Host seam for running a non-builtin plugin's command outside the main process
 * ([08-security.md §8.1]). The Android host implements this with a bound
 * service; runtime-core stays pure JVM.
 *
 * A runtime built without an [IsolationHost] falls back to a best-effort
 * in-process invocation for non-builtin plugins — the documented MVP posture —
 * and records `plugin.isolation_fallback` so the weaker boundary is auditable.
 * Mirrors the [com.morainet.mcos.runtime.core.workflow.WakeScheduler] seam.
 */
interface IsolationHost {
    /**
     * Run [request] in an isolated process and return its result. A remote
     * plugin crash MUST be mapped to a [CommandResult.Err] rather than thrown,
     * so a plugin process death never takes down the runtime
     * ([08-security.md §8.1]).
     */
    suspend fun invoke(request: IsolatedInvocation): CommandResult
}
