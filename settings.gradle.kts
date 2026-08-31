pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mcos"

include(":mcos-sdk")
include(":mcos-security")
include(":mcos-runtime-core")
include(":mcos-runtime")
include(":mcos-llm")
include(":mcos-marketplace")
include(":mcos-android-sdk")
include(":mcos-android")
include(":mcos-server")
// BOM for the published artifacts (java-platform, no sources).
include(":mcos-bom")
include(":plugins:mcos-plugin-hello")
include(":plugins:mcos-plugin-system")
include(":plugins:mcos-plugin-camera")
include(":plugins:mcos-plugin-files")
include(":plugins:mcos-plugin-mcp")
