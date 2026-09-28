// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

/**
 * Capability probe (ADR-0003, gate G0). Runs once on launch, writes a JSON report and shows a summary.
 * Nothing is captured or saved to the gallery; the camera is only opened to query session support.
 */
class ProbeActivity : Activity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply {
            setTextIsSelectable(true)
            setPadding(PADDING, PADDING, PADDING, PADDING)
        }
        setContentView(ScrollView(this).apply { addView(output) })
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            runProbe()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Run even when denied: the report records the missing permission and skips session queries.
        if (requestCode == REQUEST_CAMERA) runProbe()
    }

    private fun runProbe() {
        output.text = "Probing…"
        thread(name = "probe") {
            val text = runCatching { ProbeReport(applicationContext).collectAndWrite() }
                .getOrElse { "Probe failed:\n${it.stackTraceToString()}" }
            runOnUiThread { output.text = text }
        }
    }

    private companion object {
        const val REQUEST_CAMERA = 1
        const val PADDING = 32
    }
}
