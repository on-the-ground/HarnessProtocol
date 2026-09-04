plugins {
    kotlin("jvm")
    `java-library`
}
dependencies {
    api(project(":harness-protocol"))
    implementation(project(":harness-runtime"))
    api("ai.koog:agents-core:1.2.0")
    implementation("ai.koog:agents-features-event-handler:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
tasks.test { useJUnitPlatform() }
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
