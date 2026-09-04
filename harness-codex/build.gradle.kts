plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

dependencies {
    api(project(":harness-protocol"))
    api(project(":harness-process-bridge"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    testImplementation(project(":harness-adapter-testkit"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    from(rootProject.file("bridges/codex_sdk_bridge.py")) {
        into("dev/harnessprotocol/codex")
    }
    from(rootProject.file("bridges/requirements-codex.txt")) {
        into("dev/harnessprotocol/codex")
    }
}
