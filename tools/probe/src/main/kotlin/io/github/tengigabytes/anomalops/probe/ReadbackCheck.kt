// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.opengl.GLES20
import android.util.Half
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

/** Compares a [GlCompute.readBack] of float texels with the test values of [GlCompute.expected]. */
internal object ReadbackCheck {
    /** How the GPU converts 32-bit results to the storage format; all test values are positive. */
    private enum class Rounding(val round: (Float) -> Float) {
        EXACT({ it }),
        NEAREST_EVEN({ Half.toFloat(Half.toHalf(it)) }),
        TOWARD_ZERO(
            { x -> Half.toHalf(x).let { h -> Half.toFloat(if (Half.toFloat(h) > x) (h - 1).toShort() else h) } },
        ),
    }

    /**
     * Errors against the exact sum, the mean signed error (a rounding bias would shift averaged frames), and which
     * [Rounding] reproduces every texel when applied after each pass (`rounding` is null when none does).
     */
    fun compare(bytes: ByteBuffer, half: Boolean, passes: Int): JSONObject {
        val error = GLES20.glGetError()
        val values = bytes.asFloatBuffer()
        val candidates = if (half) listOf(Rounding.NEAREST_EVEN, Rounding.TOWARD_ZERO) else listOf(Rounding.EXACT)
        val matching = candidates.toMutableSet()
        var maxAbs = 0f
        var maxRel = 0f
        var sum = 0.0
        for (i in 0 until GlCompute.COUNT) {
            val value = GlCompute.expected(i)
            val got = values.get(i)
            matching.removeAll { mode -> (1..passes).fold(0f) { stored, _ -> mode.round(stored + value) } != got }
            val exact = value * passes
            maxAbs = max(maxAbs, abs(got - exact))
            maxRel = max(maxRel, abs(got - exact) / exact)
            sum += got - exact
        }
        return jsonOf(
            "compiled" to true,
            "glError" to GlCompute.hex(error),
            "maxAbsError" to maxAbs.finiteOrString(),
            "maxRelError" to maxRel.finiteOrString(),
            "meanError" to sum / GlCompute.COUNT,
            "rounding" to candidates.firstOrNull { it in matching }?.name,
        )
    }
}
