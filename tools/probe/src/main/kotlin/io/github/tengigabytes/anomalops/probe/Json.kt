// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.util.Range
import org.json.JSONArray
import org.json.JSONObject

/** Builds a JSONObject; null values become JSON null instead of being dropped. */
internal fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject =
    JSONObject().apply { pairs.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }

internal fun Iterable<*>.toJsonArray(): JSONArray = JSONArray().also { array -> forEach { array.put(it) } }

/** org.json rejects NaN and infinities, which some platform APIs return (e.g. thermal headroom). */
internal fun Float.finiteOrString(): Any = if (isFinite()) toDouble() else toString()

internal fun FloatArray?.toJson(): Any = this?.map { it.finiteOrString() }?.toJsonArray() ?: JSONObject.NULL

internal fun <T : Comparable<T>> Range<T>?.toJson(): Any =
    this?.let { JSONArray().put(it.lower).put(it.upper) } ?: JSONObject.NULL
