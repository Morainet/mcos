plugins {
    `java-platform`
    // Publishes io.github.morainet:mcos-bom (root convention block).
    id("maven-publish")
}

// Publishable-artifact BOM (io.github.morainet:mcos-bom). All MCOS modules
// MUST stay on one aligned version: they share cross-artifact contracts
// (AuthStamp signing, plugin ABI, isolation binder protocol), so mixed
// versions fail at runtime, not at compile time. Consumers import this
// platform and omit versions per artifact.
//
// Build-only module (no sources) — exempt from the src/main package rules by
// design. Keep this list in sync with the modules that apply maven-publish:
// 6 JVM core artifacts, 4 published built-in plugins, 1 Android AAR.
// Not listed: :mcos-android (demo app), :mcos-server, :plugins:mcos-plugin-mcp.
dependencies {
    constraints {
        val group = project.group.toString()
        val version = project.version.toString()
        listOf(
            "mcos-sdk",
            "mcos-security",
            "mcos-runtime-core",
            "mcos-llm",
            "mcos-marketplace",
            "mcos-runtime",
            "mcos-plugin-hello",
            "mcos-plugin-system",
            "mcos-plugin-camera",
            "mcos-plugin-files",
            "mcos-android-sdk",
        ).forEach { artifact ->
            api("$group:$artifact:$version")
        }
    }
}
