// SPDX-FileCopyrightText: 2026 Terry Wang and Anomalops contributors
// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tengigabytes.anomalops.probe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** Posts a high-importance notification to see whether heads-up alerts appear during screen pinning (ADR-0006). */
internal object LockTestNotifier {
    private const val CHANNEL_ID = "lock-test"
    private const val NOTIFICATION_ID = 1

    fun postHeadsUp(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Lock test", NotificationManager.IMPORTANCE_HIGH),
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Anomalops lock test")
            .setContentText("Heads-up test notification")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
