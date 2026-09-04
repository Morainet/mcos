import org.gradle.api.tasks.JavaExec

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // NOT published. spec 10 §6.4 community "Conformance test suite — published
    // as executable artifact" is satisfied by the `run` JavaExec below: plugin
    // authors clone the repo (or download the released jar) and invoke
    // `./gradlew :mcos-conformance:run --args="…"` against their artifact.
    // The marketplace CI mirrors the same suites (spec 09 §5.1).
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The conformance tool mirrors the marketplace CI gates (spec 09 §5.1):
    //   gate 1 (manifest schema) → :mcos-runtime-core McosPackage.readPluginManifest
    //   gate 2 (reserved namespace) → NamespaceEnforcer (conformance-internal)
    //   gate 3 (duplicate IDs)     → McosPackage.readPluginManifest (fail-closed)
    //   gate 5 (SemVer)            → mcos-marketplace VersionRange
    //   gate 7 (secret containment)→ ConformanceRunner-internal regex
    //   gate 8 (signature verify)  → :mcos-security ArtifactVerifier
    //   gate 8 (blocklist sig)     → :mcos-marketplace BlocklistVerifier
    //   gate 9 (trust gate matrix) → :mcos-security PluginTrustGate
    //   DSL round-trip             → :mcos-runtime-core DslParser + IrTypes
    implementation(project(":mcos-runtime-core"))
    implementation(project(":mcos-sdk"))
    implementation(project(":mcos-marketplace"))
    implementation(project(":mcos-security"))

    // JVM-side tests: meta-tests verify the runner itself (case loading,
    // baseline add/check round-trips, JSON shape). The conformance suites are
    // ALSO exercised by `:mcos-conformance:test` for regression coverage on
    // the runner plumbing — the actual conformance signal comes from the
    // `run` JavaExec against `docs/fixtures/` etc.
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

// ─── CLI runner ─────────────────────────────────────────────────────────────
// spec 10 §6.4: "published as executable artifact". Each task drives
// `ConformanceCli.main()`; the `workingDir` is the repository root so the
// CLI's CWD-relative defaults (`docs/fixtures`, `build/conformance/…`)
// resolve regardless of where the module lives on disk.
val conformanceWorkingDir = rootProject.projectDir

/** Shared wiring for every `ConformanceCli` entry-point task. */
fun JavaExec.conformanceCli(descriptionText: String, vararg cliArgs: String) {
    group = "application"
    description = descriptionText
    workingDir = conformanceWorkingDir
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.morainet.mcos.conformance.ConformanceCli")
    args = cliArgs.toList()
}

val conformanceMain by tasks.registering(JavaExec::class) {
    conformanceCli(
        "List available conformance suites (default subcommand).",
        "list",
    )
}

val conformanceRun by tasks.registering(JavaExec::class) {
    conformanceCli(
        "Run all conformance suites and emit a human-readable report.",
        "run", "--all", "--output", "human",
    )
}

val conformanceBaselineAdd by tasks.registering(JavaExec::class) {
    conformanceCli(
        "Run all conformance suites and capture the current pass set as a baseline JSON.",
        "baseline-add", "--baseline", "build/conformance/baseline.json",
    )
}

val conformanceBaselineCheck by tasks.registering(JavaExec::class) {
    conformanceCli(
        "Run all conformance suites and diff against the captured baseline JSON (CI gate).",
        "baseline-check", "--baseline", "build/conformance/baseline.json",
    )
}

val conformanceJson by tasks.registering(JavaExec::class) {
    conformanceCli(
        "Run all conformance suites and emit a JSON report (machine-readable).",
        "run", "--all", "--output", "json",
    )
}

val conformanceJUnit by tasks.registering(JavaExec::class) {
    conformanceCli(
        "Run all conformance suites and emit a JUnit XML report (CI gate).",
        "run", "--all", "--output", "junit",
    )
}

// Convenience alias so a plugin author can run the whole gate set the way a
// marketplace CI job would: `./gradlew :mcos-conformance:conformance`.
tasks.register("conformance") {
    group = "application"
    description = "Run + baseline-check in one command (CI gate; exit 0 only when green)."
    dependsOn("conformanceRun", "conformanceBaselineCheck")
}