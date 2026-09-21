package com.eeinspired.mantel.upload

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException

/** A picked item copied into app-private storage, with the metadata the upload needs. */
data class StagedFile(
    val path: String,
    /** Sanitised name, used both for the UI and as the WebDAV filename. */
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** Capture date in epoch seconds for `X-OC-Mtime` (API Contract §3/§4), or null. */
    val captureEpochSeconds: Long?,
)

/** Why a pick couldn't be staged. [reason] is a fixed vocabulary, safe for telemetry. */
class StagingException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NOT_CONTENT_URI, TOO_LARGE, NO_SPACE, UNREADABLE, PERMISSION }
}

/**
 * Share-sheet and Photo Picker URIs carry only a transient read grant that can be
 * gone by the time a deferred [androidx.work.WorkManager] job runs (offline, reboot).
 * So we copy each selection into `cacheDir/uploads/<batchId>/` up front and hand the
 * worker a plain file path it can always read. Files are deleted after every
 * terminal upload outcome; batch directories that no queued job still needs are swept on
 * app start and on the next enqueue.
 */
object MediaStaging {

    private const val ROOT_DIR = "uploads"
    private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000 // 6h
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 // 4 GiB client-side sanity cap
    private const val FREE_SPACE_MARGIN = 64L * 1024 * 1024

    fun batchDir(context: Context, batchId: String): File =
        File(File(context.cacheDir, ROOT_DIR), batchId)

    /**
     * @throws StagingException with a coarse [StagingException.Reason]
     */
    fun stage(context: Context, batchId: String, uri: Uri): StagedFile {
        val meta = readMetadataChecked(context, uri)
        val dir = batchDir(context, batchId).apply { mkdirs() }
        if (meta.sizeBytes >= 0 && !ensureSpace(context, dir, meta.sizeBytes + FREE_SPACE_MARGIN)) {
            throw StagingException(StagingException.Reason.NO_SPACE)
        }
        val target = File(dir, RemoteNames.localFileName(meta.displayName, dir))
        copyTo(context, uri, target)

        // The copied file's length is the truth; a provider's advertised size can be wrong,
        // and a wrong Content-Length makes every upload attempt fail.
        val actualSizeBytes = target.length()
        if (actualSizeBytes > MAX_FILE_BYTES) target.delete()
        rejectOverCap(actualSizeBytes)

        return StagedFile(
            path = target.absolutePath,
            displayName = meta.displayName,
            mimeType = meta.mimeType,
            sizeBytes = actualSizeBytes,
            captureEpochSeconds = meta.captureEpochSeconds,
        )
    }

    private fun readMetadataChecked(context: Context, uri: Uri): Metadata {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            throw StagingException(StagingException.Reason.NOT_CONTENT_URI)
        }
        val meta = try {
            readMetadata(context, uri)
        } catch (e: SecurityException) {
            throw StagingException(StagingException.Reason.PERMISSION, e)
        }
        // meta.sizeBytes is -1 when the provider doesn't report OpenableColumns.SIZE
        // (some document/cloud-backed providers) — reject only a *known* over-cap size
        // here, and check the copied file's real length afterwards.
        rejectOverCap(meta.sizeBytes)
        return meta
    }

    private fun rejectOverCap(sizeBytes: Long) {
        if (sizeBytes > MAX_FILE_BYTES) throw StagingException(StagingException.Reason.TOO_LARGE)
    }

    /**
     * Whether [needed] bytes can be written under [dir]. Uses the storage manager's allocatable
     * figure — which counts other apps' clearable cache — and asks it to free that space, rather
     * than the smaller raw free-space number.
     */
    private fun ensureSpace(context: Context, dir: File, needed: Long): Boolean {
        val storage = context.getSystemService(StorageManager::class.java)
        return try {
            val uuid = storage.getUuidForPath(dir)
            if (storage.getAllocatableBytes(uuid) < needed) {
                false
            } else {
                storage.allocateBytes(uuid, needed)
                true
            }
        } catch (_: IOException) {
            false
        }
    }

    private fun copyTo(context: Context, uri: Uri, target: File) {
        val failure = copyOrFailure(context, uri, target) ?: return
        target.delete()
        val full = target.parentFile?.let { !ensureSpace(context, it, FREE_SPACE_MARGIN) } ?: true
        val reason = when {
            failure is SecurityException -> StagingException.Reason.PERMISSION
            full -> StagingException.Reason.NO_SPACE
            else -> StagingException.Reason.UNREADABLE
        }
        throw StagingException(reason, failure)
    }

    /** Copies [uri] into [target]; returns why it couldn't, or null on success. */
    private fun copyOrFailure(context: Context, uri: Uri, target: File): Exception? = try {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("cannot open")
        input.use { source -> target.outputStream().use { out -> source.copyTo(out) } }
        null
    } catch (e: SecurityException) {
        e
    } catch (e: IOException) {
        e
    }

    /**
     * Removes batch directories older than the staleness window that no queued or running
     * upload still needs ([activeBatchIds]). A directory's age alone must not decide: a
     * batch shared offline can legitimately wait longer than the window for a connection.
     */
    fun sweepStale(context: Context, activeBatchIds: Set<String>) {
        val root = File(context.cacheDir, ROOT_DIR)
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        root.listFiles()?.forEach { entry ->
            if (entry.name !in activeBatchIds && entry.lastModified() < cutoff) entry.deleteRecursively()
        }
    }

    /** Removes every staged file (sign-out). */
    fun deleteAll(context: Context) {
        File(context.cacheDir, ROOT_DIR).deleteRecursively()
    }

    private data class Metadata(
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val captureEpochSeconds: Long?,
    )

    private fun readMetadata(context: Context, uri: Uri): Metadata {
        val resolver = context.contentResolver
        var rawName = ""
        var size = -1L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        .takeIf { it >= 0 && !c.isNull(it) }?.let { rawName = c.getString(it) }
                    c.getColumnIndex(OpenableColumns.SIZE)
                        .takeIf { it >= 0 && !c.isNull(it) }?.let { size = c.getLong(it) }
                }
            }

        // DATE_TAKEN is a MediaStore column; querying it on a non-MediaStore provider
        // throws, so it gets its own guarded query.
        val dateTakenMs: Long? = runCatching {
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_TAKEN), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()

        val mime = resolver.getType(uri) ?: "application/octet-stream"
        return Metadata(
            displayName = RemoteNames.safe(rawName),
            mimeType = mime,
            sizeBytes = size,
            captureEpochSeconds = dateTakenMs?.takeIf { it > 0 }?.let { it / 1000 },
        )
    }
}
