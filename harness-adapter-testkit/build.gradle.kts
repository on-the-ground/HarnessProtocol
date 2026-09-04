// Test-only module shared by the SDK adapters. It is deliberately not a `java-library`
// so the root publishing convention does not pick it up; it is never published.
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":harness-protocol"))
    api(project(":harness-process-bridge"))
    // kotlin("test")를 별도로 더하지 않는다: kotlin-test-junit5가 이미 kotlin-test를 함께 끌어오며,
    // 둘을 같이 선언하면 kotlin-test-framework-impl capability 충돌로 해석이 실패한다.
    api(kotlin("test-junit5"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
