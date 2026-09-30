// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Anomalops"

// Module layout and dependency rules: docs/adr/0007-stack-and-modules.md
include(":app")
include(":core:camera", ":core:imaging", ":core:profile", ":core:store", ":core:telemetry")
include(":tools:probe")
