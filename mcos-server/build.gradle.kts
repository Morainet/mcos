plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.mcos.server.MainKt")
}

dependencies {
    testImplementation(libs.kotlin.test)
    // Interop tests exercise the real device-side transport against this server.
    testImplementation(project(":mcos-runtime"))
}
