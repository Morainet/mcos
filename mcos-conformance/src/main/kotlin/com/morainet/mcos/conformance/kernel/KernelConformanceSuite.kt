package com.morainet.mcos.conformance.kernel

import com.morainet.mcos.conformance.api.ConformanceCase
import com.morainet.mcos.conformance.api.ConformanceSuite
import com.morainet.mcos.runtime.core.error.McosErrorCode
import com.morainet.mcos.runtime.core.executor.Executor
import com.morainet.mcos.runtime.core.ir.ParseResult
import com.morainet.mcos.runtime.core.parse.DslParser
import com.morainet.mcos.runtime.core.registry.CommandRegistry
import com.morainet.mcos.security.SecurityConfig
import com.morainet.mcos.sdk.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Kernel 1.0 stabilization-gate conformance (10 §17).
 *
 * The gate freezes three contracts — Command Protocol, Runtime Semantics,
 * Plugin Contract — and §17.1 demands executable guarantees this suite pins:
 *
 *  1. **The error-code table has no unassigned or ambiguous codes, and every
 *     code has at least one test asserting it** — the enum must equal the set
 *     the specs name (02 §10.3 + 01 §15.1), the retryable set must equal the
 *     documented one, and every constant must be registered in
 *     [PINNED_BY_TEST] against the test that pins it (or be an explicitly
 *     reserved constant with its rationale in the enum KDoc). Adding a
 *     constant without registering it fails this suite — the executable form
 *     of 02 §14's "no silent evolution path" applied to the error vocabulary.
 *  2. **Protocol versioning is the mechanism for change** — a future
 *     `dslVersion` header must be rejected, never silently accepted
 *     (02 §14; golden fixture S4).
 *  3. **The Executor's stage order (03 §9, Runtime Semantics)** — the
 *     registry precedes schema validation, which precedes handler dispatch;
 *     a reordered stage is a spec revision, never an optimization, and the
 *     cases below fail the moment the order changes.
 */
class KernelConformanceSuite : ConformanceSuite {
    override val id = "kernel"
    override val title = "Kernel 1.0 stabilization-gate surfaces"
    override val spec = "10 §17.1 + 02 §10.3/§14 + 01 §15.1 + 03 §9"

    override fun cases(): List<ConformanceCase> = listOf(
        errorCodeVocabularyCase(),
        errorCodeRetryableCase(),
        errorCodeTestCoverageCase(),
        futureDslVersionRejectedCase(),
        stageRegistryBeforeSchemaCase(),
        stageSchemaBeforeHandlerCase(),
        stageValidCallRunsOnceCase(),
    )

    // ─── Error-code vocabulary (02 §10.3 + 01 §15.1) ─────────────────────

    private fun errorCodeVocabularyCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-error-code-vocabulary"
        override val title = "McosErrorCode equals the spec-pinned 18-code vocabulary"
        override val spec = "02 §10.3 + 01 §15.1"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result {
            val actual = McosErrorCode.entries.map { it.name }.toSet()
            val missing = SPEC_CODES - actual
            val extra = actual - SPEC_CODES
            return when {
                missing.isNotEmpty() -> ConformanceCase.Result.Fail(
                    message = "codes named by the spec are missing from the enum: $missing",
                )
                extra.isNotEmpty() -> ConformanceCase.Result.Fail(
                    message = "enum constants the spec does not name — " +
                        "add them to 01 §15.1 or remove them: $extra",
                )
                else -> ConformanceCase.Result.Pass
            }
        }
    }

    private fun errorCodeRetryableCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-error-code-retryable"
        override val title = "retryable flags match the documented set"
        override val spec = "02 §10.3 + McosErrorCode KDoc"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result {
            val actual = McosErrorCode.entries.filter { it.retryable }.map { it.name }.toSet()
            return if (actual == RETRYABLE_CODES) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "retryable set drifted: actual=$actual expected=$RETRYABLE_CODES — " +
                        "a retryable flag is protocol semantics; changing it is a spec revision",
                )
            }
        }
    }

    private fun errorCodeTestCoverageCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-error-code-test-coverage"
        override val title = "every error code is pinned by a test or explicitly reserved"
        override val spec = "10 §17.1"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result {
            val unregistered = McosErrorCode.entries.filter { it !in PINNED_BY_TEST }
            return if (unregistered.isEmpty()) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "error codes with no pinning test and no reserved-note registration: " +
                        unregistered.map { it.name } +
                        " — write the test, then register it in KernelConformanceSuite.PINNED_BY_TEST",
                )
            }
        }
    }

    // ─── Protocol versioning (02 §14) ────────────────────────────────────

    private fun futureDslVersionRejectedCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-future-dsl-version-rejected"
        override val title = "a future dslVersion header is rejected, never silently accepted"
        override val spec = "02 §14 + §16 S4"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result {
            val parsed = DslParser.parse("# mcos-dsl: 0.2\nhello.world()")
            val err = parsed as? ParseResult.Err
                ?: return ConformanceCase.Result.Fail(
                    message = "a future dslVersion must be rejected, got $parsed",
                )
            return if (err.code == "PARSE_ERROR") {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = "version rejection must surface PARSE_ERROR, got '${err.code}'",
                )
            }
        }
    }

    // ─── Runtime Semantics: executor stage order (03 §9) ─────────────────

    private fun stageRegistryBeforeSchemaCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-stage-registry-before-schema"
        override val title = "an unknown command resolves UNKNOWN_COMMAND, not a downstream code"
        override val spec = "03 §9 (registry precedes schema validation)"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result = runBlocking {
            val (executor, invocations) = semanticsExecutor()
            val r = executor.execute("no.such.command", buildJsonObject { })
            return@runBlocking when {
                r !is CommandResult.Err -> ConformanceCase.Result.Fail(
                    message = "expected Err for an unknown command, got $r",
                )
                r.code != McosErrorCode.UNKNOWN_COMMAND.name -> ConformanceCase.Result.Fail(
                    message = "the registry stage must resolve first: expected " +
                        "UNKNOWN_COMMAND, got '${r.code}' — stage order broke",
                )
                invocations.get() != 0 -> ConformanceCase.Result.Fail(
                    message = "a handler ran ${invocations.get()}× for an unknown command",
                )
                else -> ConformanceCase.Result.Pass
            }
        }
    }

    private fun stageSchemaBeforeHandlerCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-stage-schema-before-handler"
        override val title = "schema violation fires before the handler (zero invocations)"
        override val spec = "03 §9 (schema validation precedes dispatch)"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result = runBlocking {
            val (executor, invocations) = semanticsExecutor()
            // Missing the required `id` arg entirely.
            val r = executor.execute("semantics.check", buildJsonObject { })
            return@runBlocking when {
                r !is CommandResult.Err -> ConformanceCase.Result.Fail(
                    message = "expected Err for a schema-violating call, got $r",
                )
                r.code != McosErrorCode.SCHEMA_VIOLATION.name -> ConformanceCase.Result.Fail(
                    message = "expected SCHEMA_VIOLATION, got '${r.code}'",
                )
                invocations.get() != 0 -> ConformanceCase.Result.Fail(
                    message = "the handler ran ${invocations.get()}× on a schema-violating " +
                        "call — schema validation no longer precedes dispatch",
                )
                else -> ConformanceCase.Result.Pass
            }
        }
    }

    private fun stageValidCallRunsOnceCase(): ConformanceCase = object : ConformanceCase {
        override val id = "kernel-stage-valid-call-runs-once"
        override val title = "a schema-valid call dispatches exactly once and succeeds"
        override val spec = "03 §9 (dispatch)"
        override val category = "kernel"

        override fun run(): ConformanceCase.Result = runBlocking {
            val (executor, invocations) = semanticsExecutor()
            val r = executor.execute("semantics.check", buildJsonObject { put("id", JsonPrimitive("x")) })
            return@runBlocking when {
                r !is CommandResult.Ok -> ConformanceCase.Result.Fail(
                    message = "expected Ok for a schema-valid call, got $r",
                )
                invocations.get() != 1 -> ConformanceCase.Result.Fail(
                    message = "the handler must run exactly once, ran ${invocations.get()}×",
                )
                else -> ConformanceCase.Result.Pass
            }
        }
    }

    // ─── Fixtures ────────────────────────────────────────────────────────

    /**
     * Executor over a single `semantics.check` command whose handler counts
     * invocations — the observable that pins the stage order. A fresh
     * registry per call keeps the cases order-independent.
     */
    private fun semanticsExecutor(): Pair<Executor, AtomicInteger> {
        val invocations = AtomicInteger(0)
        val registry = CommandRegistry()
        registry.register(object : McosPlugin {
            override val manifest = PluginManifest(
                id = "semantics", name = "semantics", version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                description = "conformance fixture",
                provider = ProviderInfo("Conformance", "https://conformance.local"),
                entry = "com.morainet.mcos.conformance.SemanticsFixture",
                commands = listOf(
                    CommandManifestEntry(
                        id = "semantics.check", version = "1.0.0",
                        title = "check", description = "check",
                        sideEffectClass = SideEffectClass.read,
                        inputSchema = buildJsonObject {
                            put("type", "object")
                            put("required", buildJsonArray { add(JsonPrimitive("id")) })
                            putJsonObject("properties") {
                                putJsonObject("id") { put("type", "string") }
                            }
                        },
                    )
                )
            )
            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
            override fun handlers(): Map<String, CommandHandler> = mapOf(
                "semantics.check" to object : CommandHandler {
                    override suspend fun invoke(ctx: ExecutionContext): CommandResult {
                        invocations.incrementAndGet()
                        return CommandResult.Ok(JsonPrimitive("ok"))
                    }
                }
            )
        })
        return Executor(registry, InertHostServices(), SecurityConfig.permissive()) to invocations
    }

    /**
     * Minimal in-memory host: every required capability present but inert —
     * these cases drive the EXECUTOR's stage order, not any host service.
     */
    private class InertHostServices : HostServices {
        override val files: FileService = object : FileService {
            override suspend fun list(uri: String, mimeType: String?): List<FileEntry> = inert()
        }
        override val net: NetService = object : NetService {
            override suspend fun request(req: HttpRequest): HttpResponse = inert()
        }
        override val ui: UiService = object : UiService {
            override suspend fun startActivityForResult(intent: Map<String, String>): Map<String, String>? = inert()
        }
        override val secureStore: SecureStore = object : SecureStore {
            override suspend fun get(key: String): ByteArray? = null
            override suspend fun put(key: String, value: ByteArray) {}
            override suspend fun remove(key: String) {}
            override suspend fun keys(): Set<String> = emptySet()
        }
        override val clock: Clock = object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(0)
            override fun monotonicMs(): Long = 0L
        }
        override val json: JsonService = object : JsonService {
            override fun parse(jsonStr: String): JsonElement = Json.parseToJsonElement(jsonStr)
        }
        override val memory: MemoryFacade = object : MemoryFacade {
            override suspend fun get(path: String): JsonElement? = null
            override suspend fun resolveRef(ref: String, semanticType: String?): ResolveResult =
                ResolveResult.NotFound()
        }

        private fun inert(): Nothing = throw McosException(
            code = McosErrorCode.UNAVAILABLE.name,
            message = "inert conformance host — this case never touches host services",
        )
    }

    companion object {
        /** The 18 codes the specs name: 02 §10.3 plus the 01 §15.1 addendum. */
        val SPEC_CODES: Set<String> = setOf(
            // 02 §10.3 — command-level subset
            "PARSE_ERROR", "UNKNOWN_COMMAND", "SCHEMA_VIOLATION", "PERMISSION_DENIED",
            "CONFIRMATION_REQUIRED", "TIMEOUT", "CANCELLED", "PLUGIN_ERROR",
            "UNAVAILABLE", "RATE_LIMITED", "INTERNAL", "CONFLICT",
            // 01 §15.1 — compile + workflow addendum
            "COMPILE_FAILED", "WORKFLOW_INVALID", "MAX_ITERATIONS_EXCEEDED",
            "COMPENSATION_FAILED", "JOIN_FAILED", "TRIGGER_MISFIRE",
        )

        /** The documented retryable set — every other code is non-retryable. */
        val RETRYABLE_CODES: Set<String> = setOf(
            "TIMEOUT", "UNAVAILABLE", "RATE_LIMITED", "TRIGGER_MISFIRE",
        )

        /**
         * Executable form of 10 §17.1 "every code has at least one test
         * asserting it". Reserved constants (no production path) register
         * their enum-KDoc rationale instead of a test.
         */
        val PINNED_BY_TEST: Map<McosErrorCode, String> = mapOf(
            McosErrorCode.PARSE_ERROR to "DslParserTest + golden fixtures (02 §16)",
            McosErrorCode.COMPILE_FAILED to "AgentLoopTest A22",
            McosErrorCode.UNKNOWN_COMMAND to "WorkflowEngineTest W2",
            McosErrorCode.SCHEMA_VIOLATION to "WorkflowInputTest",
            McosErrorCode.PERMISSION_DENIED to "IsolatedHostServicesProxyTest",
            McosErrorCode.CONFIRMATION_REQUIRED to "ConfirmationCoordinatorTest",
            McosErrorCode.TIMEOUT to "ExecutorRuntimeConfigTest",
            McosErrorCode.CANCELLED to "WorkflowEngineTest (cancellation)",
            McosErrorCode.PLUGIN_ERROR to "IsolatedPluginRunnerTest",
            McosErrorCode.UNAVAILABLE to "IsolatedHostServicesProxyTest",
            McosErrorCode.RATE_LIMITED to "RunSchedulerTest",
            McosErrorCode.CONFLICT to "DeviceMutexMapTest DM4/DM8",
            McosErrorCode.INTERNAL to "AuditFailClosedWiringTest",
            McosErrorCode.WORKFLOW_INVALID to "WorkflowEngineTest W30",
            McosErrorCode.MAX_ITERATIONS_EXCEEDED to "WorkflowEngineTest (loop cap)",
            McosErrorCode.COMPENSATION_FAILED to "WorkflowEngineTest W31",
            McosErrorCode.JOIN_FAILED to "RESERVED — enum KDoc (no production path)",
            McosErrorCode.TRIGGER_MISFIRE to "RESERVED — enum KDoc (no production path)",
        )
    }
}
