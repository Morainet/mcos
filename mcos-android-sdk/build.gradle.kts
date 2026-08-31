import org.gradle.api.publish.maven.MavenPublication

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // Publishes io.github.morainet:mcos-android-sdk (AAR). Shared POM
    // metadata / staging repository / signing come from the root build's
    // maven-publish convention block.
    id("maven-publish")
}

android {
    namespace = "com.morainet.mcos.android"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        // Applied to consumer apps that enable minification: keeps the
        // runtime API surface intact and silences java.net.http warnings
        // (the JDK transports in the JVM artifacts are never loaded on
        // Android — hosts inject their own transport implementation).
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Publish only the release variant. AGP generates the sources jar; the
    // javadoc artifact is the root convention's stub jar instead of
    // withJavadocJar(): AGP 8.7's bundled Dokka chokes on sealed classes
    // ("PermittedSubclasses requires ASM9"), and Central only requires a
    // valid javadoc zip anyway. Swap for real API docs when they exist.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            register("maven", MavenPublication::class.java) {
                from(components["release"])
                artifact(tasks.named("stubJavadoc"))
            }
        }
    }
}

dependencies {
    // MCOS internal modules. The SDK is the UI-free Android host runtime —
    // any app can embed it and ship its own UI (the demo shell in
    // :mcos-android shows how). No Compose/ViewModel dependencies here by
    // design: the bridge classes are the only Activity-bound surface.
    implementation(project(":mcos-sdk"))
    implementation(project(":mcos-runtime"))
    // CompositionRoot wires the full marketplace chain (index client,
    // installer, trust anchors) and the LLM transport adapter.
    implementation(project(":mcos-llm"))
    implementation(project(":mcos-marketplace"))
    // Core subsystem types (CommandRegistry, PluginLoader, Executor) and
    // security types (EnterprisePolicy, ArtifactVerifier, PermissionKernel)
    // appear in AppDeps' public surface; declare the edges explicitly.
    implementation(project(":mcos-runtime-core"))
    implementation(project(":mcos-security"))
    // Reference host default plugin set (CompositionRoot.defaultBuiltIns;
    // a third-party host injects its own list instead).
    implementation(project(":plugins:mcos-plugin-hello"))
    implementation(project(":plugins:mcos-plugin-system"))
    implementation(project(":plugins:mcos-plugin-camera"))
    implementation(project(":plugins:mcos-plugin-files"))

    // FileProvider (camera capture / media compress share URIs)
    implementation(libs.androidx.core.ktx)
    // ActivityResultBridge binds the activity-result contracts; the -ktx
    // artifact carries no Compose, keeping the SDK UI-free.
    implementation(libs.androidx.activity.ktx)

    // Unit tests (bridges + transports + loaders — plain JVM, no Robolectric)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
