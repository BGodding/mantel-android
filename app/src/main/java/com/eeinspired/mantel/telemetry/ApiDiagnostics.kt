package com.eeinspired.mantel.telemetry

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Builds crash-report context for an unexpected server response **without content**.
 *
 * `org.json` exception messages embed the entire input (`... at character N of {...}`),
 * so messages are never forwarded. What *is* useful for diagnosing server API drift is
 * the shape: HTTP status, content type, size, and the field *names* present — e.g.
 * `json{ocs{data[3]{id,item_type,...},meta{...}}}` — which tells us a key was renamed or
 * an HTML error page came back, without a single value (no paths, names or ids).
 */
object ApiDiagnostics {

    private const val MAX_DEPTH = 4
    private const val MAX_KEYS = 24
    private const val MAX_SHAPE_CHARS = 400
    private val HTML_START = Regex("^<(!doctype html|html)", RegexOption.IGNORE_CASE)
    private val MISSING_KEY = Regex("""^No value for [A-Za-z0-9_.:-]{1,64}$""")

    fun describe(status: Int, contentType: String?, body: String?, failure: Throwable): String = buildString {
        append("status=").append(status)
        append(" ct=").append(contentType?.substringBefore(';')?.trim()?.take(64) ?: "none")
        append(" len=").append(body?.length ?: -1)
        append(" error=").append(failure.javaClass.simpleName)
        // "No value for <key>" is schema, not data, and is the single most useful hint.
        failure.message?.takeIf { MISSING_KEY.matches(it) }?.let { append(" (").append(it).append(')') }
        if (body != null) append(" shape=").append(shapeOf(body))
    }

    /** Position-only description for XML failures (the parser message quotes the document). */
    fun describeXml(status: Int, contentType: String?, failure: Throwable, line: Int, column: Int): String =
        "status=$status ct=${contentType?.substringBefore(';')?.trim()?.take(64) ?: "none"} " +
            "error=${failure.javaClass.simpleName} at=$line:$column"

    fun shapeOf(body: String): String {
        val text = body.trimStart()
        val shape = when {
            text.isEmpty() -> "empty"
            text.startsWith("<") -> if (HTML_START.containsMatchIn(text)) "html" else "xml"
            text.startsWith("{") || text.startsWith("[") -> jsonShape(text)
            else -> "text"
        }
        return shape.take(MAX_SHAPE_CHARS)
    }

    private fun jsonShape(text: String): String = try {
        val node = if (text.startsWith("{")) JSONObject(text) else JSONArray(text)
        "json" + shape(node, 0)
    } catch (_: JSONException) {
        "json-invalid"
    }

    private fun shape(node: Any?, depth: Int): String = when (node) {
        is JSONObject -> if (depth >= MAX_DEPTH) "{…}" else objectShape(node, depth)
        is JSONArray -> "[${node.length()}]" + if (node.length() > 0 && depth < MAX_DEPTH) {
            shape(node.opt(0), depth + 1).takeIf { it.startsWith("{") } ?: ""
        } else {
            ""
        }
        else -> ""
    }

    private fun objectShape(node: JSONObject, depth: Int): String =
        node.keys().asSequence().take(MAX_KEYS).joinToString(",", "{", "}") { key ->
            key + shape(node.opt(key), depth + 1).let { if (it.startsWith("{") || it.startsWith("[")) it else "" }
        }
}
