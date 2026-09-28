// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.graphics.ImageFormat
import android.hardware.camera2.CameraExtensionCharacteristics
import android.hardware.camera2.CameraMetadata
import java.lang.reflect.Modifier

/**
 * Maps Camera2 integer constants to their public names by reflection, so the probe
 * keeps working when a new API level adds values (no hand-written tables to maintain).
 */
internal object ConstantNames {
    val capabilities by lazy { table(CameraMetadata::class.java, "REQUEST_AVAILABLE_CAPABILITIES_") }
    val hardwareLevels by lazy { table(CameraMetadata::class.java, "INFO_SUPPORTED_HARDWARE_LEVEL_") }
    val lensFacing by lazy { table(CameraMetadata::class.java, "LENS_FACING_") }
    val afModes by lazy { table(CameraMetadata::class.java, "CONTROL_AF_MODE_") }
    val aeModes by lazy { table(CameraMetadata::class.java, "CONTROL_AE_MODE_") }
    val awbModes by lazy { table(CameraMetadata::class.java, "CONTROL_AWB_MODE_") }
    val oisModes by lazy { table(CameraMetadata::class.java, "LENS_OPTICAL_STABILIZATION_MODE_") }
    val timestampSources by lazy { table(CameraMetadata::class.java, "SENSOR_INFO_TIMESTAMP_SOURCE_") }
    val focusCalibrations by lazy { table(CameraMetadata::class.java, "LENS_INFO_FOCUS_DISTANCE_CALIBRATION_") }
    val extensions by lazy { table(CameraExtensionCharacteristics::class.java, "EXTENSION_") }
    private val imageFormats by lazy { table(ImageFormat::class.java, "") }

    fun name(table: Map<Int, String>, value: Int?): String? = value?.let { table[it] ?: it.toString() }

    fun names(table: Map<Int, String>, values: IntArray?): List<String> =
        values?.map { table[it] ?: it.toString() }.orEmpty()

    fun format(code: Int): String = imageFormats[code] ?: "0x%x".format(code)

    private fun table(owner: Class<*>, prefix: String): Map<Int, String> = owner.fields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        .filter { it.name.startsWith(prefix) }
        .associate { it.getInt(null) to it.name.removePrefix(prefix) }
}
