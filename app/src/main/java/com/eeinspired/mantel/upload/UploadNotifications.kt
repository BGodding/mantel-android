package com.eeinspired.mantel.upload

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.work.WorkManager
import com.eeinspired.mantel.R
import java.util.UUID

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

    /** No file name: notification text can show on the lock screen. */
    fun building(context: Context, workId: UUID): NotificationCompat.Builder {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_upload)
            .setContentTitle("Uploading to your frame")
            .setOngoing(true)
            .setProgress(0, 0, true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                0,
                "Cancel",
                WorkManager.getInstance(context).createCancelPendingIntent(workId),
            )
    }
}
