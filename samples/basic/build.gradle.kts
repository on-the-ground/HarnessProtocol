plugins {
    kotlin("jvm") version "2.2.0"
    application
}

dependencies {
    implementation("dev.harnessprotocol:harness-bundle:0.1.0-SNAPSHOT")
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

application {
    mainClass.set("sample.MainKt")
}

layout.buildDirectory.set(
    file(System.getProperty("java.io.tmpdir"))
        .resolve("harness-protocol-build")
        .resolve("basic-sample"),
)
