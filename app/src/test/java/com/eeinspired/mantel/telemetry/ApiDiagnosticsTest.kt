package com.eeinspired.mantel.telemetry

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiDiagnosticsTest {

    private val body =
        """{"ocs":{"meta":{"status":"ok"},"data":[{"id":"7","file_target":"/Grandma-Secret","owner":"alice"}]}}"""

    @Test
    fun shape_lists_key_names_but_no_values() {
        val shape = ApiDiagnostics.shapeOf(body)
        assertTrue(shape, shape.startsWith("json{ocs{"))
        assertTrue(shape, "data[1]" in shape)
        assertTrue(shape, "file_target" in shape)
        assertFalse(shape, "Grandma" in shape || "alice" in shape)
    }

    @Test
    fun describe_never_forwards_the_exception_message_or_body() {
        // org.json messages embed the whole input; a description must not.
        val leaky = JSONException("Unterminated object at character 9 of $body")
        val text = ApiDiagnostics.describe(200, "application/json; charset=utf-8", body, leaky)
        assertTrue(text, "status=200" in text && "ct=application/json" in text && "error=JSONException" in text)
        assertFalse(text, "Grandma" in text || "alice" in text || "Unterminated" in text)
    }

    @Test
    fun describe_keeps_the_missing_key_hint() {
        val text = ApiDiagnostics.describe(200, null, "{}", JSONException("No value for ocs"))
        assertTrue(text, "(No value for ocs)" in text)
    }

    @Test
    fun recognises_html_error_pages_and_empty_bodies() {
        assertEquals("html", ApiDiagnostics.shapeOf("<!DOCTYPE html><html>captive portal</html>"))
        assertEquals("empty", ApiDiagnostics.shapeOf("  "))
        assertEquals("json-invalid", ApiDiagnostics.shapeOf("{not json"))
    }

    @Test
    fun xml_description_is_position_only() {
        val failure = RuntimeException("<d:href>/secret</d:href>")
        val text = ApiDiagnostics.describeXml(207, "application/xml", failure, 3, 14)
        assertTrue(text, "at=3:14" in text)
        assertFalse(text, "secret" in text)
    }
}
