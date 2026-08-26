plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
