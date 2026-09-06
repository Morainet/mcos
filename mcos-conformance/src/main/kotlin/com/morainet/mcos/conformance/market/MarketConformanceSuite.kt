package com.morainet.mcos.conformance.market

import com.morainet.mcos.conformance.api.ConformanceCase
import com.morainet.mcos.conformance.api.ConformanceSuite
import com.morainet.mcos.marketplace.review.ArtifactScan
import com.morainet.mcos.marketplace.review.AvVerdict
import com.morainet.mcos.marketplace.review.CiGateEngine
import com.morainet.mcos.marketplace.review.CiReviewReport
import com.morainet.mcos.marketplace.review.GateCheck
import com.morainet.mcos.marketplace.review.PreviousRelease
import com.morainet.mcos.marketplace.review.RegistrySnapshot
import com.morainet.mcos.marketplace.review.ReviewOverall
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.I18nOverrides
import com.morainet.mcos.sdk.PermissionEntry
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.ProviderInfo
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.Json

/**
 * Marketplace review-gate conformance (spec 09 §5.1 gates 4/5/6/9/10/11,
 * plus gate 2 as the engine evaluates it).
 *
 * This suite drives the **shared production engine**
 * ([CiGateEngine], the same class `mcos-index-server`'s review pipeline
 * runs) over hand-built manifests and registry snapshots, so an author
 * reproduces the exact marketplace verdict locally before submitting
 * (09 §5.1: "Authors who pass local validation should pass CI").
 *
 * The gates that need central state behave as follows on the author's
 * side:
 *  - gate 10/11/5 update paths take a [RegistrySnapshot]; an **empty
 *    snapshot means first publication** (nothing previously approved),
 *    which is the right default for a new package;
 *  - gate 9 defaults to [ArtifactScan.Unscanned] — the engine never
 *    invents a scan result, so an author who does not wire an AV engine
 *    sees the honest `HUMAN_REVIEW` escalation, never a fabricated
 *    pass.
 *
 * A case *Pass* means the engine produced the expected verdict; a *Fail*
 * carries the engine's actual [CiReviewReport] as structured detail.
 */
class MarketConformanceSuite : ConformanceSuite {
    override val id = "market"
    override val title = "Marketplace CI gates (shared CiGateEngine verdicts)"
    override val spec = "09 §5.1 gates 4/5/6/9/10/11 + 09 §5.2 escalation"

    /** Current runtime release the review caps against (gate 11). */
    private val currentRuntime = "0.2.0"

    override fun cases(): List<ConformanceCase> = buildList {
        // ─── First publication (empty registry) ─────────────────────────
        add(case(
            id = "market-green-first-publication",
            title = "a clean first publication is APPROVED with every gate green",
            manifest = manifest(),
            scan = ArtifactScan.Clean,
            expect = Expect(green = true),
            spec = "09 §5.1 + 09 §5.2",
        ))
        add(case(
            id = "market-reserved-prefix-rejects",
            title = "command id under a reserved prefix (sys.) is CI_REJECTED",
            manifest = manifest(commands = listOf(cmd("sys.notify"))),
            expect = Expect(overall = ReviewOverall.CI_REJECTED, gate = 2, status = "fail"),
            spec = "09 §5.1 gate 2 + 02 §4.3",
        ))

        // ─── Gate 4: sideEffectClass honesty ────────────────────────────
        add(case(
            id = "market-read-with-egress-warns",
            title = "read class that requests network egress routes to HUMAN_REVIEW",
            manifest = manifest(
                pluginPermissions = listOf(net("mcos:network.domain.api.example.com")),
                commands = listOf(cmd("demo.fetch", SideEffectClass.read)),
            ),
            expect = Expect(
                overall = ReviewOverall.HUMAN_REVIEW,
                gate = 4,
                status = "warning",
                messageFragment = "sideEffectClass 'read'",
            ),
            spec = "09 §5.1 gate 4",
        ))
        add(case(
            id = "market-write-without-permissions-warns",
            title = "side-effecting command with no declared permissions routes to HUMAN_REVIEW",
            manifest = manifest(commands = listOf(cmd("demo.write", SideEffectClass.write))),
            expect = Expect(
                overall = ReviewOverall.HUMAN_REVIEW,
                gate = 4,
                status = "warning",
                messageFragment = "no declared permissions",
            ),
            spec = "09 §5.1 gate 4",
        ))

        // ─── Gate 5: SemVer + release coupling ──────────────────────────
        add(case(
            id = "market-non-semver-rejects",
            title = "a version that is not full MAJOR.MINOR.PATCH is CI_REJECTED",
            manifest = manifest(version = "2.0"),
            expect = Expect(overall = ReviewOverall.CI_REJECTED, gate = 5, status = "fail"),
            spec = "09 §5.1 gate 5 + 04 §13.1",
        ))
        add(case(
            id = "market-plugin-downgrade-rejects",
            title = "re-publishing a lower plugin version is CI_REJECTED",
            manifest = manifest(version = "1.9.0"),
            registry = registry(
                previous = release("2.0.0", commandVersions = mapOf("demo.ping" to "2.0.0")),
            ),
            expect = Expect(overall = ReviewOverall.CI_REJECTED, gate = 5, status = "fail"),
            spec = "09 §5.1 gate 5 (monotonicity)",
        ))
        add(case(
            id = "market-major-bump-requires-owned-command-major",
            title = "a plugin MAJOR bump without an owned command MAJOR bump is CI_REJECTED",
            manifest = manifest(version = "2.0.0", commands = listOf(cmd("demo.ping", version = "1.1.0"))),
            registry = registry(
                previous = release("1.0.0", commandVersions = mapOf("demo.ping" to "1.0.0")),
            ),
            expect = Expect(
                overall = ReviewOverall.CI_REJECTED,
                gate = 5,
                status = "fail",
                messageFragment = "MAJOR bump",
            ),
            spec = "09 §5.1 gate 5 (coupling)",
        ))
        add(case(
            id = "market-major-bump-with-command-major-green",
            title = "a plugin MAJOR bump paired with an owned command MAJOR bump is APPROVED",
            manifest = manifest(version = "2.0.0", commands = listOf(cmd("demo.ping", version = "2.0.0"))),
            registry = registry(
                previous = release("1.0.0", commandVersions = mapOf("demo.ping" to "1.0.0")),
            ),
            scan = ArtifactScan.Clean,
            expect = Expect(green = true),
            spec = "09 §5.1 gate 5 (coupling)",
        ))

        // ─── Gate 6: i18n completeness ──────────────────────────────────
        add(case(
            id = "market-partial-locale-i18n-rejects",
            title = "a declared locale missing description is CI_REJECTED",
            manifest = manifest(i18n = mapOf("zh-CN" to I18nOverrides(name = "演示"))),
            expect = Expect(overall = ReviewOverall.CI_REJECTED, gate = 6, status = "fail"),
            spec = "09 §5.1 gate 6 + 04 §12.1",
        ))
        add(case(
            id = "market-complete-locale-green",
            title = "complete per-locale overrides stay APPROVED",
            manifest = manifest(
                i18n = mapOf("zh-CN" to I18nOverrides(name = "演示", description = "演示插件")),
            ),
            scan = ArtifactScan.Clean,
            expect = Expect(green = true),
            spec = "09 §5.1 gate 6 + 04 §12.1",
        ))

        // ─── Gate 9: malware scan — never invents a verdict ─────────────
        add(case(
            id = "market-unscanned-routes-human-review",
            title = "an unscanned artifact escalates to HUMAN_REVIEW (never a silent pass)",
            expect = Expect(
                overall = ReviewOverall.HUMAN_REVIEW,
                gate = 9,
                status = "warning",
                messageFragment = "not scanned",
            ),
            spec = "09 §5.1 gate 9 + 09 §5.2",
        ))
        add(case(
            id = "market-malware-rejects",
            title = "a MALICIOUS scan verdict is CI_REJECTED",
            scan = ArtifactScan(AvVerdict.MALICIOUS, "clamav-test"),
            expect = Expect(
                overall = ReviewOverall.CI_REJECTED,
                gate = 9,
                status = "fail",
                messageFragment = "human review",
            ),
            spec = "09 §5.1 gate 9",
        ))

        // ─── Gate 10: namespace arbitration (first-published wins) ──────
        add(case(
            id = "market-claimed-command-rejects",
            title = "a command already claimed by another plugin is CI_REJECTED",
            registry = registry(knownCommandIds = setOf("demo.ping")),
            expect = Expect(
                overall = ReviewOverall.CI_REJECTED,
                gate = 10,
                status = "fail",
                messageFragment = "first-published wins",
            ),
            spec = "09 §5.1 gate 10 + 02 §4.4",
        ))
        add(case(
            id = "market-own-command-not-a-conflict",
            title = "re-declaring commands owned by the plugin's own previous release passes gate 10",
            manifest = manifest(version = "1.1.0", commands = listOf(cmd("demo.ping", version = "1.1.0"))),
            registry = registry(
                previous = release("1.0.0", commandVersions = mapOf("demo.ping" to "1.0.0")),
                knownCommandIds = setOf("demo.ping"),
            ),
            scan = ArtifactScan.Clean,
            expect = Expect(green = true),
            spec = "09 §5.1 gate 10 + 02 §4.4",
        ))

        // ─── Gate 11: min runtime ───────────────────────────────────────
        add(case(
            id = "market-future-runtime-rejects",
            title = "a plugin targeting a future runtime is CI_REJECTED",
            manifest = manifest(minRuntimeVersion = "0.9.0"),
            expect = Expect(
                overall = ReviewOverall.CI_REJECTED,
                gate = 11,
                status = "fail",
                messageFragment = "future runtime",
            ),
            spec = "09 §5.1 gate 11",
        ))
        add(case(
            id = "market-lowered-min-runtime-rejects",
            title = "minRuntimeVersion must be monotonic across releases",
            manifest = manifest(version = "1.1.0", minRuntimeVersion = "0.1.0"),
            registry = registry(previous = release("1.0.0", minRuntimeVersion = "0.2.0")),
            expect = Expect(
                overall = ReviewOverall.CI_REJECTED,
                gate = 11,
                status = "fail",
                messageFragment = "monotonic",
            ),
            spec = "09 §5.1 gate 11",
        ))

        // ─── Escalation precedence (09 §5.2 table) ──────────────────────
        add(case(
            id = "market-error-precedes-warning",
            title = "any error outweighs warnings: a warning + an error is CI_REJECTED",
            manifest = manifest(
                minRuntimeVersion = "0.9.0", // error (gate 11)
                commands = listOf(cmd("demo.fetch", SideEffectClass.read)), // warning unless perms follow
                pluginPermissions = listOf(net("mcos:network.domain.api.example.com")), // + gate 4 warning
            ),
            expect = Expect(
                overall = ReviewOverall.CI_REJECTED,
                gate = 4,
                status = "warning",
                messageFragment = "sideEffectClass 'read'",
            ),
            spec = "09 §5.2 escalation (error > warning > pass)",
        ))
    }

    // ─── Case runner ────────────────────────────────────────────────────

    /**
     * Runs the real [CiGateEngine] over [manifest] and asserts the
     * expected verdict. Fail carries the engine's actual report as
     * pretty-printed JSON so an author sees exactly which gate fired.
     */
    private fun case(
        id: String,
        title: String,
        manifest: PluginManifest = manifest(),
        scan: ArtifactScan = ArtifactScan.Unscanned,
        registry: RegistrySnapshot = RegistrySnapshot(),
        expect: Expect,
        spec: String = this.spec,
    ): ConformanceCase = object : ConformanceCase {
        override val id = id
        override val title = title
        override val spec = spec
        override val category = "market"

        override fun run(): ConformanceCase.Result {
            val report = CiGateEngine(currentRuntime, registry).evaluate(manifest, scan)
            val problems = verify(report, expect)
            return if (problems.isEmpty()) {
                ConformanceCase.Result.Pass
            } else {
                ConformanceCase.Result.Fail(
                    message = problems.joinToString("; "),
                    detail = Json { prettyPrint = true }
                        .encodeToString(CiReviewReport.serializer(), report),
                )
            }
        }
    }

    private fun verify(report: CiReviewReport, expect: Expect): List<String> {
        val problems = mutableListOf<String>()
        if (report.overall != expect.overall) {
            problems += "overall: expected ${expect.overall}, got ${report.overall}"
        }
        if (expect.green) {
            val nonGreen = report.checks.filter { it.status != "pass" }
            if (nonGreen.isNotEmpty()) {
                problems += "expected every gate pass, got ${nonGreen.map(::describeCheck)}"
            }
        }
        val gateNumber = expect.gate
        if (gateNumber != null) {
            val check = report.checks.firstOrNull { it.gate == gateNumber }
            if (check == null) {
                problems += "gate $gateNumber not evaluated " +
                    "(evaluated gates: ${report.checks.map { it.gate }.sorted()})"
            } else {
                expect.status?.let { status ->
                    if (check.status != status) {
                        problems += "gate $gateNumber status: expected '$status', got '${check.status}'"
                    }
                }
                expect.messageFragment?.let { fragment ->
                    if (!check.message.contains(fragment, ignoreCase = true)) {
                        problems += "gate $gateNumber message: expected fragment '$fragment', " +
                            "got '${check.message}'"
                    }
                }
            }
        }
        return problems
    }

    private fun describeCheck(check: GateCheck): String =
        "gate ${check.gate} [${check.status}] ${check.message}"

    // ─── Fixture builders (same shapes the index server reviews) ────────

    private data class Expect(
        val overall: ReviewOverall = ReviewOverall.APPROVED,
        /** Assert the named gate produced this status. */
        val gate: Int? = null,
        val status: String? = null,
        /** Assert the gate message contains this fragment. */
        val messageFragment: String? = null,
        /** Assert every gate check passed (no warning / fail anywhere). */
        val green: Boolean = false,
    )

    private fun registry(
        previous: PreviousRelease? = null,
        knownCommandIds: Set<String> = emptySet(),
    ) = RegistrySnapshot(previous = previous, knownCommandIds = knownCommandIds)

    private fun release(
        version: String,
        minRuntimeVersion: String = "0.1.0",
        commandVersions: Map<String, String> = emptyMap(),
    ) = PreviousRelease(version, minRuntimeVersion, commandVersions)

    private fun manifest(
        id: String = "com.example.demo",
        version: String = "1.0.0",
        minRuntimeVersion: String = "0.1.0",
        pluginPermissions: List<PermissionEntry> = emptyList(),
        commands: List<CommandManifestEntry> = listOf(cmd("demo.ping")),
        i18n: Map<String, I18nOverrides>? = null,
    ) = PluginManifest(
        id = id,
        name = "Demo Plugin",
        version = version,
        minRuntimeVersion = minRuntimeVersion,
        description = "A demo plugin",
        provider = ProviderInfo("Demo", "https://example.com"),
        entry = "com.example.demo.DemoPlugin",
        permissions = pluginPermissions,
        commands = commands,
        namespaces = listOf("demo"),
        i18n = i18n,
    )

    private fun cmd(
        id: String,
        sideEffectClass: SideEffectClass = SideEffectClass.read,
        version: String = "1.0.0",
        permissions: List<PermissionEntry> = emptyList(),
    ) = CommandManifestEntry(
        id = id,
        version = version,
        title = "Command",
        description = "Does something",
        sideEffectClass = sideEffectClass,
        permissions = permissions,
    )

    private fun net(name: String) = PermissionEntry("mcos", name)
}
