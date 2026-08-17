package com.mcos.runtime.llm.golden

import com.mcos.runtime.llm.LlmPlan
import com.mcos.runtime.llm.LlmPlanner
import com.mcos.runtime.memory.MemoryStore
import com.mcos.runtime.registry.CommandRegistry
import com.mcos.sdk.CommandHandler
import com.mcos.sdk.CommandManifestEntry
import com.mcos.sdk.HostServices
import com.mcos.sdk.McosPlugin
import com.mcos.sdk.PluginManifest
import com.mcos.sdk.ProviderInfo
import com.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * NL→IR golden evaluation (06-agent §16): loads the fixture set from
 * `docs/fixtures/planner/`, runs each [GoldenFixture] through the real
 * [LlmPlanner] pipeline with a deterministic stub provider, and reports
 * structural match plus suite metrics (§16.1): compile accuracy,
 * mis-refusal rate, mis-execution rate, clarify quality and mean latency.
 *
 * The same [evaluate] harness is the foundation for a live-provider
 * benchmark: replace [GoldLlmProvider] with a real backend and compare the
 * produced [LlmPlan] against the fixture's [GoldenFixture.expectedIr] with
 * [structureMatches].
 */
object NlIrEvaluation {

    // ─── Fixture discovery ────────────────────────────────────────────────

    /**
     * Locate the `docs/fixtures/planner` directory. Gradle JVM tests run with
     * the module directory as the working dir (`mcos-runtime/`), while IDE
     * runs may start from the repo root, so we walk up from `user.dir` until
     * the directory is found (bounded).
     */
    fun fixtureDir(): File {
        val segments = listOf("docs", "fixtures", "planner")
        var cursor: File? = File(System.getProperty("user.dir") ?: ".")
        var depth = 0
        while (cursor != null && depth <= 5) {
            val candidate = segments.fold(cursor) { acc, seg -> File(acc, seg) }
            if (candidate.isDirectory) return candidate
            cursor = cursor.parentFile
            depth++
        }
        return File(segments.joinToString(File.separator))
    }

    /** Load and decode every `*.json` fixture, ordered by file name. */
    fun loadAll(dir: File = fixtureDir()): List<GoldenFixture> {
        val json = Json { ignoreUnknownKeys = true }
        return dir.listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name }
            ?.map { f -> json.decodeFromString<GoldenFixture>(f.readText()) }
            ?: emptyList()
    }

    // ─── Evaluation ───────────────────────────────────────────────────────

    /**
     * Run one fixture through the planner pipeline: build registry + memory
     * from the fixture, plan with a stub provider that answers the fixture's
     * [GoldenFixture.llmReply], then compare the produced plan against the
     * expected structure.
     */
    suspend fun evaluate(fixture: GoldenFixture): EvalResult {
        val registry = buildRegistry(fixture.registryFixture)
        val memory = MemoryStore()
        fixture.memoryFixture.forEach { (path, value) ->
            memory.put(path, JsonPrimitive(value))
        }
        val provider = GoldLlmProvider(fixture.llmReply, fixture.mode)
        val planner = LlmPlanner(provider, registry, memory)

        val start = System.nanoTime()
        val plan = planner.plan(fixture.utterance)
        val latencyMs = (System.nanoTime() - start) / 1_000_000

        val (ok, detail) = structureMatches(fixture, plan)
        return EvalResult(fixture, plan, ok, detail, latencyMs)
    }

    /**
     * Structural comparison per 06-agent §16.0: assert command ids, step
     * order and arg keys; `$ref:...` bound args must resolve to a non-empty
     * string but the exact value is not asserted. Clarify/refuse plans are
     * matched on their thoughts markers and on having no commands/errors.
     */
    fun structureMatches(fixture: GoldenFixture, plan: LlmPlan): Pair<Boolean, String> {
        return when (fixture.expectedType) {
            GoldenFixture.TYPE_CLARIFY -> when {
                plan.error != null ->
                    false to "expected clarify but compile failed: ${plan.error.code} ${plan.error.message}"
                plan.commands.isNotEmpty() ->
                    false to "expected clarify but compiled ${plan.commands.size} command(s)"
                plan.thoughts?.startsWith("IR clarify:") != true ->
                    false to "expected 'IR clarify:' thoughts marker, got: ${plan.thoughts}"
                else -> true to "clarify emitted"
            }

            GoldenFixture.TYPE_REFUSE -> when {
                plan.error != null ->
                    false to "expected refuse but compile failed: ${plan.error.code} ${plan.error.message}"
                plan.commands.isNotEmpty() ->
                    false to "expected refuse but compiled ${plan.commands.size} command(s)"
                plan.thoughts?.startsWith("IR refuse:") != true ->
                    false to "expected 'IR refuse:' thoughts marker, got: ${plan.thoughts}"
                else -> true to "refuse emitted"
            }

            else -> { // invoke | sequence
                if (plan.error != null) {
                    return false to "compile error: ${plan.error.code} ${plan.error.message}"
                }
                val expected = fixture.expectedIr.commands
                if (expected.isEmpty()) {
                    return false to "fixture declares no expectedIr.commands"
                }
                if (plan.commands.isEmpty()) {
                    return false to "expected ${fixture.expectedType} with ${expected.size} command(s), got an empty plan"
                }
                if (plan.commands.size != expected.size) {
                    return false to "expected ${expected.size} command(s), got ${plan.commands.size}: " +
                        plan.commands.joinToString { it.id }
                }
                expected.forEachIndexed { i, ec ->
                    val actual = plan.commands[i]
                    if (actual.id != ec.id) {
                        return false to "command[$i] id mismatch: expected '${ec.id}', got '${actual.id}'"
                    }
                    ec.args.forEach { (key, expectedVal) ->
                        if (!actual.args.containsKey(key)) {
                            return false to "command '${ec.id}' missing arg '$key'"
                        }
                        if (expectedVal is JsonPrimitive && expectedVal.isString) {
                            val marker = expectedVal.content
                            if (marker.isNotEmpty() && !marker.startsWith(GoldenFixture.REF_MARKER)) {
                                val actualVal = actual.args[key]
                                if (actualVal !is JsonPrimitive || !actualVal.isString || actualVal.content.isEmpty()) {
                                    return false to "command '${ec.id}' arg '$key' must be a non-empty string, " +
                                        "got: ${actualVal ?: "<missing>"}"
                                }
                            }
                        }
                    }
                }
                true to "structure matches (${plan.commands.size} command(s))"
            }
        }
    }

    // ─── Metrics (06-agent §16.1) ─────────────────────────────────────────

    /** Result of evaluating one fixture. */
    data class EvalResult(
        val fixture: GoldenFixture,
        val plan: LlmPlan,
        val structureOk: Boolean,
        val structureDetail: String,
        val latencyMs: Long,
    ) {
        /** Compile accuracy per fixture: the planner produced a valid plan. */
        val compiledOk: Boolean get() = plan.error == null

        /** Mis-refusal: the planner refused a request that should have compiled. */
        val isMisRefusal: Boolean
            get() = plan.thoughts?.startsWith("IR refuse:") == true &&
                fixture.expectedType != GoldenFixture.TYPE_REFUSE

        /** Mis-execution: the planner executed commands when a refusal was expected. */
        val isMisExecution: Boolean
            get() = plan.commands.isNotEmpty() && fixture.expectedType == GoldenFixture.TYPE_REFUSE

        /** Clarify quality: a clarify was emitted exactly when expected. */
        val clarifyCorrect: Boolean
            get() = (plan.thoughts?.startsWith("IR clarify:") == true) ==
                (fixture.expectedType == GoldenFixture.TYPE_CLARIFY)
    }

    /** Suite-level metrics, per 06-agent §16.1. */
    data class SuiteMetrics(
        val total: Int,
        val compiledOk: Int,
        val structureMatched: Int,
        val misRefusals: Int,
        val misExecutions: Int,
        val clarifyCorrect: Int,
        val avgLatencyMs: Double,
    ) {
        val compiledAccuracy: Double get() = if (total == 0) 0.0 else compiledOk.toDouble() / total
        val structureAccuracy: Double get() = if (total == 0) 0.0 else structureMatched.toDouble() / total
        val misRefusalRate: Double get() = if (total == 0) 0.0 else misRefusals.toDouble() / total
        val misExecutionRate: Double get() = if (total == 0) 0.0 else misExecutions.toDouble() / total
        val clarifyAccuracy: Double get() = if (total == 0) 0.0 else clarifyCorrect.toDouble() / total
    }

    fun aggregate(results: List<EvalResult>): SuiteMetrics {
        val total = results.size
        return SuiteMetrics(
            total = total,
            compiledOk = results.count { it.compiledOk },
            structureMatched = results.count { it.structureOk },
            misRefusals = results.count { it.isMisRefusal },
            misExecutions = results.count { it.isMisExecution },
            clarifyCorrect = results.count { it.clarifyCorrect },
            avgLatencyMs = if (total == 0) 0.0 else results.map { it.latencyMs }.average(),
        )
    }

    // ─── Fixture setup helpers ────────────────────────────────────────────

    /**
     * Register every fixture command as a READ command with a permissive
     * object schema. Only the manifest (id/title/description/schema) feeds
     * the planner's system prompt; handlers are never invoked by planning.
     */
    private fun buildRegistry(commandIds: List<String>): CommandRegistry {
        val registry = CommandRegistry()
        if (commandIds.isEmpty()) return registry
        val entries = commandIds.map { id ->
            CommandManifestEntry(
                id = id,
                version = "1.0.0",
                title = id,
                description = "Golden-fixture command for the NL-to-IR evaluation suite",
                sideEffectClass = SideEffectClass.read,
                inputSchema = buildJsonObject { put("type", JsonPrimitive("object")) },
            )
        }
        val plugin = object : McosPlugin {
            override val manifest = PluginManifest(
                id = "mcos.golden",
                name = "MCOS Golden Fixtures",
                version = "1.0.0",
                minRuntimeVersion = "1.0.0",
                description = "Commands used by the NL-to-IR golden evaluation suite",
                provider = ProviderInfo("MCOS", "https://example.invalid"),
                entry = "com.mcos.golden.GoldenPlugin",
                commands = entries,
            )

            override suspend fun onLoad(services: HostServices) {}
            override suspend fun onUnload() {}
            override fun handlers(): Map<String, CommandHandler> = emptyMap()
        }
        registry.register(plugin)
        return registry
    }
}
