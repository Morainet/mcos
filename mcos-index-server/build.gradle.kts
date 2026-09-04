plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.morainet.mcos.indexserver.MainKt")
}

dependencies {
    // Review gates: the engine lives in the shared client library so local
    // author validation (conformance) and marketplace CI run one implementation.
    implementation(project(":mcos-marketplace"))
    implementation(project(":mcos-security"))
    implementation(project(":mcos-runtime-core"))
    implementation(project(":mcos-sdk"))
    testImplementation(libs.kotlin.test)
    // Interop tests drive the server with the real shipped client.
    testImplementation(project(":mcos-marketplace"))
    testImplementation(project(":mcos-security"))
}
