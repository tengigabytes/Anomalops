// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin/JVM module: no Android dependency, so its tests run anywhere (ADR-0003, ADR-0007).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// ADR-0003: device profiles live in assets/device-profiles/ and ship as classpath resources.
sourceSets {
    main {
        resources.srcDir(rootProject.file("assets"))
        resources.exclude("**/*.md")
    }
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
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
