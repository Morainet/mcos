plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":mcos-sdk"))
    // JsonObject appears in public signatures (SchemaValidator.validate) → api.
    api(libs.kotlinx.serialization.json)
    // Coroutines types never leak into public signatures (suspend-only API).
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
}
