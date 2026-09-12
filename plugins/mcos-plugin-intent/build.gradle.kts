plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Standalone like mcos-plugin-iot / mcos-plugin-mcp: a host wires
    // IntentPlugin explicitly, so it is not part of the android-sdk default
    // set and is intentionally absent from the published artifact set / BOM.
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":mcos-sdk"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}
