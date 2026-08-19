package com.morainet.mcos.runtime

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture guard: no package may be split across Gradle modules
 * (01-architecture.md §3.3 — split packages break encapsulation and are
 * JPMS-incompatible).
 *
 * Two historical splits were migrated (2026-08-17):
 * - `com.morainet.mcos.runtime.api` — core-side types moved to
 *   `com.morainet.mcos.runtime.core.api` in `mcos-runtime-core`; the facade
 *   module keeps the original package.
 * - `com.morainet.mcos.runtime.memory` — the facade module's
 *   `RunSummarizerTest` moved to this bare `com.morainet.mcos.runtime` package
 *   (the subsystem package itself was renamed again by the module-alignment
 *   pass below).
 *
 * The same day, all subsystem packages were renamed to module-aligned roots
 * (`runtime.core.*`, `security.*`, `llm.*`, `marketplace.*`) so each Gradle
 * module owns its `com.morainet.mcos.<module>.*` namespace; the third test
 * pins that mapping (01-architecture.md §3.1).
 *
 * The test walks every module's `src/main` and `src/test` Kotlin sources from
 * the repository root (located by walking up from the working directory to
 * `settings.gradle.kts`), so it needs no Gradle wiring and fails on any new
 * cross-module package.
 */
class PackageBoundariesTest {

    @Test
    fun `no package is declared in two different modules`() {
        val root = findRepoRoot()
        val packageOwners = mutableMapOf<String, MutableSet<String>>()

        sourceRoots(root).forEach { file ->
            val module = moduleName(root, file)
            val pkg = packageDeclaration(file) ?: return@forEach
            packageOwners.getOrPut(pkg) { mutableSetOf() }.add(module)
        }

        val split = packageOwners.filterValues { it.size > 1 }
        assertTrue(
            "Packages split across modules (each must live in exactly one module):\n" +
                split.entries.joinToString("\n") { (pkg, modules) -> "  $pkg -> $modules" },
            split.isEmpty(),
        )
    }

    @Test
    fun `the migrated runtime api packages each live in exactly one module`() {
        val root = findRepoRoot()
        val owners = sourceRoots(root)
            .groupBy(packageDeclarationOrNull())
            .mapValues { (_, files) -> files.map { moduleName(root, it) }.toSet() }

        // Facade keeps the original package; core types moved next door.
        assertTrue(
            "runtime.api must be facade-only after the migration, got ${owners["com.morainet.mcos.runtime.api"]}",
            owners["com.morainet.mcos.runtime.api"] == setOf("mcos-runtime"),
        )
        assertTrue(
            "runtime.core.api must be core-only, got ${owners["com.morainet.mcos.runtime.core.api"]}",
            owners["com.morainet.mcos.runtime.core.api"] == setOf("mcos-runtime-core"),
        )
    }

    @Test
    fun `package names align with module names`() {
        val root = findRepoRoot()
        val misaligned = sourceRoots(root)
            .mapNotNull { file -> packageDeclaration(file)?.let { pkg -> Triple(pkg, moduleName(root, file), expectedOwner(pkg)) } }
            .filter { (_, module, expected) -> expected == null || module != expected }
            .distinct()

        assertTrue(
            "Packages whose owning module breaks the module↔package mapping (01-architecture.md §3.1):\n" +
                misaligned.joinToString("\n") { (pkg, module, expected) ->
                    "  $pkg -> $module (expected $expected)"
                },
            misaligned.isEmpty(),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /**
     * Package root → owning module. Longest-prefix match wins, so
     * `runtime.core.*` resolves to `mcos-runtime-core` while the facade keeps
     * the bare `com.morainet.mcos.runtime` root and `runtime.api`. A package
     * matching no rule (null) fails the alignment test, forcing new modules to
     * register here deliberately.
     */
    private val packageRules = listOf(
        "com.morainet.mcos.sdk" to "mcos-sdk",
        "com.morainet.mcos.security" to "mcos-security",
        "com.morainet.mcos.runtime.core" to "mcos-runtime-core",
        "com.morainet.mcos.runtime" to "mcos-runtime",
        "com.morainet.mcos.llm" to "mcos-llm",
        "com.morainet.mcos.marketplace" to "mcos-marketplace",
        "com.morainet.mcos.android" to "mcos-android",
        "com.morainet.mcos.server" to "mcos-server",
        "com.morainet.mcos.plugin.hello" to "plugins:mcos-plugin-hello",
        "com.morainet.mcos.plugin.system" to "plugins:mcos-plugin-system",
        "com.morainet.mcos.plugin.camera" to "plugins:mcos-plugin-camera",
        "com.morainet.mcos.plugin.files" to "plugins:mcos-plugin-files",
    )

    private fun expectedOwner(pkg: String): String? = packageRules
        .filter { (prefix, _) -> pkg == prefix || pkg.startsWith("$prefix.") }
        .maxByOrNull { (prefix, _) -> prefix.length }
        ?.second

    private fun findRepoRoot(): File {
        var dir = File(".").absoluteFile.parentFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return checkNotNull(dir) { "settings.gradle.kts not found above ${File(".").absoluteFile}" }
    }

    /** Kotlin sources of every module's main and test source sets. */
    private fun sourceRoots(root: File): List<File> {
        val moduleDirs = root.listFiles { f: File -> f.isDirectory && f.name.startsWith("mcos-") }
            .orEmpty()
            .toList() + (File(root, "plugins").listFiles { f: File -> f.isDirectory }.orEmpty().toList())
        return moduleDirs.flatMap { dir ->
            listOf(File(dir, "src/main/kotlin"), File(dir, "src/test/kotlin"))
        }.filter { it.isDirectory }
            .flatMap { it.walkTopDown().filter { file -> file.extension == "kt" }.toList() }
    }

    /** `plugins/mcos-plugin-x` → `plugins:mcos-plugin-x`; `mcos-llm` → `mcos-llm`. */
    private fun moduleName(root: File, file: File): String {
        val relative = file.relativeTo(root).path.split(File.separatorChar)
        return if (relative.first() == "plugins") relative.take(2).joinToString(":") else relative.first()
    }

    private fun packageDeclaration(file: File): String? = packageDeclarationOrNull()(file)

    private fun packageDeclarationOrNull(): (File) -> String? = { file ->
        file.useLines { lines ->
            lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
        }
    }
}
