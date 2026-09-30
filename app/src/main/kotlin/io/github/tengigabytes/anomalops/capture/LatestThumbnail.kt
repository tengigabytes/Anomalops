// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import io.github.tengigabytes.anomalops.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

private const val THUMBNAIL_SAMPLE = 16

/** FR-62: the latest still; a long press keeps its RAW frame as a DNG. The caller sizes it (dive-lock-layout.md). */
@Composable
fun LatestThumbnail(jpeg: ByteArray?, onLongPress: () -> Unit, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, jpeg) {
        value = jpeg?.let { withContext(Dispatchers.Default) { decode(it) } }
    }
    val description = stringResource(R.string.thumbnail_description)
    val box = modifier
        .background(Color.DarkGray)
        .combinedClickable(onClick = {}, onLongClick = onLongPress)
    Box(box) {
        bitmap?.let {
            Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** A small, upright bitmap: the JPEG is subsampled and turned by its EXIF orientation. */
private fun decode(jpeg: ByteArray): Bitmap? {
    val options = BitmapFactory.Options().apply { inSampleSize = THUMBNAIL_SAMPLE }
    return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)?.let { bitmap ->
        val degrees = rotationDegrees(ExifInterface(ByteArrayInputStream(jpeg)))
        if (degrees == 0) {
            bitmap
        } else {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }
}

private fun rotationDegrees(exif: ExifInterface): Int =
    when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90 -> QUARTER_TURN
        ExifInterface.ORIENTATION_ROTATE_180 -> HALF_TURN
        ExifInterface.ORIENTATION_ROTATE_270 -> THREE_QUARTER_TURN
        else -> 0
    }

private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val THREE_QUARTER_TURN = 270
