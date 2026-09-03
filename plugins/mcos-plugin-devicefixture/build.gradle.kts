plugins {
    alias(libs.plugins.kotlin.jvm)
    // NOT published: device-verification fixture only. Its jar is dexed by
    // mcos-android-sdk's `deviceFixtureDex` task and packed into the .mcos
    // artifact that BinderIsolationDeviceTest installs on a real device.
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":mcos-sdk"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}
