// 공개 Port의 적합성 검사와 그 fixture seam. 구현과 독립적으로 작성하며 발행하지 않는다.
plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":harness-protocol"))
    // kotlin("test")를 별도로 더하지 않는다: kotlin-test-junit5가 이미 kotlin-test를 함께 끌어오며,
    // 둘을 같이 선언하면 kotlin-test-framework-impl capability 충돌로 해석이 실패한다.
    api(kotlin("test-junit5"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
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
