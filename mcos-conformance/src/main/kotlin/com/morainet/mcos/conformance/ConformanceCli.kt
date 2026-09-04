package com.morainet.mcos.conformance

import com.morainet.mcos.conformance.api.ConformanceCase
import com.morainet.mcos.conformance.api.ConformanceReport
import com.morainet.mcos.conformance.api.ConformanceRunner
import com.morainet.mcos.conformance.api.ConformanceSuite
import com.morainet.mcos.conformance.dsl.DslConformanceSuite
import com.morainet.mcos.conformance.ir.IrConformanceSuite
import com.morainet.mcos.conformance.manifest.ManifestConformanceSuite
import com.morainet.mcos.conformance.reporters.HumanReporter
import com.morainet.mcos.conformance.reporters.JsonReporter
import com.morainet.mcos.conformance.reporters.JUnitReporter
import com.morainet.mcos.conformance.trust.TrustConformanceSuite
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.system.exitProcess

/**
 * CLI entry point for the MCOS conformance suite (spec 10 §6.4 + 09 §5.1).
 *
 * Subcommands:
 *  - `run`            — run every suite (or `--suite X`), emit a report;
 *  - `list`           — list every available suite + its case count;
 *  - `baseline-add`   — run all suites, capture the case set + outcomes
 *                       as a JSON baseline;
 *  - `baseline-check` — run all suites, fail if the outcome set drifted
 *                       from the captured baseline.
 *
 * Exit codes:
 *  - 0 — pass;
 *  - 1 — at least one case failed;
 *  - 2 — baseline mismatch (only `baseline-check`);
 *  - 3 — configuration / IO error.
 *
 * See `mcos-conformance/README.md` for the canonical usage examples.
 */
object ConformanceCli {

    /** Default location for the baseline JSON. */
    const val DEFAULT_BASELINE = "build/conformance/baseline.json"

    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = runMain(args)
        exitProcess(exitCode)
    }

    /**
     * Test-friendly entry: returns the exit code instead of calling
     * [System.exit]. The JVM [main] above delegates here.
     */
    fun runMain(args: Array<String>): Int {
        val argv = args.toList()
        if (argv.isEmpty() || argv.first() == "-h" || argv.first() == "--help") {
            printUsage()
            return 0
        }
        val cmd = argv.first()
        val rest = argv.drop(1)
        return when (cmd) {
            "list" -> listSuites()
            "run" -> runCommand(rest)
            "baseline-add" -> baselineAdd(rest)
            "baseline-check" -> baselineCheck(rest)
            else -> {
                System.err.println("Unknown subcommand: $cmd")
                printUsage(System.err)
                3
            }
        }
    }

    private fun listSuites(): Int {
        val suites = allSuites()
        println("MCOS Conformance — ${ConformanceReport.PRETTY_JSON} suites available")
        for (suite in suites) {
            val count = suite.cases().size
            println("  ${suite.id.padEnd(10)} · $count case(s) · ${suite.spec}")
            println("              ${suite.title}")
        }
        return 0
    }

    private fun runCommand(args: List<String>): Int {
        val opts = parseOpts(args)
        val runner = ConformanceRunner(allSuites())
        val suites = opts.onlySuiteIds()
        val report = runner.run(suites)
        emitReport(report, opts)
        return runner.exitCode(report)
    }

    private fun baselineAdd(args: List<String>): Int {
        val opts = parseOpts(args)
        val runner = ConformanceRunner(allSuites())
        val report = runner.run(opts.onlySuiteIds())
        val out = File(opts.baselinePath)
        out.parentFile?.mkdirs()
        val baselineJson = baselineFromReport(report)
        out.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), baselineJson))
        // Human-readable summary so the operator sees what they captured.
        if (opts.output == "human" || opts.output == null) {
            System.err.println(
                "Captured ${report.summary.total} cases " +
                    "(${report.summary.passed} pass / ${report.summary.failed} fail / " +
                    "${report.summary.skipped} skip) → ${out.absolutePath}",
            )
        }
        return runner.exitCode(report)
    }

    private fun baselineCheck(args: List<String>): Int {
        val opts = parseOpts(args)
        val runner = ConformanceRunner(allSuites())
        val report = runner.run(opts.onlySuiteIds())
        emitReport(report, opts)

        val baselineFile = File(opts.baselinePath)
        if (!baselineFile.isFile) {
            System.err.println("baseline file not found: ${baselineFile.absolutePath}")
            return 3
        }
        val baselineEl = try {
            Json.parseToJsonElement(baselineFile.readText()).jsonObject
        } catch (e: Exception) {
            System.err.println("baseline file is not valid JSON: ${e.message}")
            return 3
        }
        val baselineSet = parseBaselineSet(baselineEl)
        val currentSet = report.suites.flatMap { suite ->
            suite.cases.map { Triple(suite.id, it.id, it.status) }
        }.toSet()
        val missing = baselineSet - currentSet
        val added = currentSet - baselineSet
        val statusChanged = currentSet.filter { (sId, cId, status) ->
            baselineSet.any { it.first == sId && it.second == cId && it.third != status }
        }
        return when {
            missing.isNotEmpty() || added.isNotEmpty() || statusChanged.isNotEmpty() -> {
                System.err.println("Baseline drift detected:")
                missing.take(20).forEach { System.err.println("  - missing: ${it.first}/${it.second}") }
                added.take(20).forEach { System.err.println("  + added:   ${it.first}/${it.second}") }
                statusChanged.take(20).forEach {
                    System.err.println("  ~ changed: ${it.first}/${it.second} now '${it.third}'")
                }
                2
            }
            else -> {
                if (opts.output == "human" || opts.output == null) {
                    System.err.println("Baseline matches.")
                }
                runner.exitCode(report)
            }
        }
    }

    private fun emitReport(report: ConformanceReport, opts: CliOpts) {
        val outputFile = opts.outputFile?.let { File(it).also { f -> f.parentFile?.mkdirs() } }
        val rendered = when (opts.output) {
            "json" -> JsonReporter.render(report)
            "junit" -> JUnitReporter.render(report)
            else -> HumanReporter.renderToString(report)
        }
        if (outputFile != null) {
            outputFile.writeText(rendered)
        } else {
            print(rendered)
        }
    }

    private fun parseOpts(args: List<String>): CliOpts {
        var onlySuiteIds: Set<String>? = null
        var output: String? = null
        var outputFile: String? = null
        var baselinePath: String = DEFAULT_BASELINE
        var fixturesRoot: String? = null
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--suite" -> {
                    require(i + 1 < args.size) { "--suite requires an argument" }
                    onlySuiteIds = (onlySuiteIds ?: emptySet()) + args[i + 1]
                    i += 2
                }
                "--all" -> {
                    onlySuiteIds = null
                    i++
                }
                "--output" -> {
                    require(i + 1 < args.size) { "--output requires an argument (human|json|junit)" }
                    output = args[i + 1]
                    i += 2
                }
                "--output-file" -> {
                    require(i + 1 < args.size) { "--output-file requires a path" }
                    outputFile = args[i + 1]
                    i += 2
                }
                "--baseline" -> {
                    require(i + 1 < args.size) { "--baseline requires a path" }
                    baselinePath = args[i + 1]
                    i += 2
                }
                "--fixtures-root" -> {
                    require(i + 1 < args.size) { "--fixtures-root requires a path" }
                    fixturesRoot = args[i + 1]
                    i += 2
                }
                else -> throw IllegalArgumentException("Unknown argument: $arg")
            }
        }
        return CliOpts(onlySuiteIds, output, outputFile, baselinePath, fixturesRoot)
    }

    private fun CliOpts.onlySuiteIds(): Set<String>? = onlySuiteIds

    private fun baselineFromReport(report: ConformanceReport): JsonObject =
        buildJsonObject {
            put("version", report.version)
            put("capturedAt", report.startedAt)
            put("spec", report.spec)
            put("cases", buildJsonArray {
                report.suites.forEach { suite ->
                    suite.cases.forEach { case ->
                        add(buildJsonObject {
                            put("suite", suite.id)
                            put("case", case.id)
                            put("status", case.status)
                        })
                    }
                }
            })
        }

    private fun parseBaselineSet(root: JsonObject): Set<Triple<String, String, String>> {
        val arr = root["cases"] ?: return emptySet()
        val list = (arr as kotlinx.serialization.json.JsonArray).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val sId = (obj["suite"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            val cId = (obj["case"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            val status = (obj["status"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: return@mapNotNull null
            Triple(sId, cId, status)
        }
        return list.toSet()
    }

    private fun printUsage(out: java.io.PrintStream = System.out) {
        out.println(
            """
            MCOS Conformance CLI — spec 10 §6.4 / 09 §5.1

            Usage:
              mcos-conformance <subcommand> [options]

            Subcommands:
              list                            List available suites and case counts.
              run                             Run every suite (or --suite X) and emit a report.
              baseline-add                    Run every suite, capture case set + outcomes as JSON baseline.
              baseline-check                  Run every suite, diff against captured baseline JSON.

            Common options:
              --suite <id>                    Restrict to one suite id (repeatable).
              --all                           Run every suite (default for run/baseline-*).
              --output <human|json|junit>     Output format (default: human).
              --output-file <path>            Write the report to this file instead of stdout.
              --baseline <path>               Baseline JSON path (default: $DEFAULT_BASELINE).
              --fixtures-root <path>          Override the docs/fixtures directory (DSL suite).

            Exit codes:
              0  pass
              1  at least one case failed
              2  baseline drift detected (baseline-check only)
              3  configuration / IO error
            """.trimIndent(),
        )
    }

    /**
     * The suites the CLI exposes. Add a new suite here when the
     * conformance surface grows — this is the single registration point.
     */
    private fun allSuites(): List<ConformanceSuite> = listOf(
        DslConformanceSuite(),
        ManifestConformanceSuite(),
        TrustConformanceSuite(),
        IrConformanceSuite(),
    )

    private data class CliOpts(
        val onlySuiteIds: Set<String>?,
        val output: String?,
        val outputFile: String?,
        val baselinePath: String,
        val fixturesRoot: String?,
    )
}