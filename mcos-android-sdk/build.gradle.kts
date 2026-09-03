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

        // On-device instrumented tests (the Binder isolation verification
        // suite). No emulator in CI — run locally against an attached device
        // via `sh gradlew :mcos-android-sdk:connectedDebugAndroidTest`.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

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

// ─── On-device isolation verification fixture ─────────────────────────────
// The BinderIsolationDeviceTest installs a REAL .mcos artifact through the
// production PluginInstaller, so the plugin's code must exist as a real dex.
// The fixture plugin lives in :plugins:mcos-plugin-devicefixture (pure JVM);
// this task dexes its jar with build-tools d8 and exposes the result as an
// androidTest asset the test zips next to its plugin.json. Regenerated on
// every build — no binary fixture is committed.
val deviceFixtureClasspath: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    // Just the fixture jar itself — its SDK/Kotlin deps resolve at runtime
    // through the DexClassLoader parent on device, and must not be dexed in.
    isTransitive = false
}

val deviceFixtureAssetsDir = layout.buildDirectory.dir("generated/deviceFixtureAssets")

val deviceFixtureDex = tasks.register("deviceFixtureDex") {
    group = "verification"
    description = "Dex the device-isolation fixture plugin into an androidTest asset."
    val dexOutDir = layout.buildDirectory.dir("generated/deviceFixtureDex").get().asFile
    inputs.files(deviceFixtureClasspath)
    outputs.file(File(deviceFixtureAssetsDir.get().asFile, "device-fixture.dex"))
    doLast {
        val jarFile = deviceFixtureClasspath.files.single { it.extension == "jar" }
        val d8 = File(android.sdkDirectory, "build-tools/${android.buildToolsVersion}/d8")
        // compileSdk is 35 (above); --lib only guides desugaring checks.
        val androidJar = File(android.sdkDirectory, "platforms/android-35/android.jar")
        dexOutDir.deleteRecursively()
        dexOutDir.mkdirs()
        // Missing-class warnings are expected: the fixture references SDK and
        // Kotlin types that resolve at runtime through the DexClassLoader's
        // parent (the app classloader).
        exec {
            commandLine(
                d8.absolutePath, "--release", "--min-api", "26",
                "--lib", androidJar.absolutePath,
                "--output", dexOutDir.absolutePath,
                jarFile.absolutePath,
            )
        }
        val assetDir = deviceFixtureAssetsDir.get().asFile
        assetDir.mkdirs()
        File(dexOutDir, "classes.dex").copyTo(File(assetDir, "device-fixture.dex"), overwrite = true)
    }
}

android.sourceSets.getByName("androidTest") {
    assets.srcDir(deviceFixtureAssetsDir)
}
tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach {
    dependsOn(deviceFixtureDex)
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

    // Instrumented on-device tests: the Binder isolation verification suite
    // (BinderIsolationDeviceTest) — production CompositionRoot + real
    // :mcos_plugin process split, run against attached hardware.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Fixture plugin: constants (id, marker) shared with the test + its jar
    // feeds the deviceFixtureDex task via the dedicated configuration.
    androidTestImplementation(project(":plugins:mcos-plugin-devicefixture"))
    deviceFixtureClasspath(project(":plugins:mcos-plugin-devicefixture"))
}
