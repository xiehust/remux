plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin/JVM module — NO Android dependencies. Holds the terminal VT
// parser, ssh_config/tmux parsing, tunnel-protocol client, and the AI-command
// catalog, all unit-testable on the JVM without an emulator.
//
// Target JVM 17 bytecode (compiled by the JDK 21 toolchain) so the Android :app
// module, which targets 17, can consume it. We deliberately avoid jvmToolchain()
// to not require provisioning a separate JDK.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test> {
    testLogging {
        events("passed", "failed", "skipped")
    }
}
