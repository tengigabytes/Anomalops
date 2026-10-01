// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject

/** GPU-related system features (ADR-0017): declared GLES version, Vulkan version and level, dEQP levels. */
internal object GpuFeatureProbe {
    private const val GLES_MAJOR_SHIFT = 16
    private const val GLES_MINOR_MASK = 0xffff
    private const val VK_MAJOR_SHIFT = 22
    private const val VK_MINOR_SHIFT = 12
    private const val VK_MINOR_MASK = 0x3ff
    private const val VK_PATCH_MASK = 0xfff
    private const val DATE_YEAR_SHIFT = 16
    private const val DATE_MONTH_SHIFT = 8
    private const val DATE_PART_MASK = 0xff

    fun probe(context: Context): JSONObject {
        val pm = context.packageManager
        val features = pm.systemAvailableFeatures.filter { it.name != null }.associate { it.name to it.version }
        val glEs = context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion
        val vulkan = features[PackageManager.FEATURE_VULKAN_HARDWARE_VERSION]
        return jsonOf(
            "reqGlEsVersion" to "${glEs shr GLES_MAJOR_SHIFT}.${glEs and GLES_MINOR_MASK}",
            "openGlEsExtensionPack" to pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK),
            "openGlEsDeqpLevel" to features[PackageManager.FEATURE_OPENGLES_DEQP_LEVEL]?.let(::deqpDate),
            "vulkanVersion" to vulkan?.let(::vulkanVersion),
            "vulkanVersionRaw" to vulkan,
            "vulkanLevel" to features[PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL],
            "vulkanCompute" to features[PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE],
            "vulkanDeqpLevel" to features[PackageManager.FEATURE_VULKAN_DEQP_LEVEL]?.let(::deqpDate),
        )
    }

    /** VK_MAKE_API_VERSION packing: major 10 bits, minor 10 bits, patch 12 bits. */
    private fun vulkanVersion(v: Int) =
        "${v ushr VK_MAJOR_SHIFT}.${(v shr VK_MINOR_SHIFT) and VK_MINOR_MASK}.${v and VK_PATCH_MASK}"

    /** dEQP levels are dates packed as 0xYYYYMMDD in binary (year 16 bits, month and day 8 bits each). */
    private fun deqpDate(v: Int) = "%04d-%02d-%02d".format(
        v ushr DATE_YEAR_SHIFT,
        (v shr DATE_MONTH_SHIFT) and DATE_PART_MASK,
        v and DATE_PART_MASK,
    )
}
