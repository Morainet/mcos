package com.morainet.mcos.llm.golden

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * NL→IR golden suite regression gate (06-agent §16.2): every change to the
 * system prompt, provider adapters or the compiler must keep the whole
 * `docs/fixtures/planner/` fixture set compiling to its expected structure.
 */
class NlIrGoldenSuiteTest {

    @Test
    fun `golden fixtures are well-formed`() {
        val fixtures = NlIrEvaluation.loadAll()
        assertTrue(fixtures.isNotEmpty(), "no fixtures found under docs/fixtures/planner")
        val ids = fixtures.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "fixture ids must be unique")
        fixtures.forEach { f ->
            assertTrue(
                f.id.matches(Regex("golden-\\d{3}.*")),
                "fixture id '${f.id}' must start with 'golden-<3 digits>'",
            )
            assertTrue(
                f.expectedType in GoldenFixture.VALID_TYPES,
                "fixture '${f.id}' has unknown expectedType '${f.expectedType}'",
            )
            assertTrue(
                f.mode in GoldenFixture.VALID_MODES,
                "fixture '${f.id}' has unknown mode '${f.mode}'",
            )
            assertTrue(f.llmReply.isNotBlank(), "fixture '${f.id}' has an empty llmReply")
            if (!f.isClarifyOrRefuse) {
                assertTrue(
                    f.expectedIr.commands.isNotEmpty(),
                    "fixture '${f.id}' (${f.expectedType}) declares no expectedIr.commands",
                )
            }
        }
    }

    @Test
    fun `golden suite compiles with full accuracy and no mis-execution`() = runBlocking {
        val fixtures = NlIrEvaluation.loadAll()
        assertTrue(fixtures.isNotEmpty(), "no fixtures found under docs/fixtures/planner")
        val results = fixtures.map { NlIrEvaluation.evaluate(it) }
        val metrics = NlIrEvaluation.aggregate(results)

        assertEquals(fixtures.size, metrics.total, "suite must cover every fixture")
        val mismatches = results
            .filterNot { it.structureOk }
            .joinToString(separator = "; ") { "${it.fixture.id}: ${it.structureDetail}" }
        assertEquals(1.0, metrics.structureAccuracy, "every fixture must match its expected structure. Failures: $mismatches")
        assertEquals(0.0, metrics.misRefusalRate, "no fixture may refuse unexpectedly. Failures: " +
            results.filter { it.isMisRefusal }.joinToString { it.fixture.id })
        assertEquals(0.0, metrics.misExecutionRate, "no fixture may execute when a refusal is expected. Failures: " +
            results.filter { it.isMisExecution }.joinToString { it.fixture.id })
        assertEquals(1.0, metrics.clarifyAccuracy, "clarify must be emitted exactly when expected")
        assertTrue(metrics.avgLatencyMs >= 0.0, "mean latency must be non-negative")
    }

    @Test
    fun `every fixture compiles to its expected structure`() = runBlocking {
        val fixtures = NlIrEvaluation.loadAll()
        assertTrue(fixtures.isNotEmpty(), "no fixtures found under docs/fixtures/planner")
        fixtures.forEach { f ->
            val result = NlIrEvaluation.evaluate(f)
            val error = result.plan.error
            assertTrue(
                result.structureOk,
                "fixture '${f.id}' (${f.expectedType}): ${result.structureDetail}",
            )
            assertTrue(
                result.compiledOk,
                "fixture '${f.id}' failed to compile: ${error?.code} ${error?.message}",
            )
        }
    }
}
