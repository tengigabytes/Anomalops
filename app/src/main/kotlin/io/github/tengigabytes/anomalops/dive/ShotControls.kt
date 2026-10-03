// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.dive

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.tengigabytes.anomalops.R
import io.github.tengigabytes.anomalops.capture.ShootingMode
import io.github.tengigabytes.anomalops.capture.ShotPipeline
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.launch
import java.io.IOException

private const val TAG = "DiveScreen"
private const val MS_PER_S = 1000f

/**
 * Shooting from the dive screen: mode and merge switch (FR-11 / FR-12, FR-17), stills and bursts (FR-52, FR-15), RAW keeps (FR-62), the
 * short status note, and the FR-45 `captures.csv` rows.
 */
class ShotControls(
    private val deps: DiveDeps,
    private val actions: DiveActions,
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var mode by mutableStateOf(ShootingMode.AUTO)
        private set

    /** FR-17: whether shots are merged bursts; the switch on the dive screen, kept across launches. */
    var merge by mutableStateOf(deps.merge.on)
        private set

    /** The FR-11 preset the camera runs: the mode's, which for [ShootingMode.AUTO] depends on [merge]. */
    val preset: ScenePreset get() = mode.preset(merge)
    var shot by mutableStateOf<ShotPipeline.Shot?>(null)
        private set
    var note by mutableStateOf<String?>(null)

    fun launchSafely(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "camera call failed", e)
            } catch (e: IOException) {
                Log.w(TAG, "saving failed", e)
            }
        }
    }

    fun select(chosen: ShootingMode, key: CalibrationKey) {
        mode = chosen
        launchSafely { deps.controller.select(preset, key) }
    }

    fun toggleMerge(key: CalibrationKey) {
        merge = !merge
        deps.merge.on = merge
        launchSafely { deps.controller.select(preset, key) }
    }

    fun shoot() = launchSafely {
        val taken = deps.pipeline.shoot()
        log(taken)
        shot = taken
        actions.session()?.capture(stillRecord(taken, deps.conditions.current))
        if (taken.capture.spec.exposure?.isoClamped == true) note = context.getString(R.string.iso_clamped)
        taken.merge?.let { note = mergeNote(it) }
    }

    fun burst(release: Deferred<Unit>) = launchSafely {
        val burst = deps.pipeline.burst(until = { release.await() })
        Log.i(TAG, "burst stem=${burst.stem} frames=${burst.frames} saved=${burst.saved.size}")
        actions.session()?.capture(burstRecord(burst, preset, deps.conditions.current))
    }

    fun keepRaw() {
        val stem = shot?.saved?.stem ?: return
        launchSafely {
            val kept = deps.pipeline.keepRaw(stem)?.displayName ?: context.getString(R.string.raw_gone)
            note = context.getString(R.string.raw_note, kept)
        }
    }

    /** FR-17 (developer switch): how many frames were merged and how long the shot took, or that it fell back. */
    private fun mergeNote(merge: ShotPipeline.Merge): String {
        val totalMs = merge.captureMs + merge.mergeMs + merge.encodeMs
        Log.i(
            TAG,
            "FR-17 merged=${merge.merged} frames=${merge.frames} capture=${merge.captureMs} ms " +
                "merge=${merge.mergeMs} ms encode=${merge.encodeMs} ms",
        )
        if (!merge.merged) return context.getString(R.string.merge_fell_back)
        return context.getString(R.string.merge_done, merge.frames, totalMs / MS_PER_S)
    }

    private fun log(shot: ShotPipeline.Shot) {
        val c = shot.capture
        Log.i(
            TAG,
            "still preset=${c.spec.preset} lens=${c.spec.physicalId} format=${c.format} bytes=${c.bytes.size} " +
                "exposure=${c.spec.exposure} flashFired=${c.flashFired} color=${c.spec.color} " +
                "focus=${c.spec.focus} reported=${c.reported} saved=${shot.saved.displayName}",
        )
    }
}
