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

project(":harness-protocol").projectDir = file("protocol/core")
project(":harness-conformance").projectDir = file("protocol/conformance")

project(":harness-runtime").projectDir = file("implementations/shared/runtime")
project(":harness-process-bridge").projectDir = file("implementations/shared/process-bridge")
project(":harness-codex").projectDir = file("implementations/codex")
project(":harness-gemini-cli").projectDir = file("implementations/gemini-cli")
project(":harness-koog").projectDir = file("implementations/koog")
project(":harness-bundle").projectDir = file("implementations/bundle")

project(":harness-adapter-testkit").projectDir = file("verification/adapter-testkit")
project(":harness-native-integration").projectDir = file("verification/native-integration")
