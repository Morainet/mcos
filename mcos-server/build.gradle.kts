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
    // Enterprise-policy endpoint validates policy documents on PUT
    // (EnterprisePolicy.parse — a malformed policy must be refused at the
    // management channel, not pushed to clients for them to fail closed on).
    implementation(project(":mcos-security"))
    testImplementation(libs.kotlin.test)
    // Interop tests exercise the real device-side transport against this
    // server (memory package lives in :mcos-runtime-core after the split).
    testImplementation(project(":mcos-runtime-core"))
}
