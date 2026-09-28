// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import android.Manifest
import android.content.pm.PackageManager
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import io.github.tengigabytes.anomalops.R
import io.github.tengigabytes.anomalops.core.camera.session.CameraController

/**
 * The camera preview: a `SurfaceView` embedded in Compose (ADR-0007) with its buffer fixed to
 * [CameraController.PREVIEW_SIZE]. The camera routes into it only once the surface has that size.
 */
@Composable
fun CameraPreview(onSurfaceReady: (Surface) -> Unit, onSurfaceGone: () -> Unit, modifier: Modifier = Modifier) {
    val ready by rememberUpdatedState(onSurfaceReady)
    val gone by rememberUpdatedState(onSurfaceGone)
    AndroidView(modifier = modifier, factory = { context ->
        SurfaceView(context).apply {
            val size = CameraController.PREVIEW_SIZE
            holder.setFixedSize(size.width, size.height)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    if (width == size.width && height == size.height) ready(holder.surface)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) = gone()
            })
        }
    })
}

/** Asks for CAMERA once and shows [content] only when it is granted. */
@Composable
fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (granted) content() else Message(stringResource(R.string.camera_permission_needed))
}

@Composable
fun Message(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(text) }
}
