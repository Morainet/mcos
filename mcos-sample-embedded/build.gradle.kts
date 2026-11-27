plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.morainet.mcos.sample.embedded"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.morainet.mcos.sample.embedded"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { lintConfig = file("lint.xml") }
}

dependencies {
    implementation(project(":mcos-android-sdk"))
    implementation(project(":mcos-runtime"))
    implementation(project(":mcos-runtime-core"))
    implementation(project(":mcos-security"))
    implementation(project(":mcos-sdk"))
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
