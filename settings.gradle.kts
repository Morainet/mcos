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
include(":mcos-android")
include(":mcos-server")
include(":plugins:mcos-plugin-hello")
include(":plugins:mcos-plugin-system")
include(":plugins:mcos-plugin-camera")
include(":plugins:mcos-plugin-files")
include(":plugins:mcos-plugin-mcp")
