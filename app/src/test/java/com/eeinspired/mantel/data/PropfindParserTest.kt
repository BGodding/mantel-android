package com.eeinspired.mantel.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringReader

class PropfindParserTest {

    private fun parse(xml: String, self: List<String>?) = parsePropfind(
        KXmlParser().apply {
            setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", false)
            setInput(StringReader(xml))
        },
        self,
    )

    private val listing = """
        <?xml version="1.0"?>
        <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
          <d:response><d:href>/remote.php/dav/files/bob/Grandma/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/files/bob/Grandma/Trip/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
          <d:response><d:href>/remote.php/dav/files/bob/Grandma/a+b%20c.jpg</d:href>
            <d:propstat><d:prop>
              <d:getcontenttype>image/jpeg</d:getcontenttype><d:getcontentlength>1234</d:getcontentlength>
              <d:getlastmodified>Tue, 03 Mar 2026 10:00:00 GMT</d:getlastmodified>
              <oc:fileid>42</oc:fileid><nc:has-preview>true</nc:has-preview><nc:upload_time>1772532000</nc:upload_time>
            </d:prop></d:propstat></d:response>
        </d:multistatus>
    """.trimIndent()

    @Test
    fun parses_files_skips_self_and_subfolders() {
        val items = parse(listing, listOf("remote.php", "dav", "files", "bob", "Grandma"))
        assertEquals(1, items.size)
        with(items.single()) {
            assertEquals("a+b c.jpg", name) // '+' is literal in a path
            assertEquals("/remote.php/dav/files/bob/Grandma/a+b%20c.jpg", href)
            assertEquals("image/jpeg", contentType)
            assertEquals(1234L, sizeBytes)
            assertEquals(1772532000L, lastModifiedEpochSeconds)
            assertEquals("42", fileId)
            assertTrue(hasPreview)
        }
    }

    @Test
    fun without_a_self_path_every_file_is_kept() {
        assertEquals(1, parse(listing, null).size)
    }
}
