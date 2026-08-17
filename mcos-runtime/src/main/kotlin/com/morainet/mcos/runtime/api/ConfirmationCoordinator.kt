package com.morainet.mcos.runtime.api

import com.morainet.mcos.runtime.events.EventBus
import com.morainet.mcos.runtime.executor.Command
import com.morainet.mcos.runtime.registry.CommandRegistry
import com.morainet.mcos.runtime.registry.ResolveResult as RegistryResolveResult
import com.morainet.mcos.runtime.security.AuthStampSigner
import com.morainet.mcos.sdk.AuthStamp
import com.morainet.mcos.sdk.CommandResult
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the human-confirmation flow (08-security.md §5), extracted from
 * [McosRuntime] so the facade stays a thin wiring layer.
 *
 * Commands whose side-effect class requires confirmation return
 * CONFIRMATION_REQUIRED. The run suspends on a deferred until the host answers
 * via [respond] (or the timeout elapses, which is treated as a reject); an
 * approved command is retried with the signed, run-scoped [AuthStamp] from
 * [mintAuthStamp] so the permission kernel is bypassed for exactly that
 * command/run.
 *
 * @param eventBus Publishes [RuntimeEvent.ConfirmationNeeded] notifications.
 * @param signer Signs the retry stamp minted after an approval.
 * @param registry Resolves command descriptors so the minted stamp covers
 *   exactly the permissions the confirmed command requires.
 * @param timeoutMs How long a run stays suspended before the request is
 *   treated as rejected (08-security.md §6.3).
 */
internal class ConfirmationCoordinator(
    private val eventBus: EventBus,
    private val signer: AuthStampSigner,
    private val registry: CommandRegistry,
    private val timeoutMs: Long,
) {

    private val pendingConfirmations =
        ConcurrentHashMap<String, CompletableDeferred<ConfirmationDecision>>()

    private fun confirmationKey(runId: String, commandId: String) = "$runId\u0000$commandId"

    /**
     * Answer a pending confirmation request. The run that emitted
     * [RuntimeEvent.ConfirmationNeeded] stays suspended until this is called
     * or the confirmation timeout elapses.
     *
     * @return `true` if a pending request for the given run/command existed and
     *         was answered; `false` if it was already answered or timed out.
     */
    suspend fun respond(
        runId: String,
        commandId: String,
        decision: ConfirmationDecision,
    ): Boolean {
        val deferred = pendingConfirmations[confirmationKey(runId, commandId)] ?: return false
        deferred.complete(decision)
        return true
    }

    /**
     * Publish the [RuntimeEvent.ConfirmationNeeded] notification and suspend
     * the run until the host answers via [respond] or the confirmation
     * timeout elapses (timeout ⇒ reject).
     */
    suspend fun requestConfirmation(
        runId: String,
        index: Int,
        cmd: Command,
        result: CommandResult.Err,
    ): ConfirmationDecision {
        val sideEffectClass = (result.details?.get("sideEffectClass") as? JsonPrimitive)?.content
        eventBus.publish(
            runId,
            RuntimeEvent.ConfirmationNeeded(
                runId = runId,
                commandId = cmd.id,
                reason = result.message,
                sideEffectClass = sideEffectClass,
            )
        )
        return awaitConfirmation(runId, cmd.id)
    }

    private suspend fun awaitConfirmation(runId: String, commandId: String): ConfirmationDecision {
        val key = confirmationKey(runId, commandId)
        val deferred = CompletableDeferred<ConfirmationDecision>()
        pendingConfirmations[key] = deferred
        try {
            return withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: ConfirmationDecision.Reject
        } finally {
            pendingConfirmations.remove(key)
        }
    }

    /**
     * Mint a signed, run-scoped [AuthStamp] covering exactly the permissions
     * required by `cmd`, so a confirmed command can be retried without going
     * through the permission kernel again. Grants mirror what the kernel would
     * have included on an ordinary Authorized path.
     */
    fun mintAuthStamp(runId: String, cmd: Command): AuthStamp {
        val descriptor = (registry.resolve(cmd.id) as? RegistryResolveResult.Found)?.entry?.descriptor
        val now = System.currentTimeMillis()
        val grants = buildSet {
            descriptor?.permissions?.forEach { add(it.name) }
            descriptor?.let { addAll(implicitScopes(it.sideEffectClass)) }
        }
        val stamp = AuthStamp(
            runId = runId,
            commandId = cmd.id,
            pluginId = descriptor?.pluginId.orEmpty(),
            grantsUsed = grants,
            issuedAt = now,
            expiresAt = now + AUTH_STAMP_TTL_MS,
        )
        return signer.sign(stamp)
    }

    private fun implicitScopes(sideEffectClass: SideEffectClass): Set<String> = when (sideEffectClass) {
        SideEffectClass.network -> setOf("network.*")
        SideEffectClass.destructive -> setOf("mcos:destructive")
        SideEffectClass.control -> setOf("mcos:control")
        else -> emptySet()
    }

    private companion object {
        const val AUTH_STAMP_TTL_MS = 30_000L
    }
}
