// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FR-62 memory row (docs/product/mvp-acceptance.md): 300 stills, each with a RAW frame through the buffer, then a
 * 10 s burst; memory may grow by at most 50 MB. Both PSS and the camera dma-bufs are checked, because PSS does not
 * see held RAW frames. Both readings are taken with the RAW buffer empty and the buffer pools warmed up, so the
 * difference shows leaks rather than the frames the buffer holds by design.
 */
@RunWith(AndroidJUnit4::class)
class MemoryTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = AppRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun fr62_memoryAfterThreeHundredStillsAndABurst() = runBlocking<Unit> {
        rig.start()
        // Warm-up lets the readers allocate their buffer pools (a RAW reader keeps up to 7 buffers, ADR-0005).
        repeat(WARM_UP) { rig.track(rig.pipeline.shoot().saved.stem) }
        rig.keeper.clear()
        val before = rig.memoryMb()
        repeat(STILLS) { rig.track(rig.pipeline.shoot().saved.stem) }
        val burst = rig.pipeline.burst(until = { delay(BURST_MS) })
        rig.track(burst.stem)
        // The burst session released the RAW reader's pool; warm it up again so both readings compare equal states.
        repeat(WARM_UP) { rig.track(rig.pipeline.shoot().saved.stem) }
        rig.keeper.clear()
        val after = rig.memoryMb()
        val pssGrowth = after.pssMb - before.pssMb
        val dmaBufGrowth = after.dmaBufMb - before.dmaBufMb
        Log.i(
            TAG,
            "FR-62 PSS %.1f -> %.1f MB, dma-buf %.1f -> %.1f MB; burst ${burst.frames} frames (%.1f fps)".format(
                before.pssMb,
                after.pssMb,
                before.dmaBufMb,
                after.dmaBufMb,
                burst.frames * MS_PER_S / BURST_MS,
            ),
        )
        assertTrue("FR-62 PSS grew %.1f MB > $MAX_GROWTH_MB MB".format(pssGrowth), pssGrowth <= MAX_GROWTH_MB)
        assertTrue("FR-62 dma-buf grew %.1f MB > $MAX_GROWTH_MB MB".format(dmaBufGrowth), dmaBufGrowth <= MAX_GROWTH_MB)
    }

    private companion object {
        const val TAG = "M2Acceptance"
        const val WARM_UP = 10
        const val STILLS = 300
        const val BURST_MS = 10_000L
        const val MAX_GROWTH_MB = 50.0
        const val MS_PER_S = 1_000.0
    }
}
