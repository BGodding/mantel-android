package com.eeinspired.mantel.ui.gallery

import com.eeinspired.mantel.data.RemoteItem

enum class MediaFilter(val label: String) {
    ALL("All"),
    PHOTOS("Photos"),
    VIDEOS("Videos"),
}

enum class GallerySort(val label: String) {
    RECENTLY_ADDED("Recently added"),
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    LARGEST("Largest first"),
    NAME("Name"),
}

/**
 * "Newest/Oldest" use the file's modified time, which the app sets to the photo's
 * own date on upload (X-OC-Mtime) — effectively the date taken. "Recently added"
 * uses the server's upload time.
 */
fun List<RemoteItem>.filteredAndSorted(filter: MediaFilter, sort: GallerySort): List<RemoteItem> {
    val visible = when (filter) {
        MediaFilter.ALL -> this
        MediaFilter.PHOTOS -> filter { it.isImage }
        MediaFilter.VIDEOS -> filter { it.isVideo }
    }
    return when (sort) {
        GallerySort.RECENTLY_ADDED -> visible.sortedByDescending { it.addedEpochSeconds }
        GallerySort.NEWEST -> visible.sortedByDescending { it.lastModifiedEpochSeconds }
        GallerySort.OLDEST -> visible.sortedBy { it.lastModifiedEpochSeconds }
        GallerySort.LARGEST -> visible.sortedByDescending { it.sizeBytes }
        GallerySort.NAME -> visible.sortedBy { it.name.lowercase() }
    }
}
