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

/** [failed] files could not be copied into staging; [staged] were queued under [tag]. */
data class EnqueueResult(val tag: String, val staged: Int, val failed: Int)

/** Stages the picked files, then enqueues one [UploadWorker] per file under a shared batch tag. */
object UploadEnqueuer {

    private const val BACKOFF_SECONDS = 10L

    suspend fun enqueue(
        context: Context,
        destination: Destination,
        userId: String,
        uris: List<Uri>,
    ): EnqueueResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        sweep(appContext)

        val batchId = UUID.randomUUID().toString()
        val tag = "$TAG_BATCH_PREFIX$batchId"
        val collectionUrl = destination.uploadCollectionUrl(userId)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val failures = mutableListOf<StagingException.Reason>()
        val requests = uris.mapNotNull { uri ->
            val staged = try {
                MediaStaging.stage(appContext, batchId, uri)
            } catch (e: StagingException) {
                failures += e.reason
                return@mapNotNull null
            }
            OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
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
            mapOf("count" to requests.size, "stage_failed" to failures.size),
        )
        failures.groupingBy { it }.eachCount().forEach { (reason, count) ->
            Telemetry.event("upload_stage_failed", mapOf("reason" to reason.name.lowercase(), "count" to count))
        }
        EnqueueResult(tag = tag, staged = requests.size, failed = failures.size)
    }

    /** Batch ids that still have queued, running or blocked work — their staged files must survive. */
    fun activeBatchIds(context: Context): Set<String> =
        WorkManager.getInstance(context).getWorkInfosByTag(UploadWorker.TAG_ALL).get()
            .asSequence()
            .filter { !it.state.isFinished }
            .flatMap { it.tags }
            .filter { it.startsWith(TAG_BATCH_PREFIX) }
            .map { it.removePrefix(TAG_BATCH_PREFIX) }
            .toSet()

    /** Drops stale staging directories that no live upload needs. Blocking — call off the main thread. */
    fun sweep(context: Context) {
        MediaStaging.sweepStale(context.applicationContext, activeBatchIds(context.applicationContext))
    }

    const val TAG_BATCH_PREFIX = "upload-batch:"
}
