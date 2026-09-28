// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tengigabytes.anomalops.R
import io.github.tengigabytes.anomalops.core.camera.session.CameraController
import io.github.tengigabytes.anomalops.core.camera.session.CameraState
import io.github.tengigabytes.anomalops.core.camera.session.CameraStatus
import io.github.tengigabytes.anomalops.core.camera.session.StillCapture
import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.ScenePreset
import io.github.tengigabytes.anomalops.core.store.media.SavedStill
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.math.roundToLong

private const val TAG = "Capture"
private const val NS_PER_SECOND = 1e9
private const val BYTES_PER_MB = 1_048_576.0
private val SHUTTER_SIZE = 96.dp
private const val STATUS_LINES = 2

/**
 * Test screen: preview, preset switch (FR-11), shutter, and the latest-shot thumbnail whose long press keeps the
 * RAW frame (FR-62). Stills go to MediaStore (ADR-0004).
 */
@Composable
fun CaptureScreen(controller: CameraController, pipeline: ShotPipeline, conditions: CalibrationKey) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    var preset by rememberSaveable { mutableStateOf(ScenePreset.SNAPSHOT) }
    var lastShot by remember { mutableStateOf<StillCapture?>(null) }
    var lastSaved by remember { mutableStateOf<SavedStill?>(null) }
    var rawNote by remember { mutableStateOf<String?>(null) }
    val rawGone = stringResource(R.string.raw_gone)
    fun run(action: suspend () -> Unit) {
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
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CameraPreview(
            onSurfaceReady = { surface -> run { controller.start(surface, preset, conditions) } },
            onSurfaceGone = controller::stopBlocking,
            modifier = Modifier.fillMaxWidth().aspectRatio(PREVIEW_ASPECT),
        )
        StatusLine(state, lastShot, lastSaved, rawNote)
        PresetBar(selected = preset) { chosen ->
            preset = chosen
            run { controller.select(chosen, conditions) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            LatestThumbnail(lastShot?.bytes, onLongPress = {
                val stem = lastSaved?.stem ?: return@LatestThumbnail
                run { rawNote = pipeline.keepRaw(stem)?.also { Log.i(TAG, "kept $it") }?.displayName ?: rawGone }
            })
            Button(
                onClick = {
                    run {
                        val shot = pipeline.shoot()
                        log(shot.capture)
                        Log.i(TAG, "saved ${shot.saved}")
                        lastShot = shot.capture
                        lastSaved = shot.saved
                        rawNote = null
                    }
                },
                modifier = Modifier.size(SHUTTER_SIZE),
            ) {
                Text(stringResource(R.string.shutter))
            }
            Spacer(Modifier.size(THUMBNAIL_SIZE))
        }
    }
}

// Portrait screen, 4:3 sensor: the preview is 3 wide by 4 high.
private const val PREVIEW_ASPECT = 3f / 4f

@Composable
private fun PresetBar(selected: ScenePreset, onSelect: (ScenePreset) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        ScenePreset.entries.forEach { preset ->
            FilterChip(
                selected = preset == selected,
                onClick = { onSelect(preset) },
                label = { Text(stringResource(preset.label())) },
            )
        }
    }
}

@Composable
private fun StatusLine(state: CameraState, lastShot: StillCapture?, lastSaved: SavedStill?, rawNote: String?) {
    val parts = buildList {
        state.physicalId?.let { add(stringResource(R.string.status_lens, it)) }
        if (state.colorApproximate) add(stringResource(R.string.wb_approximate))
        if (state.status == CameraStatus.FAILED) add(stringResource(R.string.camera_failed, state.error.orEmpty()))
        lastShot?.let { add(describe(it)) }
        if (lastShot?.spec?.exposure?.isoClamped == true) add(stringResource(R.string.iso_clamped))
        if (lastShot?.flashFired == true) add(stringResource(R.string.flash_fired))
        lastSaved?.let { add(stringResource(R.string.saved, it.displayName)) }
        rawNote?.let { add(stringResource(R.string.raw_note, it)) }
    }
    // Fixed height so the preset bar and shutter never move under the finger when the text grows.
    Text(
        text = parts.joinToString(" · "),
        color = Color.White,
        minLines = STATUS_LINES,
        maxLines = STATUS_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(8.dp),
    )
}

private fun describe(shot: StillCapture): String {
    val exposure = checkNotNull(shot.spec.exposure).exposure
    val denominator = (NS_PER_SECOND / exposure.timeNs).roundToLong()
    return "%s %.1f MB 1/%d s ISO %d".format(shot.format, shot.bytes.size / BYTES_PER_MB, denominator, exposure.iso)
}

private fun log(shot: StillCapture) {
    val exposure = checkNotNull(shot.spec.exposure)
    Log.i(
        TAG,
        "still preset=${shot.spec.preset} lens=${shot.spec.physicalId} format=${shot.format} " +
            "bytes=${shot.bytes.size} timeNs=${exposure.exposure.timeNs} iso=${exposure.exposure.iso} " +
            "isoClamped=${exposure.isoClamped} flashFired=${shot.flashFired} color=${shot.spec.color} " +
            "focus=${shot.spec.focus} reported=${shot.reported}",
    )
}

private fun ScenePreset.label(): Int = when (this) {
    ScenePreset.SNAPSHOT -> R.string.preset_snapshot
    ScenePreset.WIDE -> R.string.preset_wide
    ScenePreset.FISH_SCHOOL -> R.string.preset_fish_school
    ScenePreset.MACRO -> R.string.preset_macro
    ScenePreset.LOW_LIGHT -> R.string.preset_low_light
}
