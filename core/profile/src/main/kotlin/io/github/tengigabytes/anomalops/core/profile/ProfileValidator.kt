// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.profile

/** Consistency rules a device profile must satisfy beyond parsing (ADR-0003). Returns human-readable problems. */
object ProfileValidator {
    const val SUPPORTED_SCHEMA = 1
    private const val GAIN_COUNT = 4
    private const val MATRIX_SIZE = 9

    fun validate(profile: DeviceProfile): List<String> = buildList {
        if (profile.schemaVersion != SUPPORTED_SCHEMA) add("schemaVersion ${profile.schemaVersion} is not supported")
        addAll(cameraLinks(profile))
        addAll(ranges(profile))
        addAll(presets(profile))
        addAll(calibration(profile))
    }

    private fun cameraLinks(profile: DeviceProfile): List<String> = buildList {
        val logicalIds = profile.capabilities.logicalCameras.map { it.id }.toSet()
        profile.capabilities.physicalCameras
            .filter { it.logicalId !in logicalIds }
            .forEach { add("physical camera ${it.id}: unknown logical camera ${it.logicalId}") }
        profile.capabilities.logicalCameras.forEach { logical ->
            logical.physicalIds
                .filter { profile.physicalCamera(it) == null }
                .forEach { add("logical camera ${logical.id}: unknown physical camera $it") }
        }
    }

    private fun ranges(profile: DeviceProfile): List<String> = buildList {
        profile.capabilities.physicalCameras.forEach { camera ->
            if (!camera.isoRange.isAscendingPair()) add("physical camera ${camera.id}: bad isoRange")
            if (!camera.exposureRangeNs.isAscendingPair()) add("physical camera ${camera.id}: bad exposureRangeNs")
        }
    }

    /** Every preset must use an existing back camera with manual white balance (ADR-0002). */
    private fun presets(profile: DeviceProfile): List<String> = buildList {
        ScenePreset.entries.forEach { preset ->
            val id = profile.presetLenses.idFor(preset)
            val camera = profile.physicalCamera(id)
            when {
                camera == null -> add("preset $preset: unknown physical camera $id")
                camera.lens == LensKind.FRONT -> add("preset $preset: camera $id is a front camera")
                !camera.manualPostProcessing -> add("preset $preset: camera $id lacks MANUAL_POST_PROCESSING")
            }
        }
    }

    private fun calibration(profile: DeviceProfile): List<String> = buildList {
        profile.calibration.forEachIndexed { index, entry ->
            val where = "calibration[$index]"
            if (profile.physicalCamera(entry.physicalId) == null) add("$where: unknown physical camera")
            if (entry.gains.size != GAIN_COUNT || entry.gains.any { it <= 0.0 }) add("$where: need 4 positive gains")
            if (entry.colorMatrix.size != MATRIX_SIZE) add("$where: colorMatrix must have 9 values")
        }
    }

    private fun <T : Comparable<T>> List<T>.isAscendingPair() = size == 2 && this[0] <= this[1]
}
