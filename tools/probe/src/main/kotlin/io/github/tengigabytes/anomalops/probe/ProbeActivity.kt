// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * Placeholder for the capability probe (ADR-0003, gate G0).
 * The next M0 step replaces this with the CameraCharacteristics / sensor dump.
 */
class ProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Anomalops capability probe (not implemented yet)" })
    }
}
