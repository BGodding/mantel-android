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
    const val NO_CONNECTION =
        "No connection — check your network and try again."

    fun serverError(code: Int): String =
        if (code > 0) "Server error ($code) — try again in a moment."
        else "The server sent something unexpected — try again in a moment."

    /** Maps an [com.eeinspired.mantel.upload.UploadWorker] error kind to user copy. */
    fun uploadError(kind: String?): String = when (kind) {
        "auth" -> "Your access expired — sign in again, then resend."
        "no_credentials" -> "You're signed out — sign in again, then resend."
        "forbidden" ->
            "Couldn't upload — a file with this name may already exist on the frame, " +
                "or you don't have permission to add to it."
        "dest_missing" -> "That frame is no longer shared with you."
        "quota" -> "The server is out of storage space."
        "network" -> "No connection — this one didn't finish. Try again when you're back online."
        "server" -> "The server had a problem — this one didn't finish."
        "unreadable_file" -> "Couldn't read that file from your phone."
        else -> "This one didn't finish."
    }
}
