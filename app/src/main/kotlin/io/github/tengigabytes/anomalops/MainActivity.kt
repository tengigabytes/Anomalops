// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops

import android.content.pm.ApplicationInfo
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
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.DeviceProfile
import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.profile.ProfileValidator
import io.github.tengigabytes.anomalops.core.store.media.StillStore

/** Single activity (ADR-0007). M1: camera screen only; the dive lock (ADR-0006) arrives in M3. */
class MainActivity : ComponentActivity() {
    private var controller: CameraController? = null
    private val store by lazy { StillStore(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = loadProfile()?.let { CameraController(applicationContext, it) }
        setContent {
            MaterialTheme {
                val camera = controller
                if (camera == null) {
                    Message(stringResource(R.string.unsupported_device, Build.DEVICE))
                } else {
                    CameraPermissionGate { CaptureScreen(camera, store, conditions()) }
                }
            }
        }
    }

    override fun onDestroy() {
        controller?.release()
        super.onDestroy()
    }

    /**
     * The calibration conditions. M1 has no depth, filter or dive-light switches yet (M3, M4), so they are fixed;
     * debug builds accept `--es depthBand DEEP` etc. to test the uncalibrated fallback (ADR-0002).
     */
    private fun conditions(): CalibrationKey {
        val fixed = CalibrationKey(DepthBand.SHALLOW, LensFilter.NONE, diveLight = false)
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val requested = intent.getStringExtra(EXTRA_DEPTH_BAND)?.takeIf { debuggable } ?: return fixed
        val band = DepthBand.entries.firstOrNull { it.name == requested }
        if (band == null) Log.w(TAG, "ignoring unknown depthBand $requested")
        return fixed.copy(depthBand = band ?: fixed.depthBand)
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
        const val EXTRA_DEPTH_BAND = "depthBand"
    }
}
