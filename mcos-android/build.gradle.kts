plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.morainet.mcos.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.morainet.mcos.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // MCOS internal modules
    implementation(project(":mcos-sdk"))
    implementation(project(":mcos-runtime"))
    // MainActivity wires the LLM chat pipeline directly (ChatOrchestrator,
    // LlmPlanner, providers, AndroidLlmHttpTransport implements the llm
    // transport interface), so the app module depends on :mcos-llm explicitly.
    implementation(project(":mcos-llm"))
    // Marketplace UI (search / install / uninstall) wires MarketplaceIndex and
    // PluginInstaller directly; AndroidMarketplaceHttpTransport implements the
    // marketplace transport interface (Android has no java.net.http module).
    implementation(project(":mcos-marketplace"))
    // The app reads core subsystem types (CommandRegistry, PluginLoader,
    // Executor) and security types (EnterprisePolicy, ArtifactVerifier)
    // directly; declare the edges instead of relying on transitive api leaks.
    implementation(project(":mcos-runtime-core"))
    implementation(project(":mcos-security"))
    implementation(project(":plugins:mcos-plugin-hello"))
    implementation(project(":plugins:mcos-plugin-system"))
    implementation(project(":plugins:mcos-plugin-camera"))
    implementation(project(":plugins:mcos-plugin-files"))

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    // Unit tests (McosViewModel — plain JVM, no Robolectric needed)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
