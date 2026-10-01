// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NNAPI accelerators (ADR-0017 section 6). The NNAPI device API is NDK-only, so without native code this
 * reads what an app process can see from the shell: the runtime feature level property and the
 * `android.hardware.neuralnetworks.IDevice/<name>` HAL instances in the service list. Per-device feature
 * levels (ANeuralNetworksDevice_getFeatureLevel) are not available here.
 */
internal object NnapiProbe {
    private const val HAL_PREFIX = "android.hardware.neuralnetworks.IDevice/"
    private const val FEATURE_LEVEL_PROP = "persist.device_config.nnapi_native.current_feature_level"
    private const val TIMEOUT_S = 5L
    private const val ERROR_CHARS = 200

    fun probe(): JSONObject {
        val services = run("service", "list")
        val devices = services.getOrNull()?.lineSequence()
            ?.mapNotNull { line -> line.substringAfter(HAL_PREFIX, "").substringBefore(':').ifEmpty { null } }
            ?.toList()
        return jsonOf(
            "runtimeFeatureLevel" to run("getprop", FEATURE_LEVEL_PROP).getOrNull()?.trim()?.toIntOrNull(),
            "halDevices" to devices?.toJsonArray(),
            "serviceListError" to services.exceptionOrNull()?.toString(),
        )
    }

    private fun run(vararg command: String): Result<String> = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        // Outputs stay well below the 64 KiB pipe buffer, so waiting before reading cannot deadlock.
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroy()
            error("${command[0]} timed out")
        }
        val text = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0) { "${command[0]} exited ${process.exitValue()}: ${text.take(ERROR_CHARS)}" }
        text
    }
}
