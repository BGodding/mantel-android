package com.eeinspired.mantel.data

import com.eeinspired.mantel.telemetry.Telemetry
import com.eeinspired.mantel.telemetry.TelemetryBackend
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NextcloudClientTest {

    private val server = MockWebServer()
    private val creds = Credentials("bob@example.com", "app-pass", userId = "bob")
    private val recorded = mutableListOf<Throwable>()

    @Before
    fun setUp() {
        Telemetry.backend = object : TelemetryBackend {
            override fun logEvent(name: String, params: Map<String, Any>) = Unit
            override fun log(message: String) = Unit
            override fun setKey(key: String, value: Any) = Unit
            override fun recordException(throwable: Throwable) {
                recorded += throwable
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun client() = NextcloudClient(
        baseUrl = server.url("/").toString().trimEnd('/'),
        transfer = OkHttpClient(),
        api = OkHttpClient(),
        parserFactory = { KXmlParser() },
    )

    private fun url(path: String) = server.url(path).toString()

    private fun respond(code: Int, body: String = "", contentType: String? = null) = server.enqueue(
        MockResponse.Builder().code(code).body(body).apply {
            if (contentType != null) addHeader("Content-Type", contentType)
        }.build(),
    )

    @Test
    fun an_html_reply_is_reported_by_shape_never_by_content() = runBlocking {
        respond(200, "<html><body>Hello alice, sign in to the hotel wifi</body></html>", "text/html; charset=utf-8")

        val result = client().validateSession(creds) as ApiResult.MalformedResponse

        assertTrue(result.detail, "ct=text/html" in result.detail && "shape=html" in result.detail)
        assertFalse(result.detail, "alice" in result.detail || "hotel" in result.detail)
    }

    @Test
    fun session_check_returns_the_servers_uid() = runBlocking {
        respond(200, """{"ocs":{"data":{"id":"bob","displayname":"Bob B"}}}""", "application/json")
        val result = client().validateSession(creds) as ApiResult.Success
        assertEquals(UserInfo("bob", "Bob B"), result.value)
    }

    @Test
    fun put_sends_no_overwrite_guard_and_maps_statuses() = runBlocking {
        val cases = mapOf(
            201 to UploadResult.Success,
            403 to UploadResult.Forbidden,
            404 to UploadResult.DestinationMissing,
            409 to UploadResult.DestinationMissing,
            412 to UploadResult.Conflict,
            507 to UploadResult.QuotaExceeded,
            429 to UploadResult.ServerError(429),
            423 to UploadResult.ServerError(423),
            503 to UploadResult.ServerError(503),
            400 to UploadResult.Rejected(400),
            413 to UploadResult.Rejected(413),
        )
        for ((code, expected) in cases) {
            respond(code)
            val actual = client().putFile(creds, url("/dav/a.jpg"), "image/jpeg", 1_700_000_000L, 3L) {
                ByteArrayInputStream(byteArrayOf(1, 2, 3))
            }
            assertEquals("HTTP $code", expected, actual)
            val request = server.takeRequest()
            assertEquals("*", request.headers["If-None-Match"])
            assertEquals("1700000000", request.headers["X-OC-Mtime"])
        }
    }

    @Test
    fun a_retried_chunked_upload_resumes_instead_of_restarting() = runBlocking {
        val chunk = 10 * 1024 * 1024
        val bytes = ByteArray(chunk + 5)
        respond(405) // MKCOL: staging already exists — this is a retry
        respond(
            207,
            """<d:multistatus xmlns:d="DAV:">
                 <d:response><d:href>/remote.php/dav/uploads/bob/up-1/</d:href>
                   <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                 <d:response><d:href>/remote.php/dav/uploads/bob/up-1/000000000000000</d:href>
                   <d:propstat><d:prop><d:getcontentlength>$chunk</d:getcontentlength></d:prop></d:propstat></d:response>
               </d:multistatus>""",
            "application/xml",
        )
        respond(201) // chunk 1 (5 bytes)
        respond(201) // MOVE

        val result = client().chunkedUpload(creds, "up-1", url("/dav/final.mp4"), null, bytes.size.toLong()) {
            ByteArrayInputStream(bytes)
        }

        assertEquals(UploadResult.Success, result)
        val methods = generateSequence { server.takeRequest(200, TimeUnit.MILLISECONDS) }.toList()
        assertEquals(listOf("MKCOL", "PROPFIND", "PUT", "MOVE"), methods.map { it.method })
        assertTrue(methods[2].url.encodedPath.endsWith("/000000000000001")) // chunk 0 was skipped
        assertEquals("F", methods[3].headers["Overwrite"])
        assertEquals(bytes.size.toString(), methods[3].headers["OC-Total-Length"])
        // Paths are keyed by the server uid, not the typed login name.
        assertTrue(methods[0].url.encodedPath, "/uploads/bob/up-1" in methods[0].url.encodedPath)
    }

    @Test
    fun cancelling_the_caller_closes_the_socket() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).headersDelay(30, TimeUnit.SECONDS).build())
        val job = async(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            client().putFile(creds, url("/dav/slow.jpg"), "image/jpeg", null, 1L) {
                ByteArrayInputStream(byteArrayOf(1))
            }
        }
        delay(300.milliseconds) // request is now in flight, waiting on the server
        val started = System.nanoTime()
        job.cancel()
        withTimeout(5.seconds) { job.join() }
        assertTrue("cancellation must return promptly", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
    }

    @Test
    fun a_server_supplied_absolute_href_cannot_redirect_delete_credentials() = runBlocking {
        respond(204)
        val result = client().deleteItem(creds, "https://evil.example/remote.php/dav/files/bob/x.jpg")
        assertEquals(UploadResult.Success, result)
        val request = server.takeRequest()
        assertEquals("/remote.php/dav/files/bob/x.jpg", request.url.encodedPath)
        assertEquals(server.hostName, request.url.host)
    }

    @Test
    fun folder_listing_uses_the_uid_and_decodes_names() = runBlocking {
        respond(
            207,
            """<d:multistatus xmlns:d="DAV:">
                 <d:response><d:href>/remote.php/dav/files/bob/Kids%20%26%20Pets/</d:href>
                   <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
                 <d:response><d:href>/remote.php/dav/files/bob/Kids%20%26%20Pets/a+b.jpg</d:href>
                   <d:propstat><d:prop><d:getcontenttype>image/jpeg</d:getcontenttype></d:prop></d:propstat></d:response>
               </d:multistatus>""",
            "application/xml",
        )
        val result = client().listFolder(creds, "/Kids & Pets") as ApiResult.Success
        assertEquals(listOf("a+b.jpg"), result.value.map { it.name })
        assertEquals("/remote.php/dav/files/bob/Kids%20&%20Pets/", server.takeRequest().url.encodedPath)
    }
}
