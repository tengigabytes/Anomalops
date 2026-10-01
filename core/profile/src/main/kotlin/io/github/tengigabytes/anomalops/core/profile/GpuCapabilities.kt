// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.core.profile

import kotlinx.serialization.Serializable

/**
 * What the multi-frame GPU path can rely on (ADR-0017), from the probe's compute-shader tests. Format names are
 * GL sized formats without the `GL_` prefix (e.g. `RGBA16F`). `halfRounding` is how 32-bit results are rounded
 * when stored as half floats (`NEAREST_EVEN`, `TOWARD_ZERO`), or null if neither matched.
 */
@Serializable
data class GpuCapabilities(
    val renderer: String,
    val glesVersion: String,
    val vulkanVersion: String?,
    val maxTextureSize: Int,
    val maxComputeWorkGroupSize: List<Int>,
    val maxComputeWorkGroupInvocations: Int,
    val maxComputeSharedMemoryBytes: Int,
    val maxComputeImageUniforms: Int,
    val mediumpFloatBits: Int,
    val imageStoreFormats: List<String>,
    val imageReadWriteFormats: List<String>,
    val colorRenderableFormats: List<String>,
    val halfRounding: String?,
    val r16uiUpload: Boolean,
    val extensions: Map<String, Boolean>,
)

/** NNAPI as seen without native code (ADR-0017 section 6): runtime feature level and HAL device names. */
@Serializable
data class NnapiCapabilities(val runtimeFeatureLevel: Int?, val halDevices: List<String>)
