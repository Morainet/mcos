plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":mcos-sdk"))
    api(project(":mcos-runtime-core"))
    // Public data classes (ToolCall types, recipes) expose JsonObject → api.
    api(libs.kotlinx.serialization.json)
    // ChatOrchestrator's constructor exposes Flow<RuntimeEvent> (RuntimeGateway)
    // → api.
    api(libs.kotlinx.coroutines.core)

    // ChatOrchestrator drives the kernel through the RuntimeGateway port
    // (core.api), NOT through the mcos-runtime facade — llm never sees the
    // facade or its marketplace re-export. Tests build the real facade for
    // integration-style coverage, hence the test-only edge.
    testImplementation(project(":mcos-runtime"))

    testImplementation(libs.kotlin.test)
}
