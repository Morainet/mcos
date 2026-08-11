package com.mcos.runtime.executor

import com.mcos.runtime.audit.AuditLog
import com.mcos.runtime.audit.RunRecord
import com.mcos.runtime.audit.StepRecord
import com.mcos.runtime.error.McosErrorCode
import com.mcos.runtime.permission.AuthorizationResult
import com.mcos.runtime.permission.PermissionKernel
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.runtime.registry.RegistryEntry
import com.mcos.runtime.registry.ResolveResult
import com.mcos.runtime.security.EgressDecision
import com.mcos.runtime.security.NetworkEgressPolicy
import com.mcos.runtime.security.RateLimitResult
import com.mcos.runtime.security.RateLimiter
import com.mcos.runtime.validate.SchemaValidator
import com.mcos.runtime.validate.ValidationError as ValError
import com.mcos.runtime.validate.ValidationResult
import com.mcos.sdk.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executor — invokes [CommandHandler]s with timeout, cancellation, and
 * structured error mapping.
 *
 * Implements MCOS Runtime spec [03-runtime.md 9].
 *
 * Pipeline: Stage 3 (Resolve) → Stage 5 (Validate) → Stage 5.5 (Rate Limit) → Stage 5.6 (Egress) → Stage 6 (Authorize) → Stage 8 (Invoke) → Stage 10 (Audit)
 *
 * @param registry The [CommandRegistry] to resolve command IDs.
 * @param hostServices The [HostServices] facade injected into each [ExecutionContext].
 * @param permissionKernel Optional [PermissionKernel] for Stage 6 authorization.
 *        If null, all commands execute without permission checks (permissive mode).
 * @param auditLog Optional [AuditLog] for Stage 10 audit recording.
 * @param rateLimiter Optional [RateLimiter] for Stage 5.5 rate limiting.
 *        If null, no rate limiting is applied.
 * @param egressPolicy Optional [NetworkEgressPolicy] for Stage 5.6 egress checks.
 *        If null, no egress validation is performed.
 */
class Executor(
    private val registry: CommandRegistry,
    private val hostServices: HostServices,
    private val permissionKernel: PermissionKernel? = null,
    private val auditLog: AuditLog? = null,
    private val rateLimiter: RateLimiter? = null,
    private val egressPolicy: NetworkEgressPolicy? = null,
) {

    private val schemaValidator = SchemaValidator()

    /**
     * Execute a single command by ID.
     *
     * @param commandId Fully-qualified command ID, e.g. "camera.capture".
     * @param args JSON arguments for the command handler.
     * @param auth Optional authorization stamp.
     * @param progress Optional progress emitter for streaming updates.
     * @return [CommandResult.Ok] on success, [CommandResult.Err] on any failure.
     */
    suspend fun execute(
        commandId: String,
        args: JsonObject = JsonObject(emptyMap()),
        auth: AuthStamp? = null,
        progress: ProgressEmitter? = null
    ): CommandResult {
        val resolved = registry.resolve(commandId)
        if (resolved !is ResolveResult.Found) {
            return CommandResult.Err(
                code = McosErrorCode.UNKNOWN_COMMAND.name,
                message = "Unknown command: $commandId",
                retryable = false
            )
        }
        return invokeHandler(resolved.entry, args, auth, progress)
    }

    /**
     * Execute a batch of commands sequentially.
     * Stops at the first failure — subsequent steps are not executed.
     *
     * @param steps Ordered list of invocations.
     * @param auth Optional authorization stamp shared across all steps.
     * @param progress Optional progress emitter.
     * @return List of results. If any step fails, the list ends with the error result.
     */
    suspend fun executeSequence(
        steps: List<Command>,
        auth: AuthStamp? = null,
        progress: ProgressEmitter? = null
    ): List<CommandResult> {
        val results = mutableListOf<CommandResult>()
        for (cmd in steps) {
            val result = execute(cmd.id, cmd.args, auth, progress)
            results.add(result)
            if (result is CommandResult.Err) break
        }
        return results
    }

    // ─── Internal dispatch ──────────────────────────────────────────────

    private suspend fun invokeHandler(
        entry: RegistryEntry,
        args: JsonObject,
        auth: AuthStamp?,
        progress: ProgressEmitter?
    ): CommandResult {
        // Stage 5 — Schema validation
        val schema = entry.descriptor.inputSchema
        if (schema.isNotEmpty()) {
            val validation = schemaValidator.validate(args, schema)
            if (validation is ValidationResult.Invalid) {
                val details = buildJsonDetails(validation.errors)
                return CommandResult.Err(
                    code = McosErrorCode.SCHEMA_VIOLATION.name,
                    message = "Schema validation failed for '${entry.descriptor.id}': ${validation.errors.size} error(s)",
                    retryable = false,
                    details = details
                )
            }
        }

        // Stage 5.5 — Rate limiting (if RateLimiter is configured)
        val limiter = rateLimiter
        if (limiter != null) {
            when (val limitResult = limiter.tryConsume(entry.descriptor.pluginId, entry.descriptor.sideEffectClass)) {
                is RateLimitResult.Limited -> {
                    return CommandResult.Err(
                        code = McosErrorCode.RATE_LIMITED.name,
                        message = "Rate limited for '${entry.descriptor.id}' (${limitResult.kind.key}): retry after ${limitResult.retryAfterMs}ms",
                        retryable = true,
                        details = buildJsonObject {
                            put("retryAfterMs", JsonPrimitive(limitResult.retryAfterMs))
                            put("kind", JsonPrimitive(limitResult.kind.key))
                        }
                    )
                }
                is RateLimitResult.Allowed -> { /* proceed */ }
            }
        }

        // Stage 5.6 — Network egress check (if EgressPolicy is configured)
        val policy = egressPolicy
        if (policy != null && entry.descriptor.sideEffectClass == SideEffectClass.network) {
            val urlArg = args["url"]?.let {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            }
            if (urlArg != null) {
                when (val egress = policy.decideEgress(urlArg, auth)) {
                    is EgressDecision.Deny -> {
                        return CommandResult.Err(
                            code = McosErrorCode.PERMISSION_DENIED.name,
                            message = "Network egress denied for '${entry.descriptor.id}': ${egress.reason}",
                            retryable = false,
                            details = buildJsonObject {
                                put("egressReason", JsonPrimitive(egress.reason))
                                egress.missingDomain?.let { put("missingDomain", JsonPrimitive(it)) }
                            }
                        )
                    }
                    is EgressDecision.Allow -> { /* proceed */ }
                }
            }
        }

        // Stage 6 — Authorization
        // Security: even when a caller supplies an AuthStamp, we verify it
        // (a) has not expired and (b) covers the descriptor's required
        // permissions. Without this check, any caller could fabricate an
        // AuthStamp to bypass authorization (privilege escalation).
        val effectiveAuth = if (permissionKernel != null) {
            if (auth != null) {
                // Validate supplied AuthStamp before trusting it
                val now = System.currentTimeMillis()
                if (auth.expiresAt <= now) {
                    return CommandResult.Err(
                        code = McosErrorCode.PERMISSION_DENIED.name,
                        message = "AuthStamp for '${entry.descriptor.id}' has expired",
                        retryable = false
                    )
                }
                val required = collectRequiredPermissions(entry.descriptor)
                val missing = required.filter { it !in auth.grantsUsed }
                if (missing.isNotEmpty()) {
                    return CommandResult.Err(
                        code = McosErrorCode.PERMISSION_DENIED.name,
                        message = "AuthStamp for '${entry.descriptor.id}' does not cover: ${missing.joinToString(", ")}",
                        retryable = false
                    )
                }
                auth
            } else {
                when (val authz = permissionKernel.authorize(entry.descriptor)) {
                    is AuthorizationResult.Authorized -> authz.stamp
                    is AuthorizationResult.Denied -> {
                        return CommandResult.Err(
                            code = McosErrorCode.PERMISSION_DENIED.name,
                            message = "Permission denied for '${entry.descriptor.id}': ${authz.reason}",
                            retryable = false
                        )
                    }
                    is AuthorizationResult.ConfirmationNeeded -> {
                        return CommandResult.Err(
                            code = McosErrorCode.CONFIRMATION_REQUIRED.name,
                            message = "Confirmation needed for '${entry.descriptor.id}': ${authz.reason}",
                            retryable = false,
                            details = buildJsonObject {
                                put("sideEffectClass", JsonPrimitive(authz.sideEffectClass.name))
                                put("reason", JsonPrimitive(authz.reason))
                            }
                        )
                    }
                }
            }
        } else {
            auth
        }

        val runId = UUID.randomUUID().toString()
        val timeoutMs = entry.descriptor.timeoutMs.coerceIn(1000, 600000)
        val startTime = System.currentTimeMillis()

        val ctx = ExecutionContext(
            runId = runId,
            commandId = entry.descriptor.id,
            args = args,
            auth = effectiveAuth?.copy(runId = runId),
            deadline = System.currentTimeMillis() + timeoutMs,
            progress = progress,
            services = hostServices
        )

        var result: CommandResult
        var outcome: String
        try {
            result = withTimeout(timeoutMs) {
                entry.handler.invoke(ctx)
            }
            outcome = "ok"
        } catch (e: TimeoutCancellationException) {
            result = CommandResult.Err(
                code = McosErrorCode.TIMEOUT.name,
                message = "Command '${entry.descriptor.id}' timed out after ${timeoutMs}ms",
                retryable = true
            )
            outcome = "timeout"
        } catch (e: CancellationException) {
            result = CommandResult.Err(
                code = McosErrorCode.CANCELLED.name,
                message = "Command '${entry.descriptor.id}' was cancelled",
                retryable = false
            )
            outcome = "cancelled"
        } catch (e: McosException) {
            result = CommandResult.Err(
                code = e.code,
                message = e.message,
                retryable = e.retryable,
                details = e.details
            )
            outcome = "failed"
        } catch (e: Exception) {
            result = CommandResult.Err(
                code = McosErrorCode.PLUGIN_ERROR.name,
                message = "Plugin error in '${entry.descriptor.id}': ${sanitize(e)}",
                retryable = false
            )
            outcome = "failed"
        }

        // Stage 10 — Audit recording
        val durationMs = System.currentTimeMillis() - startTime
        auditLog?.append(
            RunRecord(
                runId = runId,
                timestamp = startTime,
                source = "CLI",
                commandId = entry.descriptor.id,
                steps = listOf(
                    StepRecord(
                        commandId = entry.descriptor.id,
                        pluginId = entry.descriptor.pluginId,
                        ok = result is CommandResult.Ok,
                        code = (result as? CommandResult.Err)?.code,
                        message = (result as? CommandResult.Err)?.message?.take(200),
                        durationMs = durationMs,
                        artifacts = (result as? CommandResult.Ok)?.artifacts?.map {
                            com.mcos.runtime.audit.ArtifactRecord(it.type, it.uri, it.mimeType)
                        } ?: emptyList()
                    )
                ),
                totalDurationMs = durationMs,
                outcome = outcome
            )
        )

        return result
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /**
     * Collect the explicit permissions required by a descriptor.
     * Used for AuthStamp validation when a caller supplies their own stamp.
     * Implicit sideEffectClass scopes (network.*, mcos:destructive, etc.)
     * are added by PermissionKernel and need not be checked here.
     */
    private fun collectRequiredPermissions(descriptor: CommandDescriptor): List<String> {
        return descriptor.permissions.map { it.name }
    }

    /**
     * Sanitize an exception message for user-facing output.
     * Stack traces are NOT exposed.
     */
    private fun sanitize(e: Throwable): String {
        return e.message?.take(200) ?: e.javaClass.simpleName
    }

    /**
     * Build a JSON details object from validation errors.
     */
    private fun buildJsonDetails(errors: List<ValError>): JsonObject {
        return buildJsonObject {
            put("errors", buildJsonArray {
                errors.forEach { err ->
                    add(buildJsonObject {
                        put("path", JsonPrimitive(err.path))
                        put("expected", JsonPrimitive(err.expected))
                        put("actual", JsonPrimitive(err.actual))
                        put("code", JsonPrimitive(err.code))
                    })
                }
            })
        }
    }
}

/**
 * Lightweight command representation for batch execution.
 */
data class Command(
    val id: String,
    val args: JsonObject = JsonObject(emptyMap())
)
