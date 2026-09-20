package com.eeinspired.mantel.data

import android.util.Xml
import com.eeinspired.mantel.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.InputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Outcome of a read-only API call (session check, discovery). */
sealed interface ApiResult<out T> {
    data class Success<T>(val value: T) : ApiResult<T>
    data object Unauthorized : ApiResult<Nothing>
    data class ServerError(val code: Int) : ApiResult<Nothing>
    data class NetworkError(val cause: Throwable) : ApiResult<Nothing>
    data class MalformedResponse(val detail: String) : ApiResult<Nothing>
}

/** Outcome of a WebDAV upload step, shaped to Requirements §7 / API Contract §5. */
sealed interface UploadResult {
    data object Success : UploadResult
    data object Unauthorized : UploadResult // 401 — re-login, never retry
    data object Forbidden : UploadResult // 403 — permission / name collision, never retry
    data object DestinationMissing : UploadResult // 404 — share revoked since last refresh
    data object QuotaExceeded : UploadResult // 507 — server full, never retry
    data class ServerError(val code: Int) : UploadResult // 5xx — retry with backoff
    data class Network(val cause: Throwable) : UploadResult // no response — retry with backoff
}

/**
 * Thin Nextcloud client over OkHttp.
 *
 * OkHttp (not HttpURLConnection) because the chunked-upload protocol needs the
 * WebDAV `MKCOL` and `MOVE` methods, which `HttpURLConnection` refuses. JSON is
 * still plain `org.json` — no parser dependency.
 */
class NextcloudClient(private val baseUrl: String = Config.baseUrl) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS) // large uploads must not hit a write deadline
        .retryOnConnectionFailure(true)
        // TLS 1.2+ with strong cipher suites only; no cleartext fallback.
        .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
        .also(NetworkDebug::install) // no-op in release
        .build()

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
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Authorization", creds.basicAuthHeader())
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { response ->
                when {
                    response.code == 401 -> ApiResult.Unauthorized
                    response.isSuccessful -> {
                        val text = response.body?.string().orEmpty()
                        runCatching { ApiResult.Success(parse(text)) }
                            .getOrElse { ApiResult.MalformedResponse(it.message ?: "unparseable response") }
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
     * API Contract §3 — single PUT. [finalUrl] is the fully-qualified, percent-encoded
     * destination URL; [openStream] must yield a fresh stream each call.
     */
    fun putFile(
        creds: Credentials,
        finalUrl: String,
        mimeType: String,
        mtimeSeconds: Long?,
        sizeBytes: Long,
        openStream: () -> InputStream,
    ): UploadResult = runCall {
        val request = authed(finalUrl, creds)
            .apply { if (mtimeSeconds != null) header("X-OC-Mtime", mtimeSeconds.toString()) }
            .put(streamBody(mimeType, sizeBytes, openStream))
            .build()
        http.newCall(request).execute().use(::classify)
    }

    /**
     * API Contract §4 — chunked upload v2: MKCOL a staging collection, PUT ordered
     * zero-padded chunks, MOVE `.file` to assemble. Verified to work under a
     * Read+Create-only share.
     */
    fun chunkedUpload(
        creds: Credentials,
        username: String,
        finalUrl: String,
        mtimeSeconds: Long?,
        totalBytes: Long,
        openStream: () -> InputStream,
    ): UploadResult {
        val uploadId = UUID.randomUUID().toString()
        val stagingRoot = "$baseUrl/remote.php/dav/uploads/$username/$uploadId"

        val mkcol = runCall {
            http.newCall(authed(stagingRoot, creds).method("MKCOL", null).build()).execute().use(::classify)
        }
        if (mkcol != UploadResult.Success) return mkcol

        return try {
            val chunkFailure = putChunks(stagingRoot, creds, openStream)
            if (chunkFailure != null) {
                bestEffortDelete(stagingRoot, creds)
                return chunkFailure
            }
            val assembled = moveAssembled(stagingRoot, finalUrl, mtimeSeconds, totalBytes, creds)
            if (assembled != UploadResult.Success) bestEffortDelete(stagingRoot, creds)
            assembled
        } catch (e: IOException) {
            bestEffortDelete(stagingRoot, creds)
            UploadResult.Network(e)
        }
    }

    /** PUTs every chunk in order; returns null once all succeed, or the first failure. */
    private fun putChunks(
        stagingRoot: String,
        creds: Credentials,
        openStream: () -> InputStream,
    ): UploadResult? {
        openStream().use { input ->
            var index = 0L
            for (chunk in readChunks(input)) {
                val name = index.toString().padStart(CHUNK_NAME_WIDTH, '0')
                val body = chunk.toRequestBody(OCTET_STREAM, 0, chunk.size)
                val step = runCall {
                    http.newCall(authed("$stagingRoot/$name", creds).put(body).build())
                        .execute().use(::classify)
                }
                if (step != UploadResult.Success) return step
                index++
            }
        }
        return null
    }

    /** Lazily reads fixed-size chunks until [input] is exhausted (a short/empty read ends it). */
    private fun readChunks(input: InputStream): Sequence<ByteArray> =
        generateSequence { input.readNBytes(CHUNK_SIZE_BYTES).takeIf { it.isNotEmpty() } }

    private fun moveAssembled(
        stagingRoot: String,
        finalUrl: String,
        mtimeSeconds: Long?,
        totalBytes: Long,
        creds: Credentials,
    ): UploadResult {
        val move = authed("$stagingRoot/.file", creds)
            .header("Destination", finalUrl)
            .header("OC-Total-Length", totalBytes.toString())
            .apply { if (mtimeSeconds != null) header("X-OC-Mtime", mtimeSeconds.toString()) }
            .method("MOVE", null)
            .build()
        return runCall { http.newCall(move).execute().use(::classify) }
    }

    private fun bestEffortDelete(url: String, creds: Credentials) {
        val result = runCatching {
            http.newCall(authed(url, creds).delete().build()).execute().use { it.code }
        }
        val code = result.getOrNull()
        if (isOrphanedCleanup(result, code)) {
            // Staging collection may be orphaned server-side (API Contract §4).
            val reason = code ?: result.exceptionOrNull()?.javaClass?.simpleName
            Telemetry.recordNonFatal(IllegalStateException("orphaned upload staging: cleanup returned $reason"))
        }
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
            val encodedPath = remotePath.trim('/').split('/')
                .filter { it.isNotEmpty() }
                .joinToString("/") { encodePathSegment(it) }
            val davPath = "/remote.php/dav/files/${creds.username}/$encodedPath/"
            val request = authed(baseUrl + davPath, creds)
                .header("Depth", "1")
                .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML_MEDIA_TYPE))
                .build()
            try {
                http.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 -> ApiResult.Unauthorized
                        response.code == 404 -> ApiResult.ServerError(404)
                        response.isSuccessful || response.code == 207 -> {
                            val stream = response.body?.byteStream()
                                ?: return@use ApiResult.MalformedResponse("empty PROPFIND body")
                            runCatching { ApiResult.Success(parsePropfind(stream, davPath)) }
                                .getOrElse { ApiResult.MalformedResponse(it.message ?: "bad PROPFIND xml") }
                        }
                        else -> ApiResult.ServerError(response.code)
                    }
                }
            } catch (e: IOException) {
                ApiResult.NetworkError(e)
            }
        }

    /** WebDAV `DELETE` of one file. Requires the share's Delete bit; 403 if not granted. */
    fun deleteItem(creds: Credentials, href: String): UploadResult = runCall {
        http.newCall(authed(baseUrl + href, creds).delete().build()).execute().use(::classify)
    }

    private fun parsePropfind(stream: InputStream, selfPath: String): List<RemoteItem> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            // Defence in depth against XXE — KXmlParser already ignores DTDs, but be explicit.
            runCatching { setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) }
            runCatching { setFeature(XmlPullParser.FEATURE_VALIDATION, false) }
            setInput(stream, null)
        }
        val items = mutableListOf<RemoteItem>()
        val entry = PropfindEntry()

        // "Not found" propstat blocks carry only self-closing empty prop elements, so
        // `parser.next() == TEXT` never fires for them — no need to track propstat status.
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> applyPropfindStartTag(parser, entry)
                XmlPullParser.END_TAG -> collectPropfindResponse(parser, entry, selfPath, items)
            }
            event = parser.next()
        }
        return items.sortedByDescending { it.lastModifiedEpochSeconds }
    }

    private fun authed(url: String, creds: Credentials): Request.Builder =
        Request.Builder().url(url).header("Authorization", creds.basicAuthHeader())

    private inline fun runCall(block: () -> UploadResult): UploadResult =
        try {
            block()
        } catch (e: IOException) {
            UploadResult.Network(e)
        }

    private fun classify(response: Response): UploadResult = when (response.code) {
        200, 201, 204 -> UploadResult.Success
        401 -> UploadResult.Unauthorized
        403 -> UploadResult.Forbidden
        404 -> UploadResult.DestinationMissing
        507 -> UploadResult.QuotaExceeded
        in 500..599 -> UploadResult.ServerError(response.code)
        else -> UploadResult.ServerError(response.code)
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

private fun isOrphanedCleanup(result: Result<Int>, code: Int?): Boolean {
    if (result.isFailure) return true
    return code != null && code !in 200..299 && code != 404
}

private fun samePath(hrefA: String, hrefB: String): Boolean =
    hrefA.trimEnd('/').substringAfter("://").substringAfter('/') ==
        hrefB.trimEnd('/').substringAfter("://").substringAfter('/')

private fun decodeName(href: String): String =
    runCatching { URLDecoder.decode(href.trimEnd('/').substringAfterLast('/'), "UTF-8") }
        .getOrDefault(href.substringAfterLast('/'))

private fun parseHttpDate(raw: String?): Long {
    if (raw.isNullOrBlank()) return 0L
    return runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(raw)!!.time / 1000
    }.getOrDefault(0L)
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
    selfPath: String,
    items: MutableList<RemoteItem>,
) {
    if (parser.name?.substringAfterLast(':')?.lowercase() != "response") return
    val href = entry.href ?: return
    if (entry.isDirectory || samePath(href, selfPath)) return
    items += RemoteItem(
        href = href,
        name = decodeName(href),
        isDirectory = false,
        contentType = entry.contentType,
        sizeBytes = entry.sizeBytes,
        lastModifiedEpochSeconds = entry.lastModifiedEpochSeconds,
        fileId = entry.fileId,
        hasPreview = entry.hasPreview,
        uploadedEpochSeconds = entry.uploadedEpochSeconds,
    )
}
