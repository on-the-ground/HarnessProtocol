plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":harness-protocol"))
    api(project(":harness-codex"))
    api(project(":harness-gemini-cli"))

    testImplementation(kotlin("test"))
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
