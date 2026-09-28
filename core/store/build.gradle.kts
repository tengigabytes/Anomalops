// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.tengigabytes.anomalops.core.store"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:camera"))
    implementation(libs.kotlinx.coroutines.android)
    // ADR-0007: burst stack index (FR-68) in Room; the schema is exported for review and migrations.
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    testImplementation(libs.junit)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
