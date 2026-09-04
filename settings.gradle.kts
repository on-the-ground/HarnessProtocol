pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "harness-protocol"

include(
    "harness-protocol",
    "harness-runtime",
    "harness-koog",
    "harness-native-integration",
    "harness-process-bridge",
    "harness-codex",
    "harness-gemini-cli",
    "harness-bundle",
    "harness-adapter-testkit",
    "harness-conformance",
)
