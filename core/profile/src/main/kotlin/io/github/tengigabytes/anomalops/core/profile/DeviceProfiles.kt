// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.profile

import kotlinx.serialization.json.Json

/** Loads device profiles packaged as classpath resources under `device-profiles/` (ADR-0003). */
object DeviceProfiles {
    const val RESOURCE_DIR = "device-profiles"

    // Strict on purpose: an unknown key means the file and the schema have drifted apart.
    private val json = Json { ignoreUnknownKeys = false }

    fun parse(text: String): DeviceProfile = json.decodeFromString(DeviceProfile.serializer(), text)

    /** The profile for `Build.DEVICE`, or null when this model has no profile yet. */
    fun load(buildDevice: String): DeviceProfile? {
        val stream = DeviceProfiles::class.java.classLoader
            ?.getResourceAsStream("$RESOURCE_DIR/$buildDevice.json")
            ?: return null
        return stream.bufferedReader().use { parse(it.readText()) }
    }
}
