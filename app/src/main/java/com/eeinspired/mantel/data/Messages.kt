package com.eeinspired.mantel.data

/**
 * User-facing copy for every network/auth outcome. Centralised so it maps 1:1
 * to the tables in Requirements §7 and API Contract §5.
 */
object Messages {
    const val SESSION_REVOKED =
        "Your access was revoked or your password expired — please log in again."
    const val OFFLINE_CACHED =
        "No connection — showing the last known list. Tap Refresh when you're back online."
    const val SERVER_CACHED =
        "Couldn't reach the server — showing the last known list."
    const val INVALID_CREDENTIALS =
        "That username or app password wasn't accepted. Remember to use an app password, " +
            "not your Nextcloud login password — ask your admin if you don't have one."
    const val NOTHING_STAGED =
        "Couldn't prepare those files — they may be too large, unreadable, or your phone is out of space."
    const val NO_CONNECTION =
        "No connection — check your network and try again."

    fun serverError(code: Int): String =
        if (code > 0) {
            "Server error ($code) — try again in a moment."
        } else {
            "The server sent something unexpected — try again in a moment."
        }

    /** Maps an [com.eeinspired.mantel.upload.UploadWorker] failure to user copy. */
    fun uploadError(error: UploadError?): String = when (error) {
        UploadError.AUTH -> "Your access expired — sign in again, then resend."
        UploadError.NO_CREDENTIALS -> "You're signed out — sign in again, then resend."
        UploadError.FORBIDDEN ->
            "Couldn't upload — you may not have permission to add to this frame, " +
                "or a file with this name already exists there."
        UploadError.CONFLICT -> "A file with this name already exists on the frame."
        UploadError.DEST_MISSING -> "That frame is no longer shared with you."
        UploadError.QUOTA -> "The server is out of storage space."
        UploadError.NETWORK -> "No connection — this one didn't finish. Try again when you're back online."
        UploadError.SERVER -> "The server had a problem — this one didn't finish."
        UploadError.REJECTED -> "The server refused this file."
        UploadError.UNREADABLE_FILE -> "Couldn't read that file from your phone."
        UploadError.BAD_INPUT, null -> "This one didn't finish."
    }
}
