package com.eeinspired.mantel.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `safeRemoteName` is a security boundary: its output becomes a WebDAV path segment. */
class MediaStagingNameTest {

    private fun name(raw: String) = RemoteNames.safe(raw, fallbackStamp = 1L)

    @Test
    fun strips_directories_and_traversal() {
        assertEquals("passwd", name("../../etc/passwd"))
        assertEquals("evil.jpg", name("..\\..\\evil.jpg"))
        assertEquals("upload_1", name(".."))
        assertEquals("upload_1", name("/"))
    }

    @Test
    fun removes_control_and_server_reserved_characters() {
        assertEquals("a_b_c_d.jpg", name("a:b*c?d.jpg"))
        assertFalse(name("x\ny.jpg").contains('\n'))
    }

    @Test
    fun trims_leading_dots_and_trailing_dots_and_spaces() {
        assertEquals("hidden.jpg", name(".hidden.jpg"))
        assertEquals("photo", name("photo. . "))
    }

    @Test
    fun neutralises_in_flight_upload_suffixes() {
        assertEquals("movie.mp4.part_", name("movie.mp4.part"))
        assertEquals("a.FILEPART_", name("a.FILEPART"))
    }

    @Test
    fun caps_by_bytes_and_keeps_the_extension() {
        val long = "é".repeat(150) + ".jpeg" // ~300 bytes, well under 200 characters
        val out = name(long)
        assertTrue(out.toByteArray(Charsets.UTF_8).size <= 200)
        assertTrue(out, out.endsWith(".jpeg"))
    }

    @Test
    fun blank_falls_back_to_generated_name() {
        assertEquals("upload_1", name(""))
        assertEquals("upload_1", name("   "))
    }
}
