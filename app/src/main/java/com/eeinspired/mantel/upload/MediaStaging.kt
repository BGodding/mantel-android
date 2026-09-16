package com.eeinspired.mantel.upload

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
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

/**
 * Share-sheet and Photo Picker URIs carry only a transient read grant that can be
 * gone by the time a deferred [androidx.work.WorkManager] job runs (offline, reboot).
 * So we copy each selection into `cacheDir/uploads/<batchId>/` up front and hand the
 * worker a plain file path it can always read. Files are deleted after every
 * terminal upload outcome; stale batch directories are swept on app start and on
 * the next enqueue.
 */
object MediaStaging {

    private const val ROOT_DIR = "uploads"
    private const val STALE_AFTER_MS = 6L * 60 * 60 * 1000 // 6h
    private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024 // 4 GiB client-side sanity cap
    private const val MAX_NAME_LENGTH = 200

    fun batchDir(context: Context, batchId: String): File =
        File(File(context.cacheDir, ROOT_DIR), batchId)

    /**
     * @throws IllegalArgumentException for a non-`content://` URI or an over-cap file
     * @throws IOException if the source can't be read
     */
    fun stage(context: Context, batchId: String, uri: Uri): StagedFile {
        require(uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) {
            "refusing non-content URI: ${uri.scheme}"
        }
        val resolver = context.contentResolver
        val meta = readMetadata(context, uri)
        // meta.sizeBytes is -1 when the provider doesn't report OpenableColumns.SIZE
        // (some document/cloud-backed providers) — reject only a *known* over-cap size
        // here, and fall back to the copied file's real length below.
        require(meta.sizeBytes < 0 || meta.sizeBytes <= MAX_FILE_BYTES) { "file too large: ${meta.sizeBytes}" }

        val dir = batchDir(context, batchId).apply { mkdirs() }
        val target = File(dir, localFileName(meta.displayName, dir))
        resolver.openInputStream(uri).use { input ->
            if (input == null) throw IOException("cannot open $uri")
            target.outputStream().use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        }

        val actualSizeBytes = if (meta.sizeBytes >= 0) meta.sizeBytes else target.length()
        if (actualSizeBytes > MAX_FILE_BYTES) {
            target.delete()
            throw IllegalArgumentException("file too large: $actualSizeBytes")
        }

        return StagedFile(
            path = target.absolutePath,
            displayName = meta.displayName,
            mimeType = meta.mimeType,
            sizeBytes = actualSizeBytes,
            captureEpochSeconds = meta.captureEpochSeconds,
        )
    }

    fun sweepStale(context: Context) {
        val root = File(context.cacheDir, ROOT_DIR)
        val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
        root.listFiles()?.forEach { entry ->
            if (entry.lastModified() < cutoff) entry.deleteRecursively()
        }
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
            displayName = safeRemoteName(rawName),
            mimeType = mime,
            sizeBytes = size,
            captureEpochSeconds = dateTakenMs?.takeIf { it > 0 }?.let { it / 1000 },
        )
    }

    /**
     * Name used for the WebDAV path. Keeps the human-readable filename but strips
     * anything that could alter the path: directory separators, leading dots
     * (`.` / `..`), control characters. Length-capped, with a generated fallback.
     */
    private fun safeRemoteName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val noControl = base.replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
        val noLeadingDots = noControl.trimStart('.', ' ')
        val capped = noLeadingDots.take(MAX_NAME_LENGTH).trim()
        return capped.ifBlank { "upload_${System.currentTimeMillis()}" }
    }

    /** Filesystem-safe name for the staged copy on disk (de-duplicated within the batch dir). */
    private fun localFileName(safeName: String, dir: File): String {
        val cleaned = safeName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "upload" }
        if (!File(dir, cleaned).exists()) return cleaned
        val stem = cleaned.substringBeforeLast('.', cleaned)
        val ext = cleaned.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return "${stem}_${System.currentTimeMillis()}$ext"
    }
}
