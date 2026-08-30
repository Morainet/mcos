plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    // The demo shell lives in the .demo package; the applicationId stays
    // com.morainet.mcos.android so install identity and the CI artifact path
    // are unchanged by the SDK split.
    namespace = "com.morainet.mcos.android.demo"
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
    // UI-free host runtime (composition root, receivers, host services,
    // dynamic loading) — everything an integrating app needs; this demo
    // shell is just one consumer of it.
    implementation(project(":mcos-android-sdk"))
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
    // MCP bridge adapter (02 §12.4 spike): the shell discovers a user-configured
    // MCP server and registers its tools as mcp.* commands via the runtime.
    implementation(project(":plugins:mcos-plugin-mcp"))

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
    // Test fixtures compose custom built-ins lists for CompositionRoot.create
    // (TestMarketplace / MarketplaceViewModelTest); the demo's own default
    // built-in set now lives in the SDK module.
    testImplementation(project(":plugins:mcos-plugin-hello"))
    testImplementation(project(":plugins:mcos-plugin-system"))
    testImplementation(project(":plugins:mcos-plugin-camera"))
    testImplementation(project(":plugins:mcos-plugin-files"))
}
