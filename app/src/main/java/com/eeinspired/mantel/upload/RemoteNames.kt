package com.eeinspired.mantel.upload

import java.io.File
import java.text.Normalizer

/** Turns provider-supplied file names into safe WebDAV path segments and staging file names. */
internal object RemoteNames {

    private const val MAX_NAME_BYTES = 200
    private const val MAX_EXTENSION_CHARS = 12

    /** Suffixes the server treats as in-flight uploads. */
    private val RESERVED_SUFFIXES = listOf(".part", ".filepart")
    private val FORBIDDEN_CHARS = Regex("[\\u0000-\\u001F\\u007F<>:\"|?*\\\\/]")
    private val LOCAL_UNSAFE = Regex("[^A-Za-z0-9._-]")

    /**
     * Name used for the WebDAV path. Keeps the human-readable filename but strips anything
     * that could alter the path or that the server rejects: directory separators, leading
     * dots (`.` / `..`), control characters, Windows-reserved characters, trailing dots/spaces,
     * and in-flight suffixes (`.part`). The 200-*byte* cap (filesystems limit bytes, not
     * characters) trims the stem so the extension survives. Falls back to a generated name.
     */
    fun safe(raw: String, fallbackStamp: Long = System.currentTimeMillis()): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = Normalizer.normalize(base, Normalizer.Form.NFC)
            .replace(FORBIDDEN_CHARS, "_")
            .trimStart('.', ' ')
            .trimEnd('.', ' ')
        val safe = if (RESERVED_SUFFIXES.any { cleaned.endsWith(it, ignoreCase = true) }) "${cleaned}_" else cleaned
        return capBytes(safe).trim().ifBlank { "upload_$fallbackStamp" }
    }

    private fun capBytes(name: String): String {
        if (name.toByteArray(Charsets.UTF_8).size <= MAX_NAME_BYTES) return name
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && name.length - dot <= MAX_EXTENSION_CHARS) name.substring(dot) else ""
        var stem = name.removeSuffix(ext)
        while (stem.isNotEmpty() && (stem + ext).toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES) {
            stem = stem.dropLast(1)
        }
        return stem + ext
    }

    /** Filesystem-safe name for the staged copy on disk (de-duplicated within the batch dir). */
    fun localFileName(safeName: String, dir: File): String {
        val cleaned = safeName.replace(LOCAL_UNSAFE, "_").take(MAX_NAME_BYTES).ifBlank { "upload" }
        if (!File(dir, cleaned).exists()) return cleaned
        val stem = cleaned.substringBeforeLast('.', cleaned)
        val ext = cleaned.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        return "${stem}_${System.nanoTime()}$ext"
    }
}
