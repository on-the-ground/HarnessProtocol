plugins { kotlin("jvm"); kotlin("plugin.serialization") }
dependencies {
    testImplementation(project(":harness-codex"))
    testImplementation(project(":harness-gemini-cli"))
    testImplementation(project(":harness-koog"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
}
kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
}
java { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
tasks.test {
    useJUnitPlatform()
    enabled = providers.gradleProperty("nativeHarnessTests").isPresent
    systemProperty("ahp.repository", rootProject.projectDir.absolutePath)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}
