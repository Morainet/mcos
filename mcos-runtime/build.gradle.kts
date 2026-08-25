plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":mcos-sdk"))
    api(project(":mcos-security"))
    api(project(":mcos-runtime-core"))
    api(project(":mcos-marketplace"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    // FE tests exercise the REAL FilesPlugin sandbox commands through the
    // full runtime stack (test-only; production hosts wire it themselves,
    // e.g. mcos-android).
    testImplementation(project(":plugins:mcos-plugin-files"))
}
