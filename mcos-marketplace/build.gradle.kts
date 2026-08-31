plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    // Publishes io.github.morainet:mcos-marketplace (root convention block).
    id("maven-publish")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":mcos-sdk"))
    api(project(":mcos-security"))
    api(project(":mcos-runtime-core"))
    // Recipe/installer public types expose JsonObject → api.
    api(libs.kotlinx.serialization.json)
    // Suspend-only coroutines usage; no Flow/Job in public signatures.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test)
}
