package com.mcos.runtime.executor

import com.mcos.runtime.error.McosErrorCode
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
 * Implements MCOS Runtime spec [03-runtime.md §9].
 *
 * @param registry The [CommandRegistry] to resolve command IDs.
 * @param hostServices The [HostServices] facade injected into each [ExecutionContext].
 */
class Executor(
    private val registry: CommandRegistry,
    private val hostServices: HostServices
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

        val runId = UUID.randomUUID().toString()
        val timeoutMs = entry.descriptor.timeoutMs.coerceIn(1000, 600000)

        val ctx = ExecutionContext(
            runId = runId,
            commandId = entry.descriptor.id,
            args = args,
            auth = auth,
            deadline = System.currentTimeMillis() + timeoutMs,
            progress = progress,
            services = hostServices
        )

        return try {
            withTimeout(timeoutMs) {
                entry.handler.invoke(ctx)
            }
        } catch (e: TimeoutCancellationException) {
            CommandResult.Err(
                code = McosErrorCode.TIMEOUT.name,
                message = "Command '${entry.descriptor.id}' timed out after ${timeoutMs}ms",
                retryable = true
            )
        } catch (e: CancellationException) {
            CommandResult.Err(
                code = McosErrorCode.CANCELLED.name,
                message = "Command '${entry.descriptor.id}' was cancelled",
                retryable = false
            )
        } catch (e: McosException) {
            // Direct mapping — preserves plugin's declared error code
            CommandResult.Err(
                code = e.code,
                message = e.message,
                retryable = e.retryable,
                details = e.details
            )
        } catch (e: Exception) {
            // Generic exception → PLUGIN_ERROR with sanitized message
            CommandResult.Err(
                code = McosErrorCode.PLUGIN_ERROR.name,
                message = "Plugin error in '${entry.descriptor.id}': ${sanitize(e)}",
                retryable = false
            )
        }
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
