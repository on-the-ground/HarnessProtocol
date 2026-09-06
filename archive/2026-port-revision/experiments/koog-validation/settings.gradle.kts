pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "koog-abstraction-validation"

// Exercise the current public Port and production task lifecycle, never extracted historical sources.
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("dev.harnessprotocol:harness-protocol")).using(project(":harness-protocol"))
        substitute(module("dev.harnessprotocol:harness-runtime")).using(project(":harness-runtime"))
    }
}
