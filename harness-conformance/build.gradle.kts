// Native adapter 검사용 seam과 재사용 시나리오. 임시 Port 구현을 제공하거나 발행하지 않는다.
plugins {
    kotlin("jvm")
    `java-test-fixtures`
}

dependencies {
    api(project(":harness-protocol"))
    // kotlin("test")를 별도로 더하지 않는다: kotlin-test-junit5가 이미 kotlin-test를 함께 끌어오며,
    // 둘을 같이 선언하면 kotlin-test-framework-impl capability 충돌로 해석이 실패한다.
    testFixturesApi(kotlin("test-junit5"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    testFixturesApi("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
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

// Keep unattached scenarios type-checked; compiling them is not a conformance pass.
tasks.named("check") { dependsOn(tasks.named("testFixturesClasses")) }
