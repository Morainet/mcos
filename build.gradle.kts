import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.Jar
import org.gradle.plugins.signing.SigningExtension
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// All artifacts publish under one group + version (gradle.properties), so the
// BOM constraints and the cross-module project() edges stay aligned. The demo
// app (:mcos-android), :mcos-server and :plugins:mcos-plugin-mcp opt out of
// publishing entirely (they never apply maven-publish).
subprojects {
    group = findProperty("mcosGroupId") ?: "io.github.morainet"
    version = findProperty("mcosVersion") ?: "0.0.0-dev"
}

// Uniform test output across all modules so CI logs show per-test progress.
// If a test hangs, the last line printed reveals exactly which test is stuck.
subprojects {
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

// ── Maven publication convention ─────────────────────────────────────────────
// Applied to every module that opts in with `id("maven-publish")` (the 11
// library artifacts + :mcos-bom). The convention provides:
//   * shared POM metadata (license / scm / developer),
//   * sources + javadoc jars where the component model allows (Central
//     requires both; the javadoc jar is a placeholder until API docs exist),
//   * a "McosCentralBundle" file repository that `gradlew publish` fills —
//     the release workflow zips it and uploads to the Central Portal REST
//     API. No Sonatype Gradle plugin is used, so ordinary builds never
//     resolve anything new from the plugin portal,
//   * opt-in in-memory PGP signing: signatures are produced only when
//     -PsigningKey=... is provided, so local builds and PR CI need no
//     secrets. Modules with AGP (mcos-android-sdk) register their own
//     publication from components["release"]; the convention here skips
//     component registration when no "java"/"javaPlatform" component exists.
subprojects {
    plugins.withId("maven-publish") {
        val stubJavadoc = tasks.register("stubJavadoc", Jar::class.java) {
            group = "documentation"
            description = "Empty javadoc jar (Central requires a javadoc artifact; API docs are pending)."
            archiveClassifier.set("javadoc")
        }

        // Real sources jar for JVM modules: Central's validation requires a
        // sources artifact per component ("Sources must be provided but not
        // found in entries"). AGP modules register their own via
        // publishing.singleVariant(...).withSourcesJar(); java-platform (BOM)
        // modules are exempt — a BOM ships only its POM.
        val sourcesJar = tasks.register("sourcesJar", Jar::class.java) {
            group = "documentation"
            description = "Sources jar (Central requires a sources artifact)."
            archiveClassifier.set("sources")
            from(project.the<SourceSetContainer>()["main"].allSource)
        }

        extensions.configure(PublishingExtension::class.java) {
            repositories {
                maven {
                    name = "McosCentralBundle"
                    url = uri(rootProject.layout.buildDirectory.dir("central-bundle"))
                }
            }
        }

        afterEvaluate {
            extensions.configure(PublishingExtension::class.java) {
                publications.withType(MavenPublication::class.java).configureEach {
                    pom {
                        name.set(project.name)
                        description.set(
                            "MCOS (Mobile Command OS) — $name module. " +
                                "See https://github.com/Morainet/mcos for the full architecture.",
                        )
                        url.set("https://github.com/Morainet/mcos")
                        licenses {
                            license {
                                name.set("Apache-2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                        developers {
                            developer {
                                id.set("Morainet")
                                name.set("Morainet")
                                url.set("https://github.com/Morainet")
                            }
                        }
                        scm {
                            connection.set("scm:git:git://github.com/Morainet/mcos.git")
                            developerConnection.set("scm:git:ssh://git@github.com/Morainet/mcos.git")
                            url.set("https://github.com/Morainet/mcos")
                        }
                    }
                }

                // JVM modules publish the `java` component (Kotlin wires its
                // metadata into it); the BOM publishes `javaPlatform`; AGP
                // modules self-register (components["release"]) in their own
                // build script, so they take the null branch here.
                if (publications.findByName("maven") == null) {
                    val component = components.findByName("javaPlatform")
                        ?: components.findByName("java")
                    if (component != null) {
                        publications.create("maven", MavenPublication::class.java) {
                            from(component)
                            if (plugins.hasPlugin("java") && !plugins.hasPlugin("java-platform")) {
                                artifact(stubJavadoc.get())
                                artifact(sourcesJar.get())
                            }
                        }
                    }
                }
            }

            // Signing activates only when a key is supplied (release CI):
            //   -PsigningKey=<armored private key> -PsigningPassword=<passphrase>
            val signingKey = findProperty("signingKey") as String?
            if (!signingKey.isNullOrBlank()) {
                pluginManager.apply("signing")
                extensions.configure(SigningExtension::class.java) {
                    isRequired = true
                    useInMemoryPgpKeys(signingKey, (findProperty("signingPassword") as String?) ?: "")
                    sign(extensions.getByType(PublishingExtension::class.java).publications)
                }
            }
        }
    }
}
