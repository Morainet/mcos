plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.morainet.mcos.server.MainKt")
}

dependencies {
    testImplementation(libs.kotlin.test)
    // Interop tests exercise the real device-side transport against this
    // server (memory package lives in :mcos-runtime-core after the split).
    testImplementation(project(":mcos-runtime-core"))
}
