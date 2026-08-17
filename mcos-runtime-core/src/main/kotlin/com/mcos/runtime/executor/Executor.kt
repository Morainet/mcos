package com.mcos.runtime.executor

import com.mcos.runtime.audit.RunOutcome
import com.mcos.runtime.audit.RunRecord
import com.mcos.runtime.audit.StepRecord
import com.mcos.runtime.error.McosErrorCode
import com.mcos.runtime.permission.AuthorizationResult
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.runtime.registry.RegistryEntry
import com.mcos.runtime.registry.ResolveResult
import com.mcos.runtime.security.EgressDecision
import com.mcos.runtime.security.RateLimitResult
import com.mcos.runtime.security.SecurityConfig
import com.mcos.runtime.security.SecretResolver
import com.mcos.runtime.validate.SchemaValidator
import com.mcos.runtime.validate.ValidationError as ValError
import com.mcos.runtime.validate.ValidationResult
import com.mcos.sdk.*
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

/**
 * Executor — invokes [CommandHandler]s with timeout, cancellation, and
 * structured error mapping.
 *
 * Implements MCOS Runtime spec [03-runtime.md 9].
 *
 * Pipeline: Stage 3 (Resolve) → Stage 5 (Validate) → Stage 5.5 (Rate Limit) → Stage 6 (Authorize) → Stage 6.5 (Egress, post-auth) → Stage 8 (Invoke) → Stage 10 (Audit)
 *
 * NOTE: egress checking deliberately runs AFTER Stage 6 authorization
 * (signature verification + permission grants). Reading `auth.grantsUsed`
 * before the stamp's signature is verified would let a caller forge a stamp
 * with arbitrary `network.*` scopes and bypass egress policy.
 *
 * ## Security posture
 *
 * The entire posture is wired through the required [security] parameter
 * ([SecurityConfig]) — there is no `null`-able security dependency and no
 * implicit permissive mode. Disabling a control is a named, greppable
 * choice: `SecurityConfig.permissive()` (or a `.copy(...)` of it), never a
 * missing argument.
 *
 * Caller-supplied [AuthStamp]s are validated unconditionally: expiry and
 * permission-coverage checks run regardless of the configured kernel, and
 * signature verification runs whenever the configured signer rejects the
 * stamp. Only the *named* [com.mcos.runtime.security.TrustingAuthStampSigner]
 * waives signature checks.
 *
 * @param registry The [CommandRegistry] to resolve command IDs.
 * @param hostServices The [HostServices] facade injected into each [ExecutionContext].
 * @param security The security posture — permission kernel, rate limiter,
 *        egress policy, stamp signer, quarantine, enterprise policy source
 *        and audit sink. Required; see [SecurityConfig.defaults] and
 *        [SecurityConfig.permissive].
 * @param globalKillSwitch Supplier consulted at Stage 6.5; when it returns
 *        true, every network egress request is denied immediately, before
 *        any domain-scope matching. Defaults to never-active.
 * @param debugMode When true, the egress policy allows non-HTTPS URLs
 *        (development only). Defaults to false.
 */
class Executor(
    private val registry: CommandRegistry,
    private val hostServices: HostServices,
    private val security: SecurityConfig,
    private val globalKillSwitch: () -> Boolean = { false },
    private val debugMode: Boolean = false,
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
        // Crash-loop quarantine gate (08-security.md §15.3): a quarantined
        // plugin refuses to execute even if its commands were re-registered.
        val pluginId = resolved.entry.descriptor.pluginId
        if (security.quarantine.isQuarantined(pluginId)) {
            return CommandResult.Err(
                code = McosErrorCode.UNAVAILABLE.name,
                message = "Plugin '$pluginId' is quarantined (crash-loop) and cannot execute",
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

        // Stage 5.5 — Rate limiting. The configured [RateLimiter] is always
        // consulted; `UnlimitedRateLimiter` is the named opt-out.
        when (val limitResult = security.rateLimiter.tryConsume(entry.descriptor.pluginId, entry.descriptor.sideEffectClass)) {
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

        // Stage 5.6 was here historically — egress now runs at Stage 6.5,
        // AFTER signature verification, so we only ever read a trusted
        // AuthStamp's grantsUsed (see P0-S1 security note in class kdoc).

        // Stage 6 — Authorization
        // Security: even when a caller supplies an AuthStamp, we verify it
        // (a) carries a valid signature (unless the named
        //     TrustingAuthStampSigner is wired),
        // (b) has not expired and (c) covers the descriptor's required
        //     permissions — both checks run UNCONDITIONALLY. Without these
        // checks, any caller could fabricate an AuthStamp to bypass
        // authorization (privilege escalation).
        if (auth != null && !security.signer.verify(auth)) {
            return CommandResult.Err(
                code = McosErrorCode.PERMISSION_DENIED.name,
                message = "AuthStamp for '${entry.descriptor.id}' failed signature verification",
                retryable = false
            )
        }
        val effectiveAuth = if (auth != null) {
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
            val enterprise = security.enterprisePolicy.current()
            when (val authz = security.kernel.authorize(entry.descriptor, enterprise)) {
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

        // Stage 6.5 — Network egress check (post-authorization).
        // Security: runs AFTER Stage 6 so grantsUsed is read from a stamp
        // whose signature and permission coverage have already been verified.
        // Without this ordering a caller could forge a stamp with
        // `network.<attacker-domain>` scopes and pass egress (P0-S1).
        if (entry.descriptor.sideEffectClass == SideEffectClass.network) {
            val kill = globalKillSwitch()
            // Inspect every string value in the argument tree — URLs nested in
            // objects/arrays are covered, not just the top-level "url" field.
            val urls = collectUrls(args)
            for (url in urls) {
                when (val egress = security.egress.decideEgress(url, effectiveAuth, kill, debugMode, security.enterprisePolicy.current())) {
                    is EgressDecision.Deny -> {
                        return CommandResult.Err(
                            code = McosErrorCode.PERMISSION_DENIED.name,
                            message = "Network egress denied for '${entry.descriptor.id}': ${egress.reason}",
                            retryable = false,
                            details = buildJsonObject {
                                put("url", JsonPrimitive(url))
                                put("egressReason", JsonPrimitive(egress.reason))
                                egress.missingDomain?.let { put("missingDomain", JsonPrimitive(it)) }
                            }
                        )
                    }
                    is EgressDecision.Allow -> { /* proceed */ }
                }
            }
        }

        val runId = UUID.randomUUID().toString()
        val timeoutMs = entry.descriptor.timeoutMs.coerceIn(1000, 600000)
        val startTime = System.currentTimeMillis()

        val ctx = ExecutionContext(
            runId = runId,
            commandId = entry.descriptor.id,
            args = args,
            auth = effectiveAuth?.let { stamp ->
                // Re-sign after runId is bound
                security.signer.sign(stamp.copy(runId = runId))
            },
            deadline = System.currentTimeMillis() + timeoutMs,
            progress = progress,
            // Stage 4 (Expand) — `{{secret.*}}` templates (08-security.md §9.2)
            // are resolved by the per-plugin NetService decorator; args keep
            // the template form and values never enter the audit trail.
            services = secretResolvingServices()
        )

        var result: CommandResult
        var outcome: RunOutcome
        try {
            result = withTimeout(timeoutMs) {
                entry.handler.invoke(ctx)
            }
            outcome = RunOutcome.OK
        } catch (e: TimeoutCancellationException) {
            result = CommandResult.Err(
                code = McosErrorCode.TIMEOUT.name,
                message = "Command '${entry.descriptor.id}' timed out after ${timeoutMs}ms",
                retryable = true
            )
            outcome = RunOutcome.TIMEOUT
        } catch (e: CancellationException) {
            result = CommandResult.Err(
                code = McosErrorCode.CANCELLED.name,
                message = "Command '${entry.descriptor.id}' was cancelled",
                retryable = false
            )
            outcome = RunOutcome.CANCELLED
        } catch (e: McosException) {
            result = CommandResult.Err(
                code = e.code,
                message = e.message,
                retryable = e.retryable,
                details = e.details
            )
            outcome = RunOutcome.FAILED
        } catch (e: Exception) {
            result = CommandResult.Err(
                code = McosErrorCode.PLUGIN_ERROR.name,
                message = "Plugin error in '${entry.descriptor.id}': ${sanitize(e)}",
                retryable = false
            )
            outcome = RunOutcome.FAILED
            // §15.3 — uncaught plugin exceptions count as crashes.
            recordPluginCrash(entry, e)
        }

        // A successful invocation resets the crash-loop window (§15.3).
        if (result is CommandResult.Ok) {
            security.quarantine.recordSuccess(entry.descriptor.pluginId)
        }

        // Stage 10 — Audit recording
        val durationMs = System.currentTimeMillis() - startTime
        security.auditLog.append(
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

    // ─── Secret resolution (§9.2) ───────────────────────────────────────

    /**
     * Per-plugin [HostServices] facade whose [NetService] resolves
     * `{{secret.<key>}}` templates (08-security.md §9.2) from the plugin's
     * scoped [SecureStore] before the request leaves the runtime. All other
     * services are delegated unchanged, and the resolved value is never
     * written back into ExecutionContext.args.
     */
    private fun secretResolvingServices(): HostServices {
        val original = hostServices
        return object : HostServices {
            override val files: FileService get() = original.files
            override val net: NetService get() = SecretResolvingNetService(original.net, original.secureStore)
            override val ui: UiService get() = original.ui
            override val secureStore: SecureStore get() = original.secureStore
            override val clock: Clock get() = original.clock
            override val json: JsonService get() = original.json
            override val memory: MemoryFacade get() = original.memory
            override val notifications: NotificationService? get() = original.notifications
            override val media: MediaService? get() = original.media
        }
    }

    /**
     * [NetService] decorator that resolves `{{secret.<key>}}` templates in
     * request headers and body before delegating. Templates whose key is not
     * present in the plugin-scoped [SecureStore] stay inert — a plugin can
     * only resolve secrets inside its own namespace.
     */
    private class SecretResolvingNetService(
        private val delegate: NetService,
        private val store: SecureStore,
    ) : NetService {
        override suspend fun request(
            method: String,
            url: String,
            body: String?,
            headers: Map<String, String>,
        ): NetResponse {
            val resolvedHeaders = headers.mapValues { (_, v) -> SecretResolver.resolve(v) { store.get(it) } }
            val resolvedBody = body?.let { SecretResolver.resolve(it) { store.get(it) } }
            return delegate.request(method, url, resolvedBody, resolvedHeaders)
        }
    }

    // ─── Crash-loop quarantine (§15.3) ──────────────────────────────────

    /**
     * Crash-loop quarantine (08-security.md §15.3): when a plugin crashes
     * >= threshold times within the sliding window, its commands are removed
     * from the registry and an audit event `plugin.quarantined` is emitted.
     */
    private fun recordPluginCrash(entry: RegistryEntry, e: Exception) {
        val pluginId = entry.descriptor.pluginId
        if (security.quarantine.recordCrash(pluginId, e.stackTraceToString())) {
            registry.unregister(pluginId)
            security.auditLog.append(
                RunRecord(
                    runId = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    source = "SECURITY",
                    commandId = entry.descriptor.id,
                    steps = listOf(
                        StepRecord(
                            commandId = entry.descriptor.id,
                            pluginId = pluginId,
                            ok = false,
                            code = "plugin.quarantined",
                            message = security.quarantine.quarantineReason(pluginId)?.take(500),
                            durationMs = 0,
                        )
                    ),
                    totalDurationMs = 0,
                    outcome = RunOutcome.FAILED,
                )
            )
        }
    }

    /**
     * Recursively collect string values from [element] that look like URLs
     * (start with an ASCII `http://` or `https://` scheme). Used by the
     * Stage 6.5 egress check to cover URLs nested anywhere in the argument
     * tree.
     *
     * IDN/Unicode hardening (P0-S3): the scheme prefix is matched against the
     * ASCII scheme only; visually-similar Unicode lookalikes (e.g. a full-width
     * `ｈｔｔｐｓ://`) are NOT treated as URLs and therefore never reach the egress
     * policy — they simply won't be recognised as outbound targets, which is
     * the safe failure mode for a free-text field. The actual host→scope
     * matching happens in [NetworkEgressPolicy], which normalises hosts via
     * `IDN.toASCII` so a Punycode/Unicode host is compared against the granted
     * scope in canonical form.
     */
    private fun collectUrls(element: JsonElement, out: MutableList<String> = mutableListOf()): List<String> {
        when (element) {
            is JsonObject -> element.values.forEach { collectUrls(it, out) }
            is JsonArray -> element.forEach { collectUrls(it, out) }
            is JsonPrimitive -> {
                if (element.isString) {
                    val s = element.content
                    // Lowercase the ASCII scheme prefix only — `http`/`https`
                    // must be ASCII; a Unicode lookalike must NOT match. We
                    // compare the lowercased first 8 chars to the schemes.
                    val prefix = s.take(8).lowercase()
                    if (prefix.startsWith("http://") || prefix.startsWith("https://")) {
                        out.add(s)
                    }
                }
            }
            else -> {}
        }
        return out
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
