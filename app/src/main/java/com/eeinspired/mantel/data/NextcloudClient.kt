package com.eeinspired.mantel.data

import android.util.Xml
import com.eeinspired.mantel.telemetry.ApiDiagnostics
import com.eeinspired.mantel.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONException
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resumeWithException

/** Outcome of a read-only API call (session check, discovery). */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data object Unauthorized : ApiResult<Nothing>
    data class ServerError(val code: Int) : ApiResult<Nothing>
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>

    /** [detail] is an [ApiDiagnostics] description (shape only) — safe to send to crash reporting. */
    data class MalformedResponse(val detail: String) : ApiResult<Nothing>
}

/** Outcome of a WebDAV step, shaped to Requirements §7 / API Contract §5. */
sealed interface UploadResult {
    data object Success : UploadResult
    data object Unauthorized : UploadResult // 401 — re-login, never retry
    data object Forbidden : UploadResult // 403 — permission / name collision, never retry
    data object DestinationMissing : UploadResult // 404/409 — share revoked or parent gone
    data object Conflict : UploadResult // 412 — a file with this name already exists
    data object QuotaExceeded : UploadResult // 507 — server full, never retry
    data class Rejected(val code: Int) : UploadResult // other 4xx — the request itself is wrong, never retry
    data class ServerError(val code: Int) : UploadResult // 5xx / 408 / 423 / 429 — retry with backoff
    data class Network(val cause: Throwable) : UploadResult // no response — retry with backoff
}

/**
 * Thin Nextcloud client over OkHttp.
 *
 * OkHttp (not HttpURLConnection) because the chunked-upload protocol needs the
 * WebDAV `MKCOL` and `MOVE` methods, which `HttpURLConnection` refuses. JSON is
 * still plain `org.json` — no parser dependency.
 *
 * Every call is asynchronous under the hood ([await]), so cancelling the calling coroutine
 * — WorkManager stopping a worker, a screen leaving composition — cancels the socket too.
 */
@Suppress("TooManyFunctions") // one function per endpoint plus their private request helpers
class NextcloudClient(
    private val baseUrl: String = Config.baseUrl,
    private val transfer: OkHttpClient = HttpClients.transfer,
    private val api: OkHttpClient = HttpClients.api,
    private val parserFactory: () -> XmlPullParser = { Xml.newPullParser() },
) {

    // ---- Read-only OCS calls -------------------------------------------------

    /** API Contract §1 — call on launch and before trusting cached state. */
    suspend fun validateSession(creds: Credentials): ApiResult<UserInfo> =
        getJson("/ocs/v1.php/cloud/user?format=json", creds) { body ->
            val data = JSONObject(body).getJSONObject("ocs").getJSONObject("data")
            val id = data.optString("id")
            UserInfo(id = id, displayName = data.optString("displayname").ifBlank { id })
        }

    /** API Contract §2 — the live "what can I upload to right now" call. */
    suspend fun listDestinations(creds: Credentials): ApiResult<List<Destination>> =
        getJson(
            "/ocs/v2.php/apps/files_sharing/api/v1/shares?shared_with_me=true&format=json",
            creds,
        ) { body ->
            val shares = JSONObject(body).getJSONObject("ocs").getJSONArray("data")
            buildList {
                for (i in 0 until shares.length()) {
                    val share = shares.getJSONObject(i)
                    val isFolder = share.optString("item_type") == "folder"
                    val permissions = share.optInt("permissions", 0)
                    val target = share.optString("file_target")
                        .ifBlank { share.optString("path") }
                        .trim()
                    if (isFolder && permissions and PERMISSION_CREATE != 0 && target.isNotBlank()) {
                        val normalised = "/" + target.trim('/')
                        add(
                            Destination(
                                id = share.optString("id").ifBlank { normalised },
                                displayName = normalised.trimStart('/').ifBlank { normalised },
                                remotePath = normalised,
                                permissions = permissions,
                            ),
                        )
                    }
                }
            }
        }

    private suspend fun <T> getJson(
        path: String,
        creds: Credentials,
        parse: (String) -> T,
    ): ApiResult<T> = withContext(Dispatchers.IO) {
        val request = try {
            Request.Builder()
                .url(baseUrl + path)
                .header("Authorization", creds.basicAuthHeader())
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .get()
                .build()
        } catch (e: IllegalArgumentException) {
            return@withContext ApiResult.MalformedResponse("bad request url: ${e.javaClass.simpleName}")
        }
        try {
            api.newCall(request).await().use { response ->
                when {
                    response.code == 401 -> ApiResult.Unauthorized
                    response.isSuccessful -> {
                        val text = response.body?.string().orEmpty()
                        try {
                            ApiResult.Success(parse(text))
                        } catch (e: JSONException) {
                            ApiResult.MalformedResponse(
                                ApiDiagnostics.describe(response.code, response.header("Content-Type"), text, e),
                            )
                        }
                    }
                    else -> ApiResult.ServerError(response.code)
                }
            }
        } catch (e: IOException) {
            ApiResult.NetworkError(e)
        }
    }

    // ---- WebDAV uploads ----------------------------------------------------

    /**
     * API Contract §3 — single PUT. [finalUrl] is the fully-qualified destination URL;
     * [openStream] must yield a fresh stream each call. Unless [overwrite], the request
     * carries `If-None-Match: *` so an existing file yields [UploadResult.Conflict]
     * instead of being silently replaced on shares that grant Update.
     */
    suspend fun putFile(
        creds: Credentials,
        finalUrl: String,
        mimeType: String,
        mtimeSeconds: Long?,
        sizeBytes: Long,
        overwrite: Boolean = false,
        openStream: () -> InputStream,
    ): UploadResult = runCall {
        val request = authed(finalUrl.toHttpUrl(), creds)
            .apply { if (mtimeSeconds != null) header("X-OC-Mtime", mtimeSeconds.toString()) }
            .apply { if (!overwrite) header("If-None-Match", "*") }
            .put(streamBody(mimeType, sizeBytes, openStream))
            .build()
        transfer.newCall(request).await().use(::classify)
    }

    /**
     * API Contract §4 — chunked upload v2: MKCOL a staging collection, PUT ordered
     * zero-padded chunks, MOVE `.file` to assemble. Verified to work under a
     * Read+Create-only share.
     *
     * [uploadId] must be stable across retries of the same file: if the staging collection
     * already exists (405 on MKCOL) the chunks already there are listed and skipped, so a
     * failure at chunk 99 of 100 resumes instead of restarting. Staging is deliberately left in
     * place on retryable failures; call [discardStaging] once the outcome is final.
     */
    @Suppress("LongParameterList")
    suspend fun chunkedUpload(
        creds: Credentials,
        uploadId: String,
        finalUrl: String,
        mtimeSeconds: Long?,
        totalBytes: Long,
        overwrite: Boolean = false,
        openStream: () -> InputStream,
    ): UploadResult = withContext(Dispatchers.IO) {
        val stagingRoot = stagingUrl(creds.userId, uploadId)

        var resuming = false
        val mkcol = runCall {
            transfer.newCall(authed(stagingRoot, creds).method("MKCOL", null).build()).await().use {
                if (it.code == HTTP_METHOD_NOT_ALLOWED) { // already exists: this is a retry of the same upload
                    resuming = true
                    UploadResult.Success
                } else {
                    classify(it)
                }
            }
        }
        if (mkcol != UploadResult.Success) return@withContext mkcol

        val existing = if (resuming) listStaging(stagingRoot, creds) else emptyMap()
        val chunkFailure = try {
            putChunks(stagingRoot, creds, existing, openStream)
        } catch (e: IOException) { // unreadable staged file
            return@withContext UploadResult.Network(e)
        }
        chunkFailure ?: moveAssembled(stagingRoot, finalUrl, mtimeSeconds, totalBytes, overwrite, creds)
    }

    /** Best-effort removal of a staging collection once its upload has reached a final outcome. */
    suspend fun discardStaging(creds: Credentials, uploadId: String) {
        val url = stagingUrl(creds.userId, uploadId)
        val result = try {
            transfer.newCall(authed(url, creds).delete().build()).await().use { it.code }
        } catch (e: IOException) {
            Telemetry.recordNonFatal(IllegalStateException("orphaned upload staging: ${e.javaClass.simpleName}"))
            return
        }
        // 404 = never created or already assembled; anything else non-2xx may leave an orphan (API Contract §4).
        if (result !in 200..299 && result != 404) {
            Telemetry.recordNonFatal(IllegalStateException("orphaned upload staging: cleanup returned $result"))
        }
    }

    /**
     * True if [fileUrl] already exists with exactly [sizeBytes] and modification time [mtimeSeconds]
     * — i.e. an earlier attempt's PUT succeeded but its response was lost. Needs Read on the
     * share; any failure means "unknown".
     */
    suspend fun remoteFileMatches(
        creds: Credentials,
        fileUrl: String,
        sizeBytes: Long,
        mtimeSeconds: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val request = authed(fileUrl.toHttpUrl(), creds)
                    .header("Depth", "0")
                    .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                    .build()
                api.newCall(request).await().use { response ->
                    response.code == HTTP_MULTI_STATUS && parseItems(response, null).any {
                        it.sizeBytes == sizeBytes && it.lastModifiedEpochSeconds == mtimeSeconds
                    }
                }
            } catch (_: IOException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }

    private fun stagingUrl(userId: String, uploadId: String): HttpUrl =
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("remote.php/dav/uploads")
            .addPathSegment(userId)
            .addPathSegment(uploadId)
            .build()

    private suspend fun listStaging(stagingRoot: HttpUrl, creds: Credentials): Map<String, Long> = try {
        val request = authed(stagingRoot, creds)
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
            .build()
        api.newCall(request).await().use { response ->
            if (response.code != HTTP_MULTI_STATUS) {
                emptyMap()
            } else {
                parseItems(response, stagingRoot.pathSegments.filter { it.isNotEmpty() })
                    .associate { it.name to it.sizeBytes }
            }
        }
    } catch (_: IOException) {
        emptyMap() // can't tell what's there — just send everything again
    }

    /** PUTs every chunk in order (skipping intact [existing] ones); null once all succeed, or the first failure. */
    private suspend fun putChunks(
        stagingRoot: HttpUrl,
        creds: Credentials,
        existing: Map<String, Long>,
        openStream: () -> InputStream,
    ): UploadResult? = openStream().use { input ->
        var index = 0L
        for (chunk in readChunks(input)) {
            val name = index++.toString().padStart(CHUNK_NAME_WIDTH, '0')
            if (existing[name] == chunk.size.toLong()) continue
            val step = putChunk(stagingRoot.newBuilder().addPathSegment(name).build(), creds, chunk)
            if (step != UploadResult.Success) return@use step
        }
        null
    }

    private suspend fun putChunk(url: HttpUrl, creds: Credentials, chunk: ByteArray): UploadResult = runCall {
        val body = chunk.toRequestBody(OCTET_STREAM, 0, chunk.size)
        transfer.newCall(authed(url, creds).put(body).build()).await().use(::classify)
    }

    /** Lazily reads fixed-size chunks until [input] is exhausted (a short/empty read ends it). */
    private fun readChunks(input: InputStream): Sequence<ByteArray> =
        generateSequence { input.readNBytes(CHUNK_SIZE_BYTES).takeIf { it.isNotEmpty() } }

    private suspend fun moveAssembled(
        stagingRoot: HttpUrl,
        finalUrl: String,
        mtimeSeconds: Long?,
        totalBytes: Long,
        overwrite: Boolean,
        creds: Credentials,
    ): UploadResult {
        val source = stagingRoot.newBuilder().addPathSegment(".file").build()
        val move = authed(source, creds)
            .header("Destination", finalUrl)
            .header("OC-Total-Length", totalBytes.toString())
            .apply { if (mtimeSeconds != null) header("X-OC-Mtime", mtimeSeconds.toString()) }
            .apply { if (!overwrite) header("Overwrite", "F") }
            .method("MOVE", null)
            .build()
        return runCall { transfer.newCall(move).await().use(::classify) }
    }

    // ---- Destination gallery (feature-flagged) ----------------------------------

    /**
     * WebDAV `PROPFIND` (`Depth: 1`) listing of one destination folder's files.
     *
     * NOT covered by the API Contract — verify the response shape and the preview
     * endpoint against the live server before relying on this in production.
     */
    suspend fun listFolder(creds: Credentials, remotePath: String): ApiResult<List<RemoteItem>> =
        withContext(Dispatchers.IO) {
            val folder = try {
                davFolderUrl(baseUrl, creds.userId, remotePath)
            } catch (e: IllegalArgumentException) {
                return@withContext ApiResult.MalformedResponse("bad folder url: ${e.javaClass.simpleName}")
            }
            val request = authed(folder, creds)
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                .build()
            try {
                api.newCall(request).await().use { response ->
                    when {
                        response.code == 401 -> ApiResult.Unauthorized
                        response.code == 404 -> ApiResult.ServerError(404)
                        response.isSuccessful || response.code == HTTP_MULTI_STATUS -> try {
                            ApiResult.Success(parseItems(response, folder.pathSegments.filter { it.isNotEmpty() }))
                        } catch (e: XmlPullParserException) {
                            ApiResult.MalformedResponse(
                                ApiDiagnostics.describeXml(
                                    response.code,
                                    response.header("Content-Type"),
                                    e,
                                    e.lineNumber,
                                    e.columnNumber,
                                ),
                            )
                        }
                        else -> ApiResult.ServerError(response.code)
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e)
            }
        }

    /** WebDAV `DELETE` of one file. Requires the share's Delete bit; 403 if not granted. */
    suspend fun deleteItem(creds: Credentials, href: String): UploadResult = runCall {
        // Resolve against our own origin: a server-supplied href can never redirect the
        // (preemptive) Authorization header to another host.
        val url = baseUrl.toHttpUrl().resolve(hrefPath(href)) ?: return@runCall UploadResult.Rejected(0)
        transfer.newCall(authed(url, creds).delete().build()).await().use(::classify)
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun parseItems(response: Response, selfSegments: List<String>?): List<RemoteItem> {
        val stream = response.body?.byteStream() ?: return emptyList()
        return parsePropfind(newParser(stream), selfSegments)
    }

    private fun newParser(stream: InputStream): XmlPullParser = parserFactory().apply {
        setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        // Defence in depth against XXE — KXmlParser already ignores DTDs, but be explicit.
        runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
        runCatching { setFeature(XmlPullParser.FEATURE_VALIDATION, false) }
        setInput(stream, null)
    }

    private fun authed(url: HttpUrl, creds: Credentials): Request.Builder =
        Request.Builder().url(url).header("Authorization", creds.basicAuthHeader())

    private inline fun runCall(block: () -> UploadResult): UploadResult =
        try {
            block()
        } catch (e: IOException) {
            UploadResult.Network(e)
        } catch (_: IllegalArgumentException) { // unusable URL/header value
            UploadResult.Rejected(0)
        }

    private fun classify(response: Response): UploadResult = when (val code = response.code) {
        200, 201, 204 -> UploadResult.Success
        401 -> UploadResult.Unauthorized
        403 -> UploadResult.Forbidden
        404, 409 -> UploadResult.DestinationMissing
        412 -> UploadResult.Conflict
        507 -> UploadResult.QuotaExceeded
        HTTP_REQUEST_TIMEOUT, HTTP_LOCKED, HTTP_TOO_MANY_REQUESTS, in 500..599 -> UploadResult.ServerError(code)
        else -> UploadResult.Rejected(code)
    }

    private fun streamBody(
        mimeType: String,
        length: Long,
        openStream: () -> InputStream,
    ): RequestBody = object : RequestBody() {
        override fun contentType() = mimeType.toMediaTypeOrNull() ?: OCTET_STREAM
        override fun contentLength() = length
        override fun isOneShot() = false
        override fun writeTo(sink: BufferedSink) {
            openStream().use { input -> sink.writeAll(input.source()) }
        }
    }

    private companion object {
        const val PERMISSION_CREATE = 4
        const val CHUNK_SIZE_BYTES = 10 * 1024 * 1024 // 10 MiB — resilient to flaky uplinks
        const val CHUNK_NAME_WIDTH = 15 // zero-padded, lexically sortable (§4)
        const val HTTP_MULTI_STATUS = 207
        const val HTTP_METHOD_NOT_ALLOWED = 405
        const val HTTP_REQUEST_TIMEOUT = 408
        const val HTTP_LOCKED = 423
        const val HTTP_TOO_MANY_REQUESTS = 429
        val OCTET_STREAM = "application/octet-stream".toMediaTypeOrNull()!!
        val XML_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaTypeOrNull()

        val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns">
              <d:prop>
                <d:resourcetype/>
                <d:getcontenttype/>
                <d:getcontentlength/>
                <d:getlastmodified/>
                <oc:fileid/>
                <nc:has-preview/>
                <nc:upload_time/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
    }
}

/**
 * Awaits a call without blocking a thread. Cancelling the coroutine cancels the call
 * (closing the socket mid-transfer), which a blocking `execute()` never did.
 */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        },
    )
}

// ---- PROPFIND parsing -----------------------------------------------------------------

private val HTTP_DATE: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME

private fun parseHttpDate(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return runCatching { ZonedDateTime.parse(raw, HTTP_DATE).toEpochSecond() }.getOrDefault(0L)
}

/**
 * Parses a `207 Multi-Status` body from an already-configured [parser] into files
 * (collections are skipped). [selfSegments], when given, is the listed collection's own path,
 * whose entry is skipped; `null` keeps every file entry (used for `Depth: 0` stat calls).
 */
internal fun parsePropfind(parser: XmlPullParser, selfSegments: List<String>?): List<RemoteItem> {
    val items = mutableListOf<RemoteItem>()
    val entry = PropfindEntry()

    // "Not found" propstat blocks carry only self-closing empty prop elements, so
    // `parser.next() == TEXT` never fires for them — no need to track propstat status.
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        when (event) {
            XmlPullParser.START_TAG -> applyPropfindStartTag(parser, entry)
            XmlPullParser.END_TAG -> collectPropfindResponse(parser, entry, selfSegments, items)
        }
        event = parser.next()
    }
    return items.sortedByDescending { it.lastModifiedEpochSeconds }
}

/** Mutable scratch space for the `<d:response>` currently being parsed. */
private class PropfindEntry {
    var href: String? = null
    var isDirectory = false
    var contentType = ""
    var sizeBytes = 0L
    var lastModifiedEpochSeconds = 0L
    var fileId: String? = null
    var hasPreview = false
    var uploadedEpochSeconds = 0L

    fun reset() {
        href = null
        isDirectory = false
        contentType = ""
        sizeBytes = 0L
        lastModifiedEpochSeconds = 0L
        fileId = null
        hasPreview = false
        uploadedEpochSeconds = 0L
    }
}

private fun applyPropfindStartTag(parser: XmlPullParser, entry: PropfindEntry) {
    when (parser.name?.substringAfterLast(':')?.lowercase()) {
        "response" -> entry.reset()
        "href" -> if (parser.next() == XmlPullParser.TEXT) entry.href = parser.text?.trim()
        "collection" -> entry.isDirectory = true
        "getcontenttype" -> if (parser.next() == XmlPullParser.TEXT) {
            entry.contentType = parser.text?.trim().orEmpty()
        }
        "getcontentlength" -> if (parser.next() == XmlPullParser.TEXT) {
            entry.sizeBytes = parser.text?.trim()?.toLongOrNull() ?: 0L
        }
        "getlastmodified" -> if (parser.next() == XmlPullParser.TEXT) {
            entry.lastModifiedEpochSeconds = parseHttpDate(parser.text?.trim())
        }
        "fileid" -> if (parser.next() == XmlPullParser.TEXT) entry.fileId = parser.text?.trim()
        "upload_time" -> if (parser.next() == XmlPullParser.TEXT) {
            entry.uploadedEpochSeconds = parser.text?.trim()?.toLongOrNull() ?: 0L
        }
        "has-preview" -> if (parser.next() == XmlPullParser.TEXT) {
            entry.hasPreview = parser.text?.trim().equals("true", ignoreCase = true)
        }
    }
}

/** On a `</d:response>`, turns the accumulated [entry] into a [RemoteItem] if it's a file. */
private fun collectPropfindResponse(
    parser: XmlPullParser,
    entry: PropfindEntry,
    selfSegments: List<String>?,
    items: MutableList<RemoteItem>,
) {
    if (parser.name?.substringAfterLast(':')?.lowercase() != "response") return
    val href = entry.href ?: return
    val segments = hrefSegments(href)
    if (entry.isDirectory || (selfSegments != null && segments == selfSegments)) return
    items += RemoteItem(
        href = hrefPath(href),
        // Decoded as a path segment: `+` is literal, `%2B`/`%20` decode as expected.
        name = segments.lastOrNull().orEmpty(),
        isDirectory = false,
        contentType = entry.contentType,
        sizeBytes = entry.sizeBytes,
        lastModifiedEpochSeconds = entry.lastModifiedEpochSeconds,
        fileId = entry.fileId,
        hasPreview = entry.hasPreview,
        uploadedEpochSeconds = entry.uploadedEpochSeconds,
    )
}
