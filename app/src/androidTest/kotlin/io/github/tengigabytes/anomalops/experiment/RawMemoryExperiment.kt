// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.experiment

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.tengigabytes.anomalops.acceptance.AppRig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where do held RAW frames (ADR-0005) show up in `dumpsys meminfo`? Logs the memory categories of this process
 * idle, with five RAW frames held, and after releasing them, so the FR-62 memory check can read the right line.
 * Logcat tag [TAG]; the stills are deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class RawMemoryExperiment {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = AppRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun m2_rawFramesInMeminfo() = runBlocking<Unit> {
        rig.start()
        delay(SETTLE_MS)
        dump("idle")
        repeat(HELD) { rig.track(rig.pipeline.shoot().saved.stem) }
        dump("held$HELD")
        rig.keeper.clear()
        delay(SETTLE_MS)
        dump("released")
    }

    private fun dump(label: String) {
        val memory = rig.memoryMb()
        Log.i(TAG, "$label | PSS %.1f MB, dma-buf %.1f MB".format(memory.pssMb, memory.dmaBufMb))
    }

    private companion object {
        const val TAG = "M2Memory"
        const val HELD = 5
        const val SETTLE_MS = 2_000L
    }
}
