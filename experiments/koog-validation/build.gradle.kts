plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
}

// Independent experiment: no publication and no dependency from the production bundle.
// Match the repository's policy: avoid OneDrive/Unicode paths for JVM build outputs.
layout.buildDirectory.set(file(System.getProperty("java.io.tmpdir")).resolve("harness-protocol-koog-validation"))

// Keep this historical experiment reproducible after the production port and legacy package change.
// The revision is the baseline recorded in evidence/verification.json; never silently use HEAD.
val protocolBaseline = "d5733031a2533dfd0d56821d912442a08552b0fd"
val baselineSources = layout.buildDirectory.dir("baseline-protocol")
val prepareBaselineProtocol = tasks.register("prepareBaselineProtocol") {
    inputs.property("protocolRevision", protocolBaseline)
    outputs.dir(baselineSources)
    doLast {
        val repository = rootDir.resolve("../..").canonicalFile
        val prefix = "harness-protocol/src/main/kotlin/"
        fun gitText(vararg args: String): String = providers.exec {
            workingDir(repository)
            commandLine(listOf("git") + args)
        }.standardOutput.asText.get()
        val paths = gitText("ls-tree", "-r", "--name-only", protocolBaseline, "--", prefix)
            .lineSequence().filter { it.startsWith(prefix) && it.endsWith(".kt") }.toList()
        check(paths.isNotEmpty()) { "Pinned protocol revision is missing; fetch $protocolBaseline before reproducing." }
        paths.forEach { path ->
            val target = baselineSources.get().file(path.removePrefix(prefix)).asFile
            target.parentFile.mkdirs()
            target.writeText(gitText("show", "$protocolBaseline:$path"), Charsets.UTF_8)
        }
    }
}
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    sourceSets.main { kotlin.srcDir(baselineSources) }
}
tasks.named("compileKotlin") { dependsOn(prepareBaselineProtocol) }
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
dependencies {
    implementation("ai.koog:agents-core:1.2.0")
    implementation("ai.koog:agents-features-event-handler:1.2.0")
    implementation("ai.koog:agents-features-snapshot:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}
dependencyLocking { lockAllConfigurations() }
tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
tasks.register("resolveRuntime") {
    doLast { configurations.runtimeClasspath.get().files.sorted().forEach { println(it.name) } }
}
