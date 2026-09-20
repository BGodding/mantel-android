package com.eeinspired.mantel.data

import android.util.Base64

/** Nextcloud username + app password. Never the primary login password (Requirements §4). */
data class Credentials(val username: String, val appPassword: String) {
    fun basicAuthHeader(): String {
        val raw = "$username:$appPassword".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}

data class UserInfo(val id: String, val displayName: String)

/** Nextcloud permission bits (API Contract §2). */
object Permission {
    const val READ = 1
    const val UPDATE = 2
    const val CREATE = 4
    const val DELETE = 8
    const val SHARE = 16
}

/** One file inside a destination folder, from a WebDAV PROPFIND (used by the gallery feature). */
data class RemoteItem(
    /** Server-absolute, percent-encoded path, e.g. `/remote.php/dav/files/bob/Grandma/IMG_1.jpg`. */
    val href: String,
    val name: String,
    val isDirectory: Boolean,
    val contentType: String,
    val sizeBytes: Long,
    val lastModifiedEpochSeconds: Long,
    /** Nextcloud numeric file id, for the preview endpoint. */
    val fileId: String?,
    val hasPreview: Boolean,
    /** Server-side upload time (`nc:upload_time`); 0 when the server doesn't report it. */
    val uploadedEpochSeconds: Long = 0,
) {
    /** When the file reached the server, falling back to its modified time. */
    val addedEpochSeconds: Long get() = uploadedEpochSeconds.takeIf { it > 0 } ?: lastModifiedEpochSeconds

    val isImage: Boolean get() = contentType.startsWith("image/")
    val isVideo: Boolean get() = contentType.startsWith("video/")

    fun downloadUrl(): String = Config.baseUrl + href

    /** Nextcloud preview endpoint; null when the server reported no preview / no id. */
    fun previewUrl(px: Int = PREVIEW_PX): String? =
        fileId?.takeIf { hasPreview }?.let {
            "${Config.baseUrl}/core/preview?fileId=$it&x=$px&y=$px&mimeFallback=true&a=0"
        }
}

/**
 * An upload destination discovered from the server's share graph (Requirements §5).
 * There is no client-side create/edit/remove — the server is the only source of truth.
 */
data class Destination(
    val id: String,
    /** Human label, e.g. "Grandma" — derived from [remotePath]. */
    val displayName: String,
    /** Share target path, always leading-slash normalised, e.g. "/Grandma". */
    val remotePath: String,
    /** Nextcloud permission bitmask (Read=1, Update=2, Create=4, Delete=8, Share=16). */
    val permissions: Int,
) {
    /** True when this share grants Delete — i.e. the user is a "frame admin" for it. */
    val canDelete: Boolean get() = permissions and Permission.DELETE != 0

    /** WebDAV collection URL that files are PUT into, per API Contract §2/§3. */
    fun uploadCollectionUrl(username: String): String {
        val segments = remotePath.trim('/').split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { encodePathSegment(it) }
        return "${Config.baseUrl}/remote.php/dav/files/$username/$segments/"
    }
}

/**
 * Grid thumbnail size. Matches what the Nextcloud web UI requests, so the server
 * already has these cached instead of generating a new size from each full-size original.
 */
private const val PREVIEW_PX = 256

internal fun encodePathSegment(segment: String): String =
    java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
