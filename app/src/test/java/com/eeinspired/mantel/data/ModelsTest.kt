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
            "${BuildConfig.BASE_URL}/remote.php/dav/files/bob/A%20&%20B/Trip%202026/",
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
            "${BuildConfig.BASE_URL}/core/preview?fileId=42&x=256&y=256&mimeFallback=true&a=0",
            base.previewUrl(),
        )
        assertNull(base.copy(hasPreview = false).previewUrl())
        assertNull(base.copy(fileId = null).previewUrl())
    }

    @Test
    fun uploadCollectionUrl_keeps_plus_literal_and_encodes_odd_user_ids() {
        assertEquals(
            "${BuildConfig.BASE_URL}/remote.php/dav/files/a%20b%3Fc/Kids%20+%20Pets/",
            destination(5, "/Kids + Pets").uploadCollectionUrl("a b?c"),
        )
    }

    @Test
    fun hrefs_decode_as_path_segments_not_form_data() {
        assertEquals(
            listOf("remote.php", "dav", "files", "bob", "a+b c.jpg"),
            hrefSegments("/remote.php/dav/files/bob/a+b%20c.jpg"),
        )
        // A server-supplied absolute URL can never move the request to another host.
        assertEquals("/x/y.jpg", hrefPath("https://evil.example/x/y.jpg"))
    }

    @Test
    fun credentials_never_print_the_password() {
        val text = Credentials("bob", "s3cret-pass", "bob-uid").toString()
        assertFalse(text, "s3cret-pass" in text)
        assertTrue(text, "bob-uid" in text)
        assertEquals("Basic Ym9iOnMzY3JldC1wYXNz", Credentials("bob", "s3cret-pass").basicAuthHeader())
    }
}
