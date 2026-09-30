// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import io.github.tengigabytes.anomalops.capture.CameraPermissionGate
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
import io.github.tengigabytes.anomalops.core.telemetry.log.SessionInfo
import io.github.tengigabytes.anomalops.dive.DiveActions
import io.github.tengigabytes.anomalops.dive.DiveDeps
import io.github.tengigabytes.anomalops.dive.DiveScreen
import io.github.tengigabytes.anomalops.lock.CrashRestarter
import io.github.tengigabytes.anomalops.lock.DiveLock
import io.github.tengigabytes.anomalops.lock.LockState
import io.github.tengigabytes.anomalops.lock.PinWatcher
import io.github.tengigabytes.anomalops.lock.PrefsLockStore
import io.github.tengigabytes.anomalops.lock.applyDiveLockWindow
import io.github.tengigabytes.anomalops.lock.isPinned
import io.github.tengigabytes.anomalops.settings.FilterPrefs
import io.github.tengigabytes.anomalops.settings.SettingsScreen
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * Single activity (ADR-0007): the camera screen in normal mode and in dive lock (ADR-0006,
 * docs/product/dive-lock-layout.md), and the settings page. The FR-45 session lives in [Sessions], for as long as
 * the process, so recreating this activity does not restart it.
 */
class MainActivity : ComponentActivity() {
    private var controller: CameraController? = null
    private val rawKeeper by lazy { RawKeeper(DngStore(applicationContext), lifecycleScope) }
    private val stacks by lazy { BurstStacks(applicationContext) }

    // FR-84: v1.0 depth is the diver's manual zone, switched with the depth-band key.
    private val depth by lazy { ManualDepthSource(initialZone()) }
    private val conditions by lazy { ShootingConditions(depth) }
    private val filters by lazy { FilterPrefs(this) }
    private val store by lazy { PrefsLockStore(this) }
    private lateinit var lock: DiveLock
    private lateinit var pins: PinWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        CrashRestarter.install(this, store, store)
        lock = DiveLock(store, isPinned()) { SessionInfo.idAt(LocalDateTime.now()) }
        pins = PinWatcher(this, lock)
        conditions.mount(filters.load())
        controller = loadProfile()?.let { CameraController(applicationContext, it) }
        logRestart(intent)
        lifecycleScope.launch {
            lock.state.collect { state ->
                applyDiveLockWindow(state is LockState.Locked)
                Sessions.follow(applicationContext, state)
                pins.follow(state)
            }
        }
        setContent { MaterialTheme { Root() } }
        injectCrashIfAsked(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        logRestart(intent)
        injectCrashIfAsked(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        pins.onWindowFocusChanged(hasFocus)
    }

    override fun onDestroy() {
        pins.stop()
        // Held RAW frames return their camera buffers before the camera thread stops (ADR-0005).
        rawKeeper.clear()
        controller?.release()
        super.onDestroy()
    }

    @Composable
    private fun Root() {
        val camera = controller
        if (camera == null) {
            Message(stringResource(R.string.unsupported_device, Build.DEVICE))
            return
        }
        CameraPermissionGate {
            val state by lock.state.collectAsState()
            var settingsOpen by rememberSaveable { mutableStateOf(false) }
            var filter by remember { mutableStateOf(filters.load()) }
            val deps = remember(camera) {
                DiveDeps(
                    camera,
                    ShotPipeline(camera, StillStore(applicationContext), rawKeeper, stacks),
                    conditions,
                    depth,
                )
            }
            val actions = remember {
                DiveActions(pins::start, ::unlock, { settingsOpen = true }, { Sessions.current })
            }
            // In dive lock, back does nothing (FR-51); in normal mode it closes the settings page.
            BackHandler(enabled = state is LockState.Locked || settingsOpen) { settingsOpen = false }
            if (settingsOpen && state == LockState.Normal) {
                SettingsScreen(
                    filter = filter,
                    onFilter = {
                        filter = it
                        filters.save(it)
                        conditions.mount(it)
                    },
                    onBack = { settingsOpen = false },
                )
            } else {
                DiveScreen(deps, actions, locked = state is LockState.Locked)
            }
        }
    }

    /** FR-51: the unlock hold completed. The system locks the phone right after unpinning (G1 platform test). */
    private fun unlock() {
        stopLockTask()
        lock.unlocked()
    }

    private fun logRestart(intent: Intent) {
        val crashedAt = intent.getLongExtra(CrashRestarter.EXTRA_CRASHED_AT, -1)
        if (crashedAt > 0) Log.i(TAG, "restarted after crash in ${SystemClock.elapsedRealtime() - crashedAt} ms")
    }

    /** NFR-1 test: debug builds crash on `am start ... --ez injectCrash true` (docs/test/m3-test-plan.md). */
    private fun injectCrashIfAsked(intent: Intent) {
        if (!debuggable() || !intent.getBooleanExtra(EXTRA_INJECT_CRASH, false)) return
        intent.removeExtra(EXTRA_INJECT_CRASH)
        window.decorView.post { throw IllegalStateException("injected crash for the NFR-1 test") }
    }

    private fun debuggable(): Boolean = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** The starting depth zone; debug builds accept `--es depthBand DEEP` etc. to test the fallback (ADR-0002). */
    private fun initialZone(): DepthZone {
        val requested = intent.getStringExtra(EXTRA_DEPTH_BAND)?.takeIf { debuggable() } ?: return DepthZone.SHALLOW
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
        const val EXTRA_INJECT_CRASH = "injectCrash"
    }
}
