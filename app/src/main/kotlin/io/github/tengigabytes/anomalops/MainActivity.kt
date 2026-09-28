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
import androidx.lifecycle.lifecycleScope
import io.github.tengigabytes.anomalops.capture.CameraPermissionGate
import io.github.tengigabytes.anomalops.capture.CaptureScreen
import io.github.tengigabytes.anomalops.capture.Message
import io.github.tengigabytes.anomalops.capture.ShotPipeline
import io.github.tengigabytes.anomalops.conditions.ShootingConditions
import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.profile.DeviceProfile
import io.github.tengigabytes.anomalops.core.profile.DeviceProfiles
import io.github.tengigabytes.anomalops.core.profile.ProfileValidator
import io.github.tengigabytes.anomalops.core.store.media.StillStore
import io.github.tengigabytes.anomalops.core.store.raw.DngStore
import io.github.tengigabytes.anomalops.core.store.raw.RawKeeper
import io.github.tengigabytes.anomalops.core.store.stack.BurstStacks
import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthZone
import io.github.tengigabytes.anomalops.core.telemetry.depth.ManualDepthSource

/** Single activity (ADR-0007). M1: camera screen only; the dive lock (ADR-0006) arrives in M3. */
class MainActivity : ComponentActivity() {
    private var controller: CameraController? = null
    private val rawKeeper by lazy { RawKeeper(DngStore(applicationContext), lifecycleScope) }
    private val stacks by lazy { BurstStacks(applicationContext) }

    // FR-84: v1.0 depth is the diver's manual zone; the M3 dive-lock screen gets the switch.
    private val depth by lazy { ManualDepthSource(initialZone()) }
    private val conditions by lazy { ShootingConditions(depth) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = loadProfile()?.let { CameraController(applicationContext, it) }
        setContent {
            MaterialTheme {
                val camera = controller
                if (camera == null) {
                    Message(stringResource(R.string.unsupported_device, Build.DEVICE))
                } else {
                    CameraPermissionGate {
                        CaptureScreen(
                            camera,
                            ShotPipeline(camera, StillStore(applicationContext), rawKeeper, stacks),
                            conditions,
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        // Held RAW frames return their camera buffers before the camera thread stops (ADR-0005).
        rawKeeper.clear()
        controller?.release()
        super.onDestroy()
    }

    /**
     * The starting depth zone. The switch arrives with the M3 dive-lock screen; until then debug builds accept
     * `--es depthBand DEEP` etc. to test the uncalibrated fallback (ADR-0002).
     */
    private fun initialZone(): DepthZone {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val requested = intent.getStringExtra(EXTRA_DEPTH_BAND)?.takeIf { debuggable } ?: return DepthZone.SHALLOW
        val zone = DepthZone.entries.firstOrNull { it.name == requested }
        if (zone == null) Log.w(TAG, "ignoring unknown depthBand $requested")
        return zone ?: DepthZone.SHALLOW
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
