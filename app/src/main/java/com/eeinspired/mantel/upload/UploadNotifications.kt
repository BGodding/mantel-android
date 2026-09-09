package com.eeinspired.mantel.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat

/** Foreground notification for in-progress uploads (Requirements §6.4 background transfer). */
object UploadNotifications {

    const val CHANNEL_ID = "uploads"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Uploads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Shown while photos and videos are uploading to a frame." },
            )
        }
    }

    fun building(context: Context, fileName: String): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Uploading to your frame")
            .setContentText(fileName)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
    }
}
