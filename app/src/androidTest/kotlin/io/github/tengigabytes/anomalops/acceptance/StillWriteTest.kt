// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.acceptance

import android.Manifest
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/**
 * NFR-7 (docs/product/mvp-acceptance.md): 300 stills written to MediaStore, from the finished capture to
 * `IS_PENDING = 0`, p95 at most 500 ms. Stills go through the app's pipeline, so their RAW frames reach the
 * buffer (ADR-0005) instead of leaking. The files are deleted afterwards.
 */
@RunWith(AndroidJUnit4::class)
class StillWriteTest {
    @get:Rule
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private val rig = AppRig()

    @After
    fun tearDown() = rig.close()

    @Test
    fun nfr7_threeHundredMediaStoreWrites() = runBlocking<Unit> {
        rig.start()
        val writeMs = List(WRITES) {
            val saved = rig.pipeline.shoot().saved
            rig.track(saved.stem)
            saved.writeMs.toDouble()
        }.sorted()
        val p95 = writeMs[ceil(P95 * writeMs.size).toInt() - 1]
        Log.i(TAG, "NFR-7 n=${writeMs.size} median=${writeMs[writeMs.size / 2]} p95=$p95 max=${writeMs.last()} ms")
        assertTrue("NFR-7 p95 $p95 ms > $WRITE_P95_MS ms", p95 <= WRITE_P95_MS)
    }

    private companion object {
        const val TAG = "M1Acceptance"
        const val WRITES = 300
        const val WRITE_P95_MS = 500.0
        const val P95 = 0.95
    }
}
