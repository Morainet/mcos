package com.morainet.mcos.marketplace.review

import com.morainet.mcos.runtime.core.registry.SemanticVersion
import com.morainet.mcos.sdk.CommandManifestEntry
import com.morainet.mcos.sdk.PermissionEntry
import com.morainet.mcos.sdk.PluginManifest
import com.morainet.mcos.sdk.SideEffectClass
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Marketplace CI gate engine (09 §5.1 gates 4, 5, 6, 9, 10, 11 — the
 * marketplace-only surface that requires central index state).
 *
 * This is the **shared production implementation**: `mcos-index-server` runs the
 * review pipeline through it, and the `mcos-conformance` "market" suite drives the
 * same class locally so authors reproduce the exact marketplace verdict before
 * submitting (09 §5.1: "Authors who pass local validation should pass CI").
 *
 * Gates 1/2/3/7 are enforced by the manifest reader
 * ([McosPackage.readPluginManifest]) and gate 8 by [com.morainet.mcos.security] —
 * the server orchestrates those stages and concatenates their checks with this
 * engine's report.
 *
 * Gate 2 (reserved namespace) lives in [NamespaceEnforcer] (shared with
 * conformance); this engine includes its verdict for one-shot evaluation.
 *
 * **Honest boundaries** (item 51 handover, kept explicit):
 *  - Gate 4 heuristics are static manifest-facts only. Spec 04 §13.2 check 4 also
 *    mentions "all branches of bodyTemplate/handler return only read-shaped
 *    artifacts", which needs IR/code inspection — out of scope here; flagged in
 *    the review message when a static mismatch is found.
 *  - Gate 6 checks package-level locale completeness. Command-level `title`
 *    i18n overrides are not modelled by [PluginManifest] yet, so the completeness
 *    check applies to the per-locale `name`/`description` overrides that exist.
 *  - Gate 9 never claims a scan that did not run: an unscanned artifact is a
 *    warning (→ human review), not a pass.
 *
 * The spec gate table (09 §5.1) maps failure mode → state:
 *  - any `error` → [ReviewOverall.CI_REJECTED]
 *  - warnings only → [ReviewOverall.HUMAN_REVIEW] (escalation, 09 §5.2)
 *  - all green → [ReviewOverall.APPROVED]
 *
 * @param currentRuntimeVersion current runtime release SemVer (gate 11 cap),
 *   e.g. `"0.2.0"`.
 * @param registry approved-world snapshot used by gates 10/11/5
 *   (empty snapshot ⇒ treat as a brand-new first publication).
 */
class CiGateEngine(
    private val currentRuntimeVersion: String,
    private val registry: RegistrySnapshot = RegistrySnapshot(),
) {

    /**
     * Runs the evaluable gates over [manifest]. Returns a [CiReviewReport] whose
     * checks are ordered by gate number.
     *
     * @param scan outcome of the external AV stage ([ArtifactScan]); pass
     *   [ArtifactScan.Unscanned] when the pipeline has no AV engine wired.
     */
    fun evaluate(
        manifest: PluginManifest,
        scan: ArtifactScan = ArtifactScan.Unscanned,
    ): CiReviewReport {
        val checks = mutableListOf<GateCheck>()
        checks += gate2ReservedNamespace(manifest)
        checks += gate4SideEffectHonesty(manifest)
        checks += gate5SemVer(manifest)
        checks += gate6I18n(manifest)
        checks += gate9Malware(scan)
        checks += gate10NamespaceArbitration(manifest)
        checks += gate11MinRuntime(manifest)

        val overall = when {
            checks.any { it.severity == "error" } -> ReviewOverall.CI_REJECTED
            checks.any { it.severity == "warning" } -> ReviewOverall.HUMAN_REVIEW
            else -> ReviewOverall.APPROVED
        }
        return CiReviewReport(overall, checks)
    }

    // ─── Gate 2: reserved namespace (02 §4.3 / 04 §13.2 check 2) ───────────

    private fun gate2ReservedNamespace(manifest: PluginManifest): List<GateCheck> {
        val reserved = NamespaceEnforcer.findReserved(manifest.commands.map { it.id })
        if (reserved.isNotEmpty()) {
            return reserved.map { id ->
                GateCheck.fail(
                    2, "Reserved namespace",
                    "Command id '$id' uses a reserved prefix (" +
                        NamespaceEnforcer.reservedPrefixes.joinToString(" ") { "`$it`" } +
                        "); third-party plugins may not claim it",
                    id,
                )
            }
        }
        return listOf(GateCheck.pass(2, "Reserved namespace"))
    }

    // ─── Gate 4: sideEffectClass honesty (04 §13.2 check 4) ────────────────

    private fun gate4SideEffectHonesty(manifest: PluginManifest): List<GateCheck> {
        val pluginPermissions = manifest.permissions
        val checks = mutableListOf<GateCheck>()
        for (command in manifest.commands) {
            val commandPermissions = command.permissions
            val all = pluginPermissions + commandPermissions
            val hasEgress = all.any { isEgressPermission(it) }
            val hasAnyPermission = all.isNotEmpty()
            when {
                command.sideEffectClass == SideEffectClass.read && hasEgress -> {
                    val perm = all.first { isEgressPermission(it) }
                    checks += GateCheck.warning(
                        4, "sideEffectClass honesty",
                        "Command '${command.id}' declares sideEffectClass 'read' but " +
                            "requests network-egress permission '${perm.name}'; " +
                            "flagged for human review",
                        command.id,
                    )
                }
                command.sideEffectClass in SIDE_EFFECTING && !hasAnyPermission -> {
                    checks += GateCheck.warning(
                        4, "sideEffectClass honesty",
                        "Command '${command.id}' declares sideEffectClass " +
                            "'${command.sideEffectClass}' with no declared permissions; " +
                            "flagged for human review",
                        command.id,
                    )
                }
            }
        }
        return checks.ifEmpty { listOf(GateCheck.pass(4, "sideEffectClass honesty")) }
    }

    // ─── Gate 5: SemVer compliance (04 §13.1) ───────────────────────────────

    private fun gate5SemVer(manifest: PluginManifest): List<GateCheck> {
        val checks = mutableListOf<GateCheck>()

        if (!SEMVER.matches(manifest.version)) {
            checks += GateCheck.fail(
                5, "SemVer compliance",
                "Plugin version '${manifest.version}' is not a full MAJOR.MINOR.PATCH SemVer",
                manifest.id,
            )
        }
        for (command in manifest.commands) {
            if (!SEMVER.matches(command.version)) {
                checks += GateCheck.fail(
                    5, "SemVer compliance",
                    "Command '${command.id}' version '${command.version}' is not a full " +
                        "MAJOR.MINOR.PATCH SemVer",
                    command.id,
                )
            }
        }

        val previous = registry.previous
        if (previous != null) {
            val previousVersion = previous.version
            val newPlugin = runCatching { SemanticVersion.parse(manifest.version) }.getOrNull()
            val oldPlugin = runCatching { SemanticVersion.parse(previousVersion) }.getOrNull()
            if (newPlugin != null && oldPlugin != null) {
                if (newPlugin <= oldPlugin) {
                    checks += GateCheck.fail(
                        5, "SemVer compliance",
                        "Plugin version must increase monotonically across releases: " +
                            "was $previousVersion, submitted ${manifest.version}",
                        manifest.id,
                    )
                } else if (newPlugin.major > oldPlugin.major) {
                    // MAJOR bump must be accompanied by a MAJOR bump on an owned command.
                    val ownedMajorBump = manifest.commands.any { command ->
                        val old = previous.commandVersions[command.id]
                        old != null &&
                            newMajorOf(command.version) > newMajorOf(old)
                    }
                    if (!ownedMajorBump) {
                        checks += GateCheck.fail(
                            5, "SemVer compliance",
                            "Plugin version MAJOR bump (${manifest.version}) must be " +
                                "accompanied by a MAJOR bump on at least one command; " +
                                "no owned command raised its MAJOR",
                            manifest.id,
                        )
                    }
                }
            }
        }
        return checks.ifEmpty { listOf(GateCheck.pass(5, "SemVer compliance")) }
    }

    // ─── Gate 6: i18n completeness (04 §12.1 check 6) ───────────────────────

    private fun gate6I18n(manifest: PluginManifest): List<GateCheck> {
        val i18n = manifest.i18n
        if (i18n.isNullOrEmpty()) {
            return listOf(GateCheck.pass(6, "i18n completeness"))
        }
        val checks = mutableListOf<GateCheck>()
        for ((locale, overrides) in i18n) {
            if (overrides.name.isNullOrBlank() || overrides.description.isNullOrBlank()) {
                checks += GateCheck.fail(
                    6, "i18n completeness",
                    "Locale '$locale' must provide both 'name' and 'description' " +
                        "for every published locale tag (04 §12.1)",
                    manifest.id,
                )
            }
        }
        return checks.ifEmpty { listOf(GateCheck.pass(6, "i18n completeness")) }
    }

    // ─── Gate 9: malware scan (marketplace-specific) ────────────────────────

    private fun gate9Malware(scan: ArtifactScan): List<GateCheck> {
        return when (scan.verdict) {
            AvVerdict.CLEAN -> listOf(
                GateCheck(
                    9, "Malware scan", "pass", "none",
                    "Artifact scanned by '${scan.engineLabel}' — no detection",
                    buildLocation(scan.engineLabel),
                ),
            )
            AvVerdict.MALICIOUS -> listOf(
                GateCheck(
                    9, "Malware scan", "fail", "error",
                    "Malware scan flagged the artifact (engine '${scan.engineLabel}'); " +
                        "rejected and reported for human review",
                    buildLocation(scan.engineLabel),
                ),
            )
            AvVerdict.UNSCANNED -> listOf(
                GateCheck(
                    9, "Malware scan", "warning", "warning",
                    "No AV engine is configured; artifact was not scanned — routes to human review",
                    buildLocation(scan.engineLabel),
                ),
            )
        }
    }

    // ─── Gate 10: namespace arbitration (02 §4.4, first-published wins) ─────

    private fun gate10NamespaceArbitration(manifest: PluginManifest): List<GateCheck> {
        if (registry.knownCommandIds.isEmpty()) {
            return listOf(GateCheck.pass(10, "Namespace arbitration"))
        }
        val conflicts = manifest.commands
            .map { it.id }
            .filter { it in registry.knownCommandIds }
        if (conflicts.isNotEmpty()) {
            return conflicts.map { id ->
                GateCheck.fail(
                    10, "Namespace arbitration",
                    "Command '$id' is already claimed by an existing marketplace " +
                        "plugin; first-published wins",
                    id,
                )
            }
        }
        return listOf(GateCheck.pass(10, "Namespace arbitration"))
    }

    // ─── Gate 11: min runtime (marketplace-specific) ────────────────────────

    private fun gate11MinRuntime(manifest: PluginManifest): List<GateCheck> {
        val checks = mutableListOf<GateCheck>()
        val min = runCatching { SemanticVersion.parse(manifest.minRuntimeVersion) }
            .getOrNull()
        val current = runCatching { SemanticVersion.parse(currentRuntimeVersion) }.getOrNull()
        if (min == null) {
            checks += GateCheck.fail(
                11, "Min runtime version",
                "minRuntimeVersion '${manifest.minRuntimeVersion}' is not valid SemVer",
                manifest.id,
            )
        } else if (current != null && min > current) {
            checks += GateCheck.fail(
                11, "Min runtime version",
                "Plugin targets a future runtime: minRuntimeVersion " +
                    "${manifest.minRuntimeVersion} > current runtime $currentRuntimeVersion",
                manifest.id,
            )
        }
        val previous = registry.previous
        if (previous != null && min != null) {
            val oldMin = runCatching { SemanticVersion.parse(previous.minRuntimeVersion) }.getOrNull()
            if (oldMin != null && min < oldMin) {
                checks += GateCheck.fail(
                    11, "Min runtime version",
                    "minRuntimeVersion must be monotonic across releases: was " +
                        "${previous.minRuntimeVersion}, submitted ${manifest.minRuntimeVersion}",
                    manifest.id,
                )
            }
        }
        return checks.ifEmpty { listOf(GateCheck.pass(11, "Min runtime version")) }
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    private fun isEgressPermission(permission: PermissionEntry): Boolean =
        when {
            permission.type == "mcos" ->
                permission.name.startsWith("mcos:network") ||
                    permission.name.contains("network", ignoreCase = true)
            permission.type == "android" ->
                permission.name.contains("INTERNET", ignoreCase = true) ||
                    permission.name.contains("NETWORK", ignoreCase = true)
            else -> false
        }

    private fun newMajorOf(version: String): Int =
        runCatching { SemanticVersion.parse(version).major }.getOrDefault(-1)

    private fun buildLocation(engineLabel: String) = buildJsonObject { put("engine", engineLabel) }

    private companion object {
        private val SEMVER = Regex("""^\d+\.\d+\.\d+$""")
        private val SIDE_EFFECTING = setOf(
            SideEffectClass.write,
            SideEffectClass.network,
            SideEffectClass.destructive,
        )
    }
}
