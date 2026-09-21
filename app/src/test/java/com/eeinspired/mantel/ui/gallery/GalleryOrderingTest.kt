package com.eeinspired.mantel.ui.gallery

import com.eeinspired.mantel.data.RemoteItem
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryOrderingTest {

    private fun item(name: String, type: String, size: Long, modified: Long, uploaded: Long = 0) = RemoteItem(
        href = "/$name", name = name, isDirectory = false, contentType = type, sizeBytes = size,
        lastModifiedEpochSeconds = modified, fileId = null, hasPreview = false, uploadedEpochSeconds = uploaded,
    )

    private val a = item("a.jpg", "image/jpeg", size = 10, modified = 300, uploaded = 100)
    private val b = item("B.mp4", "video/mp4", size = 50, modified = 100, uploaded = 400)
    private val c = item("c.jpg", "image/jpeg", size = 30, modified = 200) // no upload time

    private fun names(filter: MediaFilter, sort: GallerySort) =
        listOf(a, b, c).filteredAndSorted(filter, sort).map { it.name }

    @Test
    fun filters_by_media_type() {
        assertEquals(listOf("a.jpg", "c.jpg"), names(MediaFilter.PHOTOS, GallerySort.NEWEST))
        assertEquals(listOf("B.mp4"), names(MediaFilter.VIDEOS, GallerySort.NEWEST))
    }

    @Test
    fun recently_added_falls_back_to_modified_time() {
        // b=400, a=100 uploaded; c has none so uses modified=200
        assertEquals(listOf("B.mp4", "c.jpg", "a.jpg"), names(MediaFilter.ALL, GallerySort.RECENTLY_ADDED))
    }

    @Test
    fun sorts_by_date_size_and_name() {
        assertEquals(listOf("a.jpg", "c.jpg", "B.mp4"), names(MediaFilter.ALL, GallerySort.NEWEST))
        assertEquals(listOf("B.mp4", "c.jpg", "a.jpg"), names(MediaFilter.ALL, GallerySort.OLDEST))
        assertEquals(listOf("B.mp4", "c.jpg", "a.jpg"), names(MediaFilter.ALL, GallerySort.LARGEST))
        assertEquals(listOf("a.jpg", "B.mp4", "c.jpg"), names(MediaFilter.ALL, GallerySort.NAME))
    }
}
