// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later

// Multi-frame work on the GPU with OpenGL ES 3.1 compute shaders (ADR-0017); :core:imaging is the CPU reference
// every shader is checked against.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.tengigabytes.anomalops.core.gpu"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:imaging"))

    // Shaders run on the phone's GPU only: every check against the CPU reference is an on-device test.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
