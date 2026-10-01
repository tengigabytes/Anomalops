// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM module: multi-frame image processing with no Android dependency, so its tests run anywhere
// (ADR-0016).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

// T12's desktop runner (src/test/.../tool/StackTool.kt): DNGs from the phone through the three FR-33 candidates.
// gradlew :core:imaging:stackTool --args="<out dir> <a.dng> <b.dng> ... [--full] [--lowlight]"
tasks.register<JavaExec>("stackTool") {
    description = "Merges DNGs with each focus-stacking candidate and writes PNGs (T12)."
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.tengigabytes.anomalops.core.imaging.tool.StackToolKt")
    maxHeapSize = "8g"
}
