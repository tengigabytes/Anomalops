// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.app.Activity
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs [FovProbe] once and writes `files/probe/fov.json`. Keep the phone still, facing a textured scene.
 * Launch: `adb shell am start -S -f 0x10008000 -n io.github.tengigabytes.anomalops.probe/.FovTestActivity`
 */
class FovTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val output = TextView(this).apply { text = "Field-of-view test running… keep the phone still." }
        setContentView(output)
        thread(name = "fov") {
            val cameraThread = HandlerThread("fov-camera").apply { start() }
            val text = runCatching {
                val report = FovProbe(getSystemService(CameraManager::class.java), Handler(cameraThread.looper)).run()
                File(File(filesDir, "probe").apply { mkdirs() }, "fov.json").writeText(report.toString(2))
                report.toString(2)
            }.getOrElse { "FOV test failed:\n${it.stackTraceToString()}" }
            cameraThread.quitSafely()
            Log.i(TAG, "fov " + text.replace('\n', ' '))
            runOnUiThread { output.text = text }
        }
    }

    private companion object {
        const val TAG = "AnomalopsProbe"
    }
}
