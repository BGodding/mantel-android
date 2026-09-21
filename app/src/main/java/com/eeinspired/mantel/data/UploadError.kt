package com.eeinspired.mantel.data

/** Why an upload ended for good. Travels through WorkManager `Data` by [name]. */
enum class UploadError {
    AUTH,
    NO_CREDENTIALS,
    FORBIDDEN,
    CONFLICT,
    DEST_MISSING,
    QUOTA,
    NETWORK,
    SERVER,
    REJECTED,
    UNREADABLE_FILE,
    BAD_INPUT,
    ;

    companion object {
        fun from(name: String?): UploadError? = entries.firstOrNull { it.name == name }
    }
}
