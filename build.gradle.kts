import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
}

val artifactDescriptions = mapOf(
    "harness-protocol" to "Provider-neutral Kotlin ports for stateful agent harnesses",
    "harness-process-bridge" to "Internal process transport for Harness Protocol SDK adapters",
    "harness-codex" to "Codex SDK adapter for Harness Protocol",
    "harness-gemini-cli" to "Gemini CLI SDK adapter for Harness Protocol",
    "harness-bundle" to "Single dependency containing Harness Protocol and its SDK adapters",
)

val publicationGroup = providers.gradleProperty("publicationGroup").orElse("dev.harnessprotocol")
val publicationVersion = providers.gradleProperty("publicationVersion").orElse("0.1.0-SNAPSHOT")

allprojects {
    group = publicationGroup.get()
    version = publicationVersion.get()

    // OneDrive can transiently lock Kotlin's incremental outputs on Windows.
    // Build products are disposable, so keep them in the OS temporary directory.
    layout.buildDirectory.set(
        file(System.getProperty("java.io.tmpdir"))
            .resolve("harness-protocol-build")
            .resolve(if (this == rootProject) "root" else name),
    )
}

subprojects {
    pluginManager.withPlugin("java-library") {
        pluginManager.apply("maven-publish")

        extensions.configure<JavaPluginExtension> {
            withSourcesJar()
        }

        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("mavenJava") {
                from(components["java"])
                pom {
                    name.set(project.name)
                    description.set(artifactDescriptions[project.name])
                }
            }
            repositories {
                maven {
                    name = "buildRepository"
                    url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// SDK host tests (Python + Node). `check` runs them; interpreters that are
// missing are skipped locally with a warning, but `-PstrictHostTests` (used by
// CI / release verification) turns a missing interpreter into a failure.
// ---------------------------------------------------------------------------
val strictHostTests = providers.gradleProperty("strictHostTests").isPresent

fun interpreter(envVar: String, vararg candidates: String): String? {
    val configured = System.getenv(envVar)?.takeIf { it.isNotBlank() }
    return (listOfNotNull(configured) + candidates).firstOrNull { candidate ->
        runCatching { ProcessBuilder(candidate, "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
    }
}

fun registerHostTest(name: String, envVar: String, candidates: List<String>, args: (String) -> List<String>) {
    tasks.register<Exec>(name) {
        group = "verification"
        description = "Runs the $name SDK host tests"
        workingDir = rootProject.file("bridges")
        val found = interpreter(envVar, *candidates.toTypedArray())
        if (found == null) {
            if (strictHostTests) throw GradleException("$name: no interpreter found (tried ${candidates.joinToString()}); set $envVar")
            enabled = false
            doFirst { logger.warn("$name skipped: no interpreter found") }
        } else {
            commandLine(args(found))
        }
    }
}

registerHostTest("codexHostTests", "HARNESS_CODEX_PYTHON", listOf("python3", "python")) { py ->
    listOf(py, "-m", "pytest", "-q", "tests/test_codex_bridge.py")
}
registerHostTest("geminiHostTests", "HARNESS_GEMINI_NODE", listOf("node")) { node ->
    listOf(node, "--test", "tests/gemini_bridge.test.mjs")
}

tasks.register("hostTests") {
    group = "verification"
    dependsOn("codexHostTests", "geminiHostTests")
}
