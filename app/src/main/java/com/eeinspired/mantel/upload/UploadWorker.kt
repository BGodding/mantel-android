package com.eeinspired.mantel.upload

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eeinspired.mantel.data.Config
import com.eeinspired.mantel.data.CredentialStore
import com.eeinspired.mantel.data.NextcloudClient
import com.eeinspired.mantel.data.UploadResult
import com.eeinspired.mantel.data.encodePathSegment
import com.eeinspired.mantel.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Uploads one staged file to one destination. One worker per file so each outcome is
 * independently visible (Requirements §7). Transient failures return [Result.retry]
 * (WorkManager applies exponential backoff); 401/403/507 are terminal and never retried.
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val name = inputData.getString(KEY_DISPLAY_NAME) ?: "photo"
        val notification = UploadNotifications.building(applicationContext, name).build()
        return ForegroundInfo(
            id.hashCode(),
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val stagedPath = inputData.getString(KEY_STAGED_PATH) ?: return@withContext fail("bad_input")
        val collectionUrl = inputData.getString(KEY_DEST_URL) ?: return@withContext fail("bad_input")
        val baseUrl = inputData.getString(KEY_BASE_URL) ?: Config.baseUrl
        val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "upload"
        val mime = inputData.getString(KEY_MIME) ?: "application/octet-stream"
        val size = inputData.getLong(KEY_SIZE, -1L)
        val mtime = inputData.getLong(KEY_MTIME, -1L).takeIf { it > 0 }

        val file = File(stagedPath)
        if (!file.exists()) return@withContext fail("unreadable_file")

        val creds = CredentialStore(applicationContext).load() ?: return@withContext fail("no_credentials")
        val startedAt = System.currentTimeMillis()

        setProgress(workDataOf(KEY_DISPLAY_NAME to displayName))
        runCatching { setForeground(getForegroundInfo()) } // ignored if bg-start is disallowed

        val finalUrl = collectionUrl.trimEnd('/') + "/" + encodePathSegment(displayName)
        val chunked = size !in 0 until SIMPLE_UPLOAD_MAX_BYTES
        val client = NextcloudClient(baseUrl)

        val result = if (chunked) {
            client.chunkedUpload(creds, creds.username, finalUrl, mtime, maxOf(size, file.length())) {
                file.inputStream()
            }
        } else {
            client.putFile(creds, finalUrl, mime, mtime, size) { file.inputStream() }
        }

        report(result, chunked, size, System.currentTimeMillis() - startedAt)

        when (result) {
            UploadResult.Success -> {
                cleanUp(file)
                Result.success(workDataOf(KEY_DISPLAY_NAME to displayName, KEY_OUTCOME to "success"))
            }
            UploadResult.Unauthorized -> terminal(file, "auth")
            UploadResult.Forbidden -> terminal(file, "forbidden")
            UploadResult.DestinationMissing -> terminal(file, "dest_missing")
            UploadResult.QuotaExceeded -> terminal(file, "quota")
            is UploadResult.ServerError ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else terminal(file, "server")
            is UploadResult.Network ->
                if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else terminal(file, "network")
        }
    }

    private fun terminal(file: File, kind: String): Result {
        cleanUp(file)
        return fail(kind)
    }

    private fun fail(kind: String): Result = Result.failure(
        workDataOf(
            KEY_DISPLAY_NAME to (inputData.getString(KEY_DISPLAY_NAME) ?: "upload"),
            KEY_ERROR_KIND to kind,
        ),
    )

    private fun cleanUp(file: File) {
        file.delete()
        file.parentFile?.let { dir -> if (dir.list()?.isEmpty() == true) dir.delete() }
    }

    private fun report(result: UploadResult, chunked: Boolean, size: Long, durationMs: Long) {
        val outcome = when (result) {
            UploadResult.Success -> "success"
            UploadResult.Unauthorized -> "auth"
            UploadResult.Forbidden -> "forbidden"
            UploadResult.DestinationMissing -> "dest_missing"
            UploadResult.QuotaExceeded -> "quota"
            is UploadResult.ServerError -> "server_${result.code}"
            is UploadResult.Network -> "network"
        }
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
    }

    companion object {
        const val KEY_STAGED_PATH = "staged_path"
        const val KEY_DEST_URL = "dest_collection_url"
        const val KEY_BASE_URL = "base_url"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_MIME = "mime"
        const val KEY_SIZE = "size"
        const val KEY_MTIME = "mtime"
        const val KEY_OUTCOME = "outcome"
        const val KEY_ERROR_KIND = "error_kind"

        const val TAG_ALL = "upload"

        /** Below this, use a single PUT; at or above, use chunked v2. Conservatively
         *  under Nextcloud's ~100 MiB single-PUT ceiling (API Contract §3). */
        const val SIMPLE_UPLOAD_MAX_BYTES = 8L * 1024 * 1024
        const val MAX_ATTEMPTS = 5
    }
}
