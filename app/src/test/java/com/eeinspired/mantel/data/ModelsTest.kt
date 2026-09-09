package com.eeinspired.mantel.data

import com.eeinspired.mantel.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelsTest {

    private fun destination(permissions: Int, remotePath: String = "/Grandma") =
        Destination(id = "1", displayName = "Grandma", remotePath = remotePath, permissions = permissions)

    @Test
    fun canDelete_reflects_the_delete_bit() {
        assertFalse(destination(Permission.READ or Permission.CREATE).canDelete) // 5 — family default
        assertTrue(destination(Permission.READ or Permission.CREATE or Permission.DELETE).canDelete) // 13
        assertTrue(destination(Permission.DELETE).canDelete)
        assertFalse(destination(Permission.READ).canDelete)
    }

    @Test
    fun uploadCollectionUrl_builds_a_percent_encoded_webdav_path() {
        assertEquals(
            "${BuildConfig.BASE_URL}/remote.php/dav/files/bob/Grandma/",
            destination(5).uploadCollectionUrl("bob"),
        )
        assertEquals(
            "${BuildConfig.BASE_URL}/remote.php/dav/files/bob/A%20%26%20B/Trip%202026/",
            destination(5, "/A & B/Trip 2026").uploadCollectionUrl("bob"),
        )
    }

    @Test
    fun previewUrl_is_null_without_a_usable_preview() {
        val base = RemoteItem(
            href = "/remote.php/dav/files/bob/Grandma/a.jpg",
            name = "a.jpg",
            isDirectory = false,
            contentType = "image/jpeg",
            sizeBytes = 1,
            lastModifiedEpochSeconds = 0,
            fileId = "42",
            hasPreview = true,
        )
        assertEquals(
            "${BuildConfig.BASE_URL}/index.php/core/preview?fileId=42&x=300&y=300&a=1",
            base.previewUrl(),
        )
        assertNull(base.copy(hasPreview = false).previewUrl())
        assertNull(base.copy(fileId = null).previewUrl())
    }
}
