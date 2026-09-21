package com.eeinspired.mantel.data

import com.eeinspired.mantel.telemetry.Telemetry
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * One process-wide connection pool and dispatcher; variants come from `newBuilder()` so
 * they share it. (Previously every worker, screen and repository built its own client,
 * so nothing was ever reused across files.)
 */
object HttpClients {

    private const val CONNECT_TIMEOUT_SECONDS = 20L
    private const val STALL_TIMEOUT_SECONDS = 60L
    private const val JSON_CALL_TIMEOUT_SECONDS = 90L

    /** TLS 1.2+ with strong ciphers only, no cleartext, and no https→http redirects. */
    val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
            .followSslRedirects(false)
            .addInterceptor(BreadcrumbInterceptor)
            .also(NetworkDebug::install) // no-op in release
            .build()
    }

    /**
     * Uploads and WebDAV. The read/write timeouts bound a *stalled* socket, not the whole
     * transfer, so they're safe for multi-GB bodies — and they're what fails a dead
     * connection instead of hanging forever.
     */
    val transfer: OkHttpClient by lazy {
        base.newBuilder()
            .readTimeout(STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(STALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Short OCS/PROPFIND calls, with an overall deadline. */
    val api: OkHttpClient by lazy {
        transfer.newBuilder().callTimeout(JSON_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    }
}

/**
 * Leaves a `METHOD route -> status (ms)` breadcrumb per request so a crash report shows the
 * last network activity. Routes are a fixed vocabulary — never a path, user or file name —
 * and thumbnail fetches are skipped (a grid makes hundreds).
 */
private object BreadcrumbInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val route = routeOf(request.url.encodedPath) ?: return chain.proceed(request)
        val started = System.nanoTime()
        return try {
            chain.proceed(request).also {
                Telemetry.breadcrumb("${request.method} $route -> ${it.code} (${elapsedMs(started)}ms)")
            }
        } catch (e: IOException) {
            Telemetry.breadcrumb("${request.method} $route -> ${e.javaClass.simpleName} (${elapsedMs(started)}ms)")
            throw e
        }
    }

    private fun elapsedMs(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun routeOf(path: String): String? = when {
        path.contains("/cloud/user") -> "ocs/user"
        path.contains("/files_sharing/") -> "ocs/shares"
        path.contains("/dav/uploads/") -> if (path.endsWith("/.file")) "dav/assemble" else "dav/staging"
        path.contains("/dav/files/") -> "dav/files"
        else -> null // /core/preview, media downloads
    }
}
