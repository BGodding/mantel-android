package com.eeinspired.mantel.upload

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.eeinspired.mantel.data.Config
import com.eeinspired.mantel.data.Destination
import com.eeinspired.mantel.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit

data class EnqueueResult(val tag: String, val staged: Int, val failed: Int)

/** Stages the picked files, then enqueues one [UploadWorker] per file under a shared batch tag. */
object UploadEnqueuer {

    suspend fun enqueue(
        context: Context,
        destination: Destination,
        username: String,
        uris: List<Uri>,
    ): EnqueueResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        MediaStaging.sweepStale(appContext)

        val batchId = UUID.randomUUID().toString()
        val tag = "$TAG_BATCH_PREFIX$batchId"
        val collectionUrl = destination.uploadCollectionUrl(username)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        var failed = 0
        val requests = uris.mapNotNull { uri ->
            val staged = runCatching { MediaStaging.stage(appContext, batchId, uri) }.getOrNull()
            if (staged == null) {
                failed++
                return@mapNotNull null
            }
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(tag)
                .addTag(UploadWorker.TAG_ALL)
                .setInputData(
                    workDataOf(
                        UploadWorker.KEY_STAGED_PATH to staged.path,
                        UploadWorker.KEY_DEST_URL to collectionUrl,
                        // Pin the host for this batch so staging + MOVE stay on one
                        // server even if server_base_url flips before the worker runs.
                        UploadWorker.KEY_BASE_URL to Config.baseUrl,
                        UploadWorker.KEY_DISPLAY_NAME to staged.displayName,
                        UploadWorker.KEY_MIME to staged.mimeType,
                        UploadWorker.KEY_SIZE to staged.sizeBytes,
                        UploadWorker.KEY_MTIME to (staged.captureEpochSeconds ?: -1L),
                    ),
                )
                .build()
        }

        if (requests.isNotEmpty()) {
            WorkManager.getInstance(appContext).enqueue(requests)
        }
        Telemetry.event(
            "upload_enqueued",
            mapOf("count" to requests.size, "stage_failed" to failed),
        )
        EnqueueResult(tag = tag, staged = requests.size, failed = failed)
    }

    const val TAG_BATCH_PREFIX = "upload-batch:"
}
