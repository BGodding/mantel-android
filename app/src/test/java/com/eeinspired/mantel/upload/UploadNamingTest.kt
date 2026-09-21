package com.eeinspired.mantel.upload

import org.junit.Assert.assertEquals
import org.junit.Test

class UploadNamingTest {
    @Test
    fun numbering_inserts_before_the_last_extension() {
        assertEquals("IMG.jpg", UploadWorker.numbered("IMG.jpg", 0))
        assertEquals("IMG (1).jpg", UploadWorker.numbered("IMG.jpg", 1))
        assertEquals("a.b.tar (2).gz", UploadWorker.numbered("a.b.tar.gz", 2))
        assertEquals("README (3)", UploadWorker.numbered("README", 3))
        assertEquals(".env (1)", UploadWorker.numbered(".env", 1))
    }
}
