package com.eeinspired.mantel.upload

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eeinspired.mantel.data.Config
import com.eeinspired.mantel.data.CredentialStore
import com.eeinspired.mantel.data.Credentials
import com.eeinspired.mantel.data.NextcloudClient
import com.eeinspired.mantel.data.UploadError
import com.eeinspired.mantel.data.UploadResult
import com.eeinspired.mantel.telemetry.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * Uploads one staged file to one destination. One worker per file so each outcome is
 * independently visible (Requirements §7). Transient failures return [Result.retry]
 * (WorkManager applies exponential backoff); 401/403/507 and other permanent refusals are
 * terminal and never retried.
 *
 * Retries are safe: the chunked staging collection is keyed by this work request's id, so a
 * retry resumes where the last attempt stopped; and an existing file is never overwritten —
 * a name clash is resolved by numbering (`IMG (1).jpg`).
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = UploadNotifications.building(applicationContext, id).build()
        return ForegroundInfo(
            id.hashCode(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "upload"
        val stagedPath = inputData.getString(KEY_STAGED_PATH) ?: return@withContext fail(UploadError.BAD_INPUT)
        val collection = inputData.getString(KEY_DEST_URL)?.toHttpUrlOrNull()
            ?: return@withContext fail(UploadError.BAD_INPUT)
        val baseUrl = inputData.getString(KEY_BASE_URL) ?: Config.baseUrl
        val mime = inputData.getString(KEY_MIME) ?: "application/octet-stream"
        val mtime = inputData.getLong(KEY_MTIME, -1L).takeIf { it > 0 }

        val file = File(stagedPath)
        if (!file.exists()) return@withContext fail(UploadError.UNREADABLE_FILE)

        val creds = CredentialStore(applicationContext).load() ?: return@withContext fail(UploadError.NO_CREDENTIALS)
        val startedAt = System.currentTimeMillis()

        setProgress(workDataOf(KEY_DISPLAY_NAME to displayName))
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e // a stopped worker must stop, not carry on uploading
        } catch (_: IllegalStateException) {
            // Foreground start disallowed from the background — the upload still runs.
        }

        val client = NextcloudClient(baseUrl)
        val uploadId = id.toString()

        // The staged copy's length is the truth, whatever a provider advertised.
        val job = UploadJob(client, creds, Target(collection, displayName, mime, mtime), file, uploadId)
        val result = uploadWithUniqueName(job)
        report(result, job.chunked, job.size, System.currentTimeMillis() - startedAt)
        finish(result, job)
    }

    private class Target(val collection: HttpUrl, val displayName: String, val mime: String, val mtime: Long?)

    private class UploadJob(
        val client: NextcloudClient,
        val creds: Credentials,
        val target: Target,
        val file: File,
        val uploadId: String,
    ) {
        val size: Long = file.length()
        val chunked: Boolean = size >= SIMPLE_UPLOAD_MAX_BYTES
        val displayName: String get() = target.displayName
    }

    /** Tries `name`, then `name (1)`, `name (2)`… while the server says a file of that name exists. */
    private suspend fun uploadWithUniqueName(job: UploadJob): UploadResult {
        var result: UploadResult = UploadResult.Conflict
        for (n in 0..MAX_RENAMES) {
            if (result != UploadResult.Conflict) break
            val name = numbered(job.displayName, n)
            val finalUrl = job.target.collection.newBuilder().addPathSegment(name).build().toString()
            if (n == 0 && alreadyUploaded(job, finalUrl)) return UploadResult.Success
            result = upload(job, finalUrl)
        }
        return result
    }

    /**
     * A previous attempt may have finished but lost its response. Without a capture time to match
     * on we can't tell that file from an unrelated one, so we don't guess (a duplicate beats a loss).
     */
    private suspend fun alreadyUploaded(job: UploadJob, finalUrl: String): Boolean {
        val mtime = job.target.mtime ?: return false
        return runAttemptCount > 0 && job.client.remoteFileMatches(job.creds, finalUrl, job.size, mtime)
    }

    private suspend fun upload(job: UploadJob, finalUrl: String): UploadResult =
        if (job.chunked) {
            job.client.chunkedUpload(job.creds, job.uploadId, finalUrl, job.target.mtime, job.size) {
                job.file.inputStream()
            }
        } else {
            job.client.putFile(job.creds, finalUrl, job.target.mime, job.target.mtime, job.size) {
                job.file.inputStream()
            }
        }

    private suspend fun finish(result: UploadResult, job: UploadJob): Result {
        val retriable = result is UploadResult.ServerError || result is UploadResult.Network
        if (retriable && runAttemptCount < MAX_ATTEMPTS - 1) return Result.retry() // keep file + staging for resume

        if (result != UploadResult.Success && job.chunked) job.client.discardStaging(job.creds, job.uploadId)
        cleanUp(job.file)
        return when (result) {
            UploadResult.Success ->
                Result.success(workDataOf(KEY_DISPLAY_NAME to job.displayName, KEY_OUTCOME to "success"))
            else -> fail(errorFor(result))
        }
    }

    private fun errorFor(result: UploadResult): UploadError = when (result) {
        UploadResult.Success -> error("no error for success")
        UploadResult.Unauthorized -> UploadError.AUTH
        UploadResult.Forbidden -> UploadError.FORBIDDEN
        UploadResult.Conflict -> UploadError.CONFLICT
        UploadResult.DestinationMissing -> UploadError.DEST_MISSING
        UploadResult.QuotaExceeded -> UploadError.QUOTA
        is UploadResult.Rejected -> UploadError.REJECTED
        is UploadResult.ServerError -> UploadError.SERVER
        is UploadResult.Network -> UploadError.NETWORK
    }

    private fun fail(error: UploadError): Result = Result.failure(
        workDataOf(
            KEY_DISPLAY_NAME to (inputData.getString(KEY_DISPLAY_NAME) ?: "upload"),
            KEY_ERROR_KIND to error.name,
        ),
    )

    private fun cleanUp(file: File) {
        file.delete()
        file.parentFile?.let { dir -> if (dir.list()?.isEmpty() == true) dir.delete() }
    }

    private fun outcomeLabel(result: UploadResult): String = when (result) {
        UploadResult.Success -> "success"
        UploadResult.Unauthorized -> "auth"
        UploadResult.Forbidden -> "forbidden"
        UploadResult.Conflict -> "conflict"
        UploadResult.DestinationMissing -> "dest_missing"
        UploadResult.QuotaExceeded -> "quota"
        is UploadResult.Rejected -> "rejected_${result.code}"
        is UploadResult.ServerError -> "server_${result.code}"
        is UploadResult.Network -> "network"
    }

    private fun report(result: UploadResult, chunked: Boolean, size: Long, durationMs: Long) {
        val outcome = outcomeLabel(result)
        val sizeMb = if (size > 0) size / (1024 * 1024) else -1L
        val kbPerSec = if (result == UploadResult.Success && size > 0 && durationMs > 0) {
            size * 1000 / durationMs / 1024
        } else {
            -1L
        }
        Telemetry.event(
            "upload_result",
            mapOf(
                "outcome" to outcome,
                "chunked" to chunked,
                "size_mb" to sizeMb,
                "attempt" to runAttemptCount,
                "duration_ms" to durationMs,
                "kb_per_sec" to kbPerSec,
            ),
        )
        if (isUnexpected(result)) {
            val cause = (result as? UploadResult.Network)?.cause?.let { " cause=${it.javaClass.simpleName}" }.orEmpty()
            Telemetry.setKey("upload_last_outcome", outcome)
            // Actionable without identifying anything: status, transport, size class, attempt.
            Telemetry.recordNonFatal(
                UploadFailedException(
                    "upload failed: outcome=$outcome chunked=$chunked size_mb=$sizeMb attempt=$runAttemptCount$cause",
                ),
            )
        }
    }

    /** Outcomes that mean the app or server did something unexpected (not a user-fixable refusal). */
    private fun isUnexpected(result: UploadResult): Boolean {
        val exhausted = runAttemptCount >= MAX_ATTEMPTS - 1
        return when (result) {
            is UploadResult.Rejected -> true
            is UploadResult.ServerError, is UploadResult.Network -> exhausted
            else -> false
        }
    }

    class UploadFailedException(message: String) : RuntimeException(message)

    companion object {
        const val KEY_STAGED_PATH = "staged_path"
        const val KEY_DEST_URL = "dest_collection_url"
        const val KEY_BASE_URL = "base_url"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_MIME = "mime"
        const val KEY_MTIME = "mtime"
        const val KEY_OUTCOME = "outcome"
        const val KEY_ERROR_KIND = "error_kind"

        const val TAG_ALL = "upload"

        /** Below this, use a single PUT; at or above, use chunked v2. Conservatively
         *  under Nextcloud's ~100 MiB single-PUT ceiling (API Contract §3). */
        const val SIMPLE_UPLOAD_MAX_BYTES = 8L * 1024 * 1024
        const val MAX_ATTEMPTS = 5
        private const val MAX_RENAMES = 9

        /** `IMG.jpg` → `IMG (2).jpg`; [n] = 0 keeps the name. */
        internal fun numbered(name: String, n: Int): String {
            if (n == 0) return name
            val dot = name.lastIndexOf('.')
            return if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
        }
    }
}
