package com.morainet.mcos.marketplace

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
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for the shared marketplace CI gate engine
 * ([CiGateEngine], spec 09 §5.1 gates 4/5/6/9/10/11).
 *
 * The engine is the shared implementation between the index server review
 * pipeline and the conformance "market" suite; these tests pin the
 * marketplace-side judgement rules that 09 §5.1 reserves for the central
 * index state.
 */
class CiGateEngineTest {

    @Test
    fun `all-green first publication approves`() {
        val report = engine().evaluate(manifest(), scan = ArtifactScan.Clean)
        assertEquals(ReviewOverall.APPROVED, report.overall)
        assertTrue(report.checks.all { it.severity == "none" }, "no fail/warning: ${report.checks}")
        assertEquals(setOf(2, 4, 5, 6, 9, 10, 11), report.checks.map { it.gate }.toSet())
    }

    @Test
    fun `scanned-clean artifact passes gate 9`() {
        val report = engine().evaluate(
            manifest(),
            scan = ArtifactScan(AvVerdict.CLEAN, "clamav-test"),
        )
        assertEquals("pass", gate(report, 9).status)
    }

    @Test
    fun `malware hit rejects`() {
        val report = engine().evaluate(
            manifest(),
            scan = ArtifactScan(AvVerdict.MALICIOUS, "clamav-test"),
        )
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
        val check = gate(report, 9)
        assertEquals("fail", check.status)
        assertTrue(check.message.contains("human review"))
    }

    @Test
    fun `unscanned artifact warns and escalates to human review`() {
        val report = engine().evaluate(manifest(), scan = ArtifactScan.Unscanned)
        assertEquals(ReviewOverall.HUMAN_REVIEW, report.overall)
        val check = gate(report, 9)
        assertEquals("warning", check.status)
        assertTrue(check.message.contains("not scanned"))
    }

    // ─── Gate 2 ──────────────────────────────────────────────────────────

    @Test
    fun `reserved command id rejects`() {
        for (prefix in listOf("mcos.", "sys.", "mcp.", "std.")) {
            val report = engine().evaluate(
                manifest(commands = listOf(cmd("$prefix.example"))),
            )
            assertEquals(ReviewOverall.CI_REJECTED, report.overall, "prefix $prefix")
            val check = gate(report, 2)
            assertEquals("fail", check.status)
            assertEquals("$prefix.example", commandIdOf(check))
        }
    }

    // ─── Gate 4 ──────────────────────────────────────────────────────────

    @Test
    fun `read class with network egress permission warns`() {
        val report = engine().evaluate(
            manifest(
                pluginPermissions = listOf(networkPermission("mcos:network.domain.api.example.com")),
                commands = listOf(cmd("demo.fetch", SideEffectClass.read)),
            ),
        )
        assertEquals(ReviewOverall.HUMAN_REVIEW, report.overall)
        val check = gate(report, 4)
        assertEquals("warning", check.status)
        assertTrue(check.message.contains("sideEffectClass 'read'"))
        assertEquals("demo.fetch", commandIdOf(check))
    }

    @Test
    fun `write class with no permissions warns`() {
        val report = engine().evaluate(
            manifest(commands = listOf(cmd("demo.write", SideEffectClass.write))),
        )
        assertEquals(ReviewOverall.HUMAN_REVIEW, report.overall)
        val check = gate(report, 4)
        assertEquals("warning", check.status)
        assertTrue(check.message.contains("no declared permissions"))
    }

    @Test
    fun `android internet permission on read command warns too`() {
        val report = engine().evaluate(
            manifest(
                commands = listOf(cmd("demo.fetch", SideEffectClass.read)),
                pluginPermissions = listOf(androidPermission("android.permission.INTERNET")),
            ),
        )
        assertEquals(ReviewOverall.HUMAN_REVIEW, report.overall)
    }

    @Test
    fun `side-effecting command with declared permissions stays green`() {
        val report = engine().evaluate(
            manifest(
                commands = listOf(
                    cmd(
                        "demo.write",
                        SideEffectClass.write,
                        permissions = listOf(networkPermission("mcos:network.domain.api.example.com")),
                    ),
                ),
            ),
            scan = ArtifactScan.Clean,
        )
        assertEquals(ReviewOverall.APPROVED, report.overall)
    }

    // ─── Gate 5 ──────────────────────────────────────────────────────────

    @Test
    fun `non-semver plugin version rejects`() {
        val report = engine().evaluate(manifest(version = "2.0"))
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
        assertEquals("fail", gate(report, 5).status)
    }

    @Test
    fun `non-semver command version rejects`() {
        val report = engine().evaluate(
            manifest(commands = listOf(cmd("demo.ping", version = "abc"))),
        )
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
    }

    @Test
    fun `plugin major bump without owned command major bump rejects`() {
        val snapshot = RegistrySnapshot(
            previous = PreviousRelease(
                version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                commandVersions = mapOf("demo.ping" to "1.0.0"),
            ),
        )
        val report = CiGateEngine("0.2.0", snapshot).evaluate(
            manifest(version = "2.0.0", commands = listOf(cmd("demo.ping", version = "1.1.0"))),
        )
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
        val check = gate(report, 5)
        assertTrue(check.message.contains("MAJOR bump"))
    }

    @Test
    fun `plugin major bump with owned command major bump passes`() {
        val snapshot = RegistrySnapshot(
            previous = PreviousRelease(
                version = "1.0.0",
                minRuntimeVersion = "0.1.0",
                commandVersions = mapOf("demo.ping" to "1.0.0"),
            ),
        )
        val report = CiGateEngine("0.2.0", snapshot).evaluate(
            manifest(version = "2.0.0", commands = listOf(cmd("demo.ping", version = "2.0.0"))),
            scan = ArtifactScan.Clean,
        )
        assertEquals(ReviewOverall.APPROVED, report.overall)
    }

    @Test
    fun `version downgrade rejects`() {
        val snapshot = RegistrySnapshot(
            previous = PreviousRelease(version = "2.0.0", minRuntimeVersion = "0.1.0"),
        )
        val report = CiGateEngine("0.2.0", snapshot).evaluate(manifest(version = "1.9.0"))
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
    }

    // ─── Gate 6 ──────────────────────────────────────────────────────────

    @Test
    fun `locale missing description rejects`() {
        val report = engine().evaluate(
            manifest(
                i18n = mapOf("zh-CN" to I18nOverrides(name = "演示")),
            ),
        )
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
        assertTrue(gate(report, 6).message.contains("zh-CN"))
    }

    @Test
    fun `complete locale overrides pass`() {
        val report = engine().evaluate(
            manifest(
                i18n = mapOf(
                    "zh-CN" to I18nOverrides(name = "演示", description = "演示插件"),
                ),
            ),
            scan = ArtifactScan.Clean,
        )
        assertEquals(ReviewOverall.APPROVED, report.overall)
    }

    @Test
    fun `no i18n declared is a pass`() {
        assertEquals(
            ReviewOverall.APPROVED,
            engine().evaluate(manifest(), scan = ArtifactScan.Clean).overall,
        )
    }

    // ─── Gate 10 ─────────────────────────────────────────────────────────

    @Test
    fun `command claimed by another approved plugin rejects`() {
        val snapshot = RegistrySnapshot(knownCommandIds = setOf("demo.ping"))
        val report = CiGateEngine("0.2.0", snapshot).evaluate(manifest())
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
        val check = gate(report, 10)
        assertEquals("fail", check.status)
        assertTrue(check.message.contains("first-published wins"))
    }

    @Test
    fun `fresh commands against empty registry pass gate 10`() {
        assertEquals(
            ReviewOverall.APPROVED,
            engine().evaluate(manifest(), scan = ArtifactScan.Clean).overall,
        )
    }

    // ─── Gate 11 ─────────────────────────────────────────────────────────

    @Test
    fun `plugin targeting a future runtime rejects`() {
        val report = engine().evaluate(manifest(minRuntimeVersion = "0.9.0"))
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
        val check = gate(report, 11)
        assertTrue(check.message.contains("future runtime"))
    }

    @Test
    fun `runtime requirement must be monotonic across releases`() {
        val snapshot = RegistrySnapshot(
            previous = PreviousRelease(
                version = "1.0.0",
                minRuntimeVersion = "0.2.0",
            ),
        )
        val report = CiGateEngine("0.2.0", snapshot)
            .evaluate(manifest(version = "1.1.0", minRuntimeVersion = "0.1.0"))
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
        assertTrue(gate(report, 11).message.contains("monotonic"))
    }

    @Test
    fun `future-runtime cap applies to first publication too`() {
        val report = CiGateEngine("0.2.0").evaluate(manifest(minRuntimeVersion = "1.0.0"))
        assertEquals(ReviewOverall.CI_REJECTED, report.overall)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun engine(
        snapshot: RegistrySnapshot = RegistrySnapshot(),
    ): CiGateEngine = CiGateEngine("0.2.0", snapshot)

    private fun commandIdOf(check: GateCheck): String =
        check.location["commandId"]?.jsonPrimitive?.content ?: error("no commandId in $check")

    private fun gate(report: CiReviewReport, gateNumber: Int): GateCheck {
        val check = report.checks.firstOrNull { it.gate == gateNumber }
        assertNotNull(check, "gate $gateNumber missing from ${report.checks}")
        return check
    }

    private fun manifest(
        id: String = "com.example.demo",
        version: String = "1.0.0",
        minRuntimeVersion: String = "0.1.0",
        namespaces: List<String> = listOf("demo"),
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
        namespaces = namespaces,
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

    private fun networkPermission(name: String) = PermissionEntry("mcos", name)

    private fun androidPermission(name: String) = PermissionEntry("android", name)
}
