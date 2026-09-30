// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.tengigabytes.anomalops.R
import io.github.tengigabytes.anomalops.core.profile.LensFilter
import io.github.tengigabytes.anomalops.dive.Key
import io.github.tengigabytes.anomalops.dive.rememberMmScale
import io.github.tengigabytes.anomalops.dive.rgb
import io.github.tengigabytes.anomalops.layout.KEY_MM
import io.github.tengigabytes.anomalops.layout.SAFE_MARGIN_MM
import io.github.tengigabytes.anomalops.theme.DivePalette

private const val TITLE_Y_MM = 16f
private const val LABEL_Y_MM = 30f
private const val FILTER_Y_MM = 36f
private const val NOTE_Y_MM = 50f
private const val FILTER_GAP_MM = 2f
private const val FILTER_KEYS = 3

/** FR-24: the filter on the housing's lens port, kept across launches. */
class FilterPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(): LensFilter =
        prefs.getString(KEY, null)?.let { name -> LensFilter.entries.firstOrNull { it.name == name } }
            ?: LensFilter.NONE

    fun save(filter: LensFilter) {
        prefs.edit().putString(KEY, filter.name).apply()
    }

    private companion object {
        const val FILE = "settings"
        const val KEY = "lens_filter"
    }
}

/**
 * The settings page (docs/product/dive-lock-layout.md, section 2): normal mode only. v1.0 has one setting, the
 * FR-24 filter. Keys keep the dive-lock sizes and the 12 mm margin so nothing jumps when entering or leaving.
 */
@Composable
fun SettingsScreen(filter: LensFilter, onFilter: (LensFilter) -> Unit, onBack: () -> Unit) {
    val scale = rememberMmScale()
    val margin = scale.xDp(SAFE_MARGIN_MM)
    BoxWithConstraints(Modifier.fillMaxSize().background(rgb(DivePalette.BACKGROUND))) {
        val innerMm = scale.widthMm(constraints.maxWidth) - 2 * SAFE_MARGIN_MM
        Key(
            label = stringResource(R.string.back),
            modifier = Modifier.offset(margin, scale.yDp(SAFE_MARGIN_MM))
                .width(scale.xDp(KEY_MM)).height(scale.yDp(KEY_MM)),
            onClick = onBack,
        )
        Text(
            stringResource(R.string.settings),
            color = rgb(DivePalette.TEXT),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(margin + scale.xDp(KEY_MM + FILTER_GAP_MM), scale.yDp(TITLE_Y_MM)),
        )
        Text(
            stringResource(R.string.filter_title),
            color = rgb(DivePalette.SECONDARY),
            fontSize = 16.sp,
            modifier = Modifier.offset(margin, scale.yDp(LABEL_Y_MM)),
        )
        FilterKeys(filter, onFilter, innerMm, Modifier.offset(margin, scale.yDp(FILTER_Y_MM)))
        Text(
            stringResource(R.string.filter_note),
            color = rgb(DivePalette.SECONDARY),
            fontSize = 16.sp,
            modifier = Modifier.offset(margin, scale.yDp(NOTE_Y_MM)).width(scale.xDp(innerMm)),
        )
    }
}

@Composable
private fun FilterKeys(selected: LensFilter, onFilter: (LensFilter) -> Unit, widthMm: Float, modifier: Modifier) {
    val scale = rememberMmScale()
    val keyWidth = (widthMm - (FILTER_KEYS - 1) * FILTER_GAP_MM) / FILTER_KEYS
    Row(modifier) {
        LensFilter.entries.forEachIndexed { i, filter ->
            if (i > 0) Box(Modifier.width(scale.xDp(FILTER_GAP_MM)))
            Key(
                label = stringResource(filterName(filter)),
                modifier = Modifier.width(scale.xDp(keyWidth)).height(scale.yDp(KEY_MM)),
                selected = filter == selected,
            ) { onFilter(filter) }
        }
    }
}

private fun filterName(filter: LensFilter): Int = when (filter) {
    LensFilter.NONE -> R.string.filter_none
    LensFilter.RED -> R.string.filter_red
    LensFilter.MAGENTA -> R.string.filter_magenta
}
