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
