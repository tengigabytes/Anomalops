// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.res.stringResource
import io.github.tengigabytes.anomalops.capture.CameraPermissionGate
import io.github.tengigabytes.anomalops.capture.CaptureScreen
import io.github.tengigabytes.anomalops.capture.Message
import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.profile.DeviceProfile
import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import io.github.tengigabytes.anomalops.core.profile.ProfileValidator

/** Single activity (ADR-0007). M1: camera screen only; the dive lock (ADR-0006) arrives in M3. */
class MainActivity : ComponentActivity() {
    private var controller: CameraController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = loadProfile()?.let { CameraController(applicationContext, it) }
        setContent {
            MaterialTheme {
                val camera = controller
                if (camera == null) {
                    Message(stringResource(R.string.unsupported_device, Build.DEVICE))
                } else {
                    CameraPermissionGate { CaptureScreen(camera) }
                }
            }
        }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }

    /** NFR-9: everything model-specific comes from the device profile; no profile means no camera. */
    private fun loadProfile(): DeviceProfile? {
        val profile = DeviceProfiles.load(Build.DEVICE) ?: return null
        val problems = ProfileValidator.validate(profile)
        problems.forEach { Log.e(TAG, "device profile ${Build.DEVICE}: $it") }
        return profile.takeIf { problems.isEmpty() }
    }

    private companion object {
        const val TAG = "Anomalops"
    }
}
