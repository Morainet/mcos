package com.morainet.mcos.runtime.core.executor

import com.morainet.mcos.security.audit.ArtifactRecord
import com.morainet.mcos.security.audit.RunOutcome
import com.morainet.mcos.security.audit.RunRecord
import com.morainet.mcos.security.audit.StepRecord
import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.security.permission.AuthorizationResult
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.runtime.core.registry.RegistryEntry
import com.morainet.mcos.runtime.core.registry.ResolveResult
import com.morainet.mcos.runtime.core.scheduler.InvocationLimiter
import com.morainet.mcos.security.EgressDecision
import com.morainet.mcos.security.RateLimitResult
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.security.SecretResolver
import com.morainet.mcos.security.TrustLevel
import com.morainet.mcos.security.validate.SchemaValidator
import com.morainet.mcos.security.validate.ValidationError as ValError
import com.morainet.mcos.security.validate.ValidationResult
import com.morainet.mcos.sdk.*
import java.util.concurrent.ConcurrentHashMap
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
 * Pipeline: Stage 3 (Resolve) → Stage 5 (Validate) → Stage 5.5 (Rate Limit) → Stage 6 (Authorize) → Stage 6.5 (Egress, post-auth) → §8.2 invocation caps (when [invocationLimiter] is wired) → Stage 8 (Invoke) → Stage 10 (Audit)
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
 * stamp. Only the *named* [com.morainet.mcos.security.TrustingAuthStampSigner]
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
 * @param isolationHost Optional host seam ([08-security.md §8.1]) for running
 *        non-`BUILTIN` plugins in a separate process. When null, non-builtin
 *        commands run best-effort in-process (the MVP posture) and each such
 *        plugin's first fallback is audited as `plugin.isolation_fallback`.
 *        `BUILTIN` plugins always run in-process regardless. Defaults to null.
 * @param invocationLimiter Optional §8.2 invocation caps (max per-plugin /
 *        max global `destructive`), acquired at Stage-8 pre-dispatch — after
 *        authorize/egress so a policy-rejected command never holds a slot, and
 *        outside the handler `withTimeout` so waiting for a slot does not burn
 *        command budget. Null (the default) disables the caps.
 */
class Executor(
    private val registry: CommandRegistry,
    private val hostServices: HostServices,
    private val security: SecurityConfig,
    private val globalKillSwitch: () -> Boolean = { false },
    private val debugMode: Boolean = false,
    private val isolationHost: IsolationHost? = null,
    private val invocationLimiter: InvocationLimiter? = null,
) {

    private val schemaValidator = SchemaValidator()

    /**
     * Plugin ids whose best-effort in-process fallback ([08-security.md §8.1])
     * has already been audited, so the `plugin.isolation_fallback` record is
     * emitted once per plugin per Executor rather than on every invocation.
     */
    private val isolationFallbackAudited = ConcurrentHashMap.newKeySet<String>()

    /**
     * Execute a single command by ID.
     *
     * @param commandId Fully-qualified command ID, e.g. "camera.capture".
     * @param args JSON arguments for the command handler.
     * @param auth Optional authorization stamp.
     * @param progress Optional progress emitter for streaming updates.
     * @param source Audit source label for the Stage-10 record (08 §14):
     *   the `ExecuteRequest.Source` name (CLI/CHAT/…) or `AGENT_PROBE` for
     *   read-only Agent probes (06 §11.3). Defaults to "CLI" for backward
     *   compatibility with existing callers.
     * @return [CommandResult.Ok] on success, [CommandResult.Err] on any failure.
     */
    suspend fun execute(
        commandId: String,
        args: JsonObject = JsonObject(emptyMap()),
        auth: AuthStamp? = null,
        progress: ProgressEmitter? = null,
        source: String = "CLI"
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
        return invokeHandler(resolved.entry, args, auth, progress, source)
    }

    /**
     * Execute a batch of commands sequentially.
     * Stops at the first failure — subsequent steps are not executed.
     *
     * @param steps Ordered list of invocations.
     * @param auth Optional authorization stamp shared across all steps.
     * @param progress Optional progress emitter.
     * @param source Audit source label (see [execute]); defaults to "CLI".
     * @return List of results. If any step fails, the list ends with the error result.
     */
    suspend fun executeSequence(
        steps: List<Command>,
        auth: AuthStamp? = null,
        progress: ProgressEmitter? = null,
        source: String = "CLI"
    ): List<CommandResult> {
        val results = mutableListOf<CommandResult>()
        for (cmd in steps) {
            val result = execute(cmd.id, cmd.args, auth, progress, source)
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
        progress: ProgressEmitter?,
        source: String = "CLI"
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
            when (val authz = security.kernel.authorize(entry.descriptor, enterprise, source)) {
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
        val deadlineMs = startTime + timeoutMs
        // Re-sign the stamp after the audit runId is bound so the isolated
        // facade (and the in-process facades) verify a run-scoped stamp.
        val boundAuth = effectiveAuth?.let { stamp -> security.signer.sign(stamp.copy(runId = runId)) }

        // Stage 8 dispatch — isolation strategy by trust level (08 §7.2/§8).
        // BUILTIN runs in-process; every other level targets a separate
        // process. Without an [isolationHost] wired we degrade to a
        // best-effort in-process invocation (the MVP posture) and audit it.
        val isolationMode = IsolationPolicy.modeFor(entry.trustLevel)

        // The in-process path builds the full ExecutionContext (with the
        // per-plugin secret-resolving, sandbox-namespaced facade). The
        // isolated path deliberately hands only identity + args + stamp across
        // the boundary — the plugin process gets a Binder-stub facade instead.
        suspend fun invokeInProcess(): CommandResult {
            val ctx = ExecutionContext(
                runId = runId,
                commandId = entry.descriptor.id,
                args = args,
                auth = boundAuth,
                deadline = deadlineMs,
                progress = progress,
                // Stage 4 (Expand) — `{{secret.*}}` templates (08-security.md §9.2)
                // are resolved by the per-plugin NetService decorator; args keep
                // the template form and values never enter the audit trail.
                // The sandbox is namespaced per executing plugin (04 §6.1), and
                // non-BUILTIN plugins additionally pass the AuthStamp scope
                // gate on facade network calls (08 §8.2, confused-deputy
                // defense) — see [stage4Services].
                services = stage4Services(entry, boundAuth),
            )
            return entry.handler.invoke(ctx)
        }

        var result: CommandResult
        var outcome: RunOutcome

        // Stage 8 dispatch, hoisted so the §8.2 invocation caps can wrap it:
        // the limiter's slot wait happens BEFORE the handler `withTimeout`,
        // so waiting for a per-plugin/destructive slot never burns command
        // budget (03-runtime.md §8.2).
        suspend fun dispatch(): CommandResult = withTimeout(timeoutMs) {
            when {
                isolationMode == IsolationMode.IN_PROCESS -> invokeInProcess()
                isolationHost != null -> isolationHost.invoke(
                    IsolatedInvocation(
                        pluginId = entry.descriptor.pluginId,
                        pluginVersion = entry.pluginVersion,
                        commandId = entry.descriptor.id,
                        args = args,
                        auth = boundAuth,
                        runId = runId,
                        deadlineMs = deadlineMs,
                        source = source,
                    )
                )
                else -> {
                    // No isolation host: best-effort in-process fallback
                    // (08 §8.1). Audited once per plugin so the weaker
                    // boundary is visible without flooding the trail.
                    recordIsolationFallback(entry, runId)
                    invokeInProcess()
                }
            }
        }

        try {
            result = when (val limiter = invocationLimiter) {
                null -> dispatch()
                else -> limiter.withPermits(entry.descriptor.pluginId, entry.descriptor.sideEffectClass) { dispatch() }
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
                source = source,
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
                            ArtifactRecord(it.type, it.uri, it.mimeType)
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
     * Per-plugin Stage-4 [HostServices] facade. Three decorations, innermost
     * first:
     *
     * 1. **Secret resolution** — the [NetService] resolves `{{secret.<key>}}`
     *    templates (08-security.md §9.2) from the plugin's scoped
     *    [SecureStore] before the request leaves the runtime; the resolved
     *    value is never written back into ExecutionContext.args.
     * 2. **Sandbox namespacing** — [SandboxFileService] is rooted at the
     *    executing plugin's namespace (04-plugin-sdk.md 6.1 — one plugin
     *    cannot address another's files).
     * 3. **AuthStamp scope gate** (non-`BUILTIN` only) — [StampScopedNetService]
     *    sits outermost and verifies the run's stamp before any facade network
     *    call: a handler-internal request is judged against
     *    `stamp.grantsUsed`'s `network.<domain>` scopes, closing the
     *    confused-deputy hole Stage 6.5's argument-tree walk cannot see
     *    (08-security.md §8.2). `BUILTIN` plugins are platform code (§7.2)
     *    and are not gated.
     *
     * All other services are delegated unchanged.
     */
    private fun stage4Services(entry: RegistryEntry, auth: AuthStamp?): HostServices {
        val original = hostServices
        val pluginId = entry.descriptor.pluginId
        return object : HostServices {
            // Built lazily per access — a host whose services are stubs that
            // throw on property access (StubHostServices) must only fail when
            // a handler actually touches the facade member.
            override val net: NetService
                get() {
                    val resolving = SecretResolvingNetService(original.net, original.secureStore)
                    return if (entry.trustLevel == TrustLevel.BUILTIN) {
                        resolving
                    } else {
                        StampScopedNetService(resolving, auth, security.signer)
                    }
                }
            override val files: FileService get() = original.files
            override val ui: UiService get() = original.ui
            override val secureStore: SecureStore get() = original.secureStore
            override val clock: Clock get() = original.clock
            override val json: JsonService get() = original.json
            override val memory: MemoryFacade get() = original.memory
            override val notifications: NotificationService? get() = original.notifications
            override val media: MediaService? get() = original.media
            // Optional capabilities must be delegated too — the interface
            // defaults are null, so a missing override here would silently
            // strip deviceInfo/clipboard/haptics from every executed command.
            override val deviceInfo: DeviceInfoService? get() = original.deviceInfo
            override val clipboard: ClipboardService? get() = original.clipboard
            override val haptics: HapticsService? get() = original.haptics
            override val events: EventPublisher? get() = original.events
            // The sandbox view is per-plugin: every path the handler uses is
            // resolved inside the plugin's own namespace directory.
            override val sandbox: SandboxFileService? =
                original.sandbox?.let { NamespacedSandbox(it, pluginId) }
        }
    }

    // ─── Process isolation (§8) ─────────────────────────────────────────

    /**
     * Record that a non-`BUILTIN` plugin ran best-effort in-process because no
     * [IsolationHost] is wired ([08-security.md §8.1] / §7.2 MVP posture).
     * Emitted once per plugin per Executor (deduped) as an informational
     * `plugin.isolation_fallback` audit step, mirroring the `plugin.quarantined`
     * security-event record shape.
     */
    private fun recordIsolationFallback(entry: RegistryEntry, runId: String) {
        val pluginId = entry.descriptor.pluginId
        if (!isolationFallbackAudited.add(pluginId)) return
        security.auditLog.append(
            RunRecord(
                runId = runId,
                timestamp = System.currentTimeMillis(),
                source = "SECURITY",
                commandId = entry.descriptor.id,
                steps = listOf(
                    StepRecord(
                        commandId = entry.descriptor.id,
                        pluginId = pluginId,
                        ok = true,
                        code = "plugin.isolation_fallback",
                        message = "trustLevel=${entry.trustLevel.name} ran in-process; no isolation host wired",
                        durationMs = 0,
                    )
                ),
                totalDurationMs = 0,
                outcome = RunOutcome.OK,
            )
        )
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
