// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Collects the full capability report (gate G0) and writes it to the app's private files directory:
 * `files/probe/<Build.DEVICE>-<timestamp>.json` and `files/probe/latest.json`.
 * Pull it with: `adb exec-out run-as io.github.tengigabytes.anomalops.probe cat files/probe/latest.json`.
 */
internal class ProbeReport(private val context: Context) {

    fun collectAndWrite(): String {
        val cameraThread = HandlerThread("probe-camera").apply { start() }
        try {
            val manager = context.getSystemService(CameraManager::class.java)
            val cameraGranted =
                context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val cameras = CameraProbe(manager).probeAll()
            val sessions = if (cameraGranted) sessions(manager, Handler(cameraThread.looper)) else JSONObject.NULL
            val report = jsonOf(
                "schema" to SCHEMA,
                "generatedAt" to OffsetDateTime.now().toString(),
                "device" to device(),
                "cameraPermissionGranted" to cameraGranted,
                "camera" to cameras,
                "sessionSupport" to sessions,
                "sensors" to SensorProbe.probe(context),
                "sensorSamples" to SensorSampler(context.getSystemService(SensorManager::class.java))
                    .sample(SAMPLE_MS, Handler(cameraThread.looper)),
            )
            val file = write(report)
            Log.i(TAG, "Probe report written to ${file.absolutePath}")
            return summary(report, file)
        } finally {
            cameraThread.quitSafely()
        }
    }

    private fun sessions(manager: CameraManager, handler: Handler): JSONObject {
        val probe = SessionProbe(manager, handler)
        return JSONObject().apply { manager.cameraIdList.forEach { put(it, probe.probe(it)) } }
    }

    private fun device() = jsonOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "device" to Build.DEVICE,
        "product" to Build.PRODUCT,
        "socManufacturer" to Build.SOC_MANUFACTURER,
        "socModel" to Build.SOC_MODEL,
        "release" to Build.VERSION.RELEASE,
        "sdkInt" to Build.VERSION.SDK_INT,
        "sdkIntFull" to Build.VERSION.SDK_INT_FULL,
        "buildId" to Build.ID,
        "securityPatch" to Build.VERSION.SECURITY_PATCH,
    )

    private fun write(report: JSONObject): File {
        val dir = File(context.filesDir, "probe").apply { mkdirs() }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val text = report.toString(2)
        File(dir, "latest.json").writeText(text)
        return File(dir, "${Build.DEVICE}-$stamp.json").apply { writeText(text) }
    }

    private fun summary(report: JSONObject, file: File): String = buildString {
        appendLine("Wrote ${file.name} (${file.length() / 1024} KiB)")
        val cameras = report.getJSONObject("camera").getJSONArray("cameras")
        for (i in 0 until cameras.length()) {
            val cam = cameras.getJSONObject(i)
            appendLine("camera ${cam.getString("id")} ${cam.optString("facing")} ${cam.optString("hardwareLevel")}")
            appendLine("  physical: ${cam.getJSONArray("physicalCameras").length()}")
        }
        val sessions = report.opt("sessionSupport")
        if (sessions is JSONObject) {
            sessions.keys().forEach { id ->
                val results = sessions.getJSONArray(id)
                val ok = (0 until results.length()).count { results.getJSONObject(it).optBoolean("supported") }
                appendLine("sessions camera $id: $ok / ${results.length()} combos supported")
            }
        } else {
            appendLine("sessions: skipped (CAMERA permission not granted)")
        }
        val samples = report.getJSONArray("sensorSamples")
        for (i in 0 until samples.length()) {
            val s = samples.getJSONObject(i)
            appendLine("${s.getString("stringType").substringAfterLast('.')}: ${s.getInt("events")} events, mean ${s.get("mean")}")
        }
    }

    private companion object {
        const val SCHEMA = "anomalops-probe/3"
        const val SAMPLE_MS = 5_000L
        const val TAG = "AnomalopsProbe"
    }
}
