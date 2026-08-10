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
import com.mcos.runtime.validate.SchemaValidator
import com.mcos.runtime.validate.ValidationError as ValError
import com.mcos.runtime.validate.ValidationResult
import com.mcos.sdk.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executor — invokes [CommandHandler]s with timeout, cancellation, and
 * structured error mapping.
 *
 * Implements MCOS Runtime spec [03-runtime.md 9].
 *
 * Pipeline: Stage 3 (Resolve) → Stage 5 (Validate) → Stage 6 (Authorize) → Stage 8 (Invoke) → Stage 10 (Audit)
 *
 * @param registry The [CommandRegistry] to resolve command IDs.
 * @param hostServices The [HostServices] facade injected into each [ExecutionContext].
 * @param permissionKernel Optional [PermissionKernel] for Stage 6 authorization.
 *        If null, all commands execute without permission checks (permissive mode).
 * @param auditLog Optional [AuditLog] for Stage 10 audit recording.
 */
class Executor(
    private val registry: CommandRegistry,
    private val hostServices: HostServices,
    private val permissionKernel: PermissionKernel? = null,
    private val auditLog: AuditLog? = null
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

        // Stage 6 — Authorization (if PermissionKernel is configured)
        val effectiveAuth = if (permissionKernel != null && auth == null) {
            when (val authz = permissionKernel.authorize(entry.descriptor)) {
                is AuthorizationResult.Authorized -> authz.stamp
                is AuthorizationResult.Denied -> {
                    return CommandResult.Err(
                        code = McosErrorCode.PERMISSION_DENIED.name,
                        message = "Permission denied for '${entry.descriptor.id}': ${authz.reason}",
                        retryable = true
                    )
                }
                is AuthorizationResult.ConfirmationNeeded -> {
                    return CommandResult.Err(
                        code = McosErrorCode.CONFIRMATION_REQUIRED.name,
                        message = "Confirmation needed for '${entry.descriptor.id}': ${authz.reason}",
                        retryable = true,
                        details = buildJsonObject {
                            put("sideEffectClass", JsonPrimitive(authz.sideEffectClass.name))
                            put("reason", JsonPrimitive(authz.reason))
                        }
                    )
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
