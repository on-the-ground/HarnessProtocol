plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
}

// Independent experiment: no publication and no dependency from the production bundle.
// Match the repository's policy: avoid OneDrive/Unicode paths for JVM build outputs.
layout.buildDirectory.set(file(System.getProperty("java.io.tmpdir")).resolve("harness-protocol-koog-validation"))

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
dependencies {
    implementation("dev.harnessprotocol:harness-protocol:0.1.0")
    implementation("dev.harnessprotocol:harness-runtime:0.1.0")
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
