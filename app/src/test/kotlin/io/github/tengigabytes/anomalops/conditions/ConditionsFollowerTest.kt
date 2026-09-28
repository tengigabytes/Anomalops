// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.conditions

import io.github.tengigabytes.anomalops.core.profile.CalibrationKey
import io.github.tengigabytes.anomalops.core.profile.DepthBand
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.core.telemetry.depth.DepthZone
import io.github.tengigabytes.anomalops.core.telemetry.depth.ManualDepthSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class ConditionsFollowerTest {
    private val depth = ManualDepthSource(DepthZone.SHALLOW)
    private val follower = ConditionsFollower(ShootingConditions(depth))
    private val sent = mutableListOf<CalibrationKey>()

    @Test
    fun aChangeBeforeTheCollectorStartsIsStillSent() = runBlocking<Unit> {
        assertEquals(SHALLOW, follower.send())
        depth.select(DepthZone.DEEP)
        val job = collect()
        job.cancel()
        assertEquals(listOf(DEEP), sent)
    }

    @Test
    fun theKeyThePreviewOpenedWithIsNotSentAgain() = runBlocking<Unit> {
        follower.send()
        val job = collect()
        depth.select(DepthZone.DEEP)
        yield()
        depth.select(DepthZone.SHALLOW)
        yield()
        job.cancel()
        assertEquals(listOf(DEEP, SHALLOW), sent)
    }

    @Test
    fun changesWhileNoPreviewRunsWaitForTheNextStart() = runBlocking<Unit> {
        val job = collect()
        depth.select(DepthZone.DEEP)
        yield()
        follower.send()
        follower.closed()
        depth.select(DepthZone.SHALLOW)
        yield()
        job.cancel()
        assertEquals(emptyList<CalibrationKey>(), sent)
        assertEquals(SHALLOW, follower.send())
    }

    private fun CoroutineScope.collect(): Job =
        launch(Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) { follower.changes.collect { sent += it } }

    private companion object {
        val SHALLOW = CalibrationKey(DepthBand.SHALLOW, LensFilter.NONE, diveLight = false)
        val DEEP = SHALLOW.copy(depthBand = DepthBand.DEEP)
    }
}
