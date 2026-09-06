pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        val publishedRepository = providers.gradleProperty("harnessRepository").orNull
        if (publishedRepository == null) {
            mavenLocal()
        } else {
            maven { url = uri(publishedRepository) }
        }
        mavenCentral()
    }
}

rootProject.name = "harness-protocol-basic-sample"

// Verify the current source without requiring a published 0.2.0 artifact.
if (providers.gradleProperty("useProjectSource").isPresent) {
    includeBuild("../..") {
        dependencySubstitution {
            substitute(module("io.github.joohyung-park:harness-bundle")).using(project(":harness-bundle"))
        }
    }
}
