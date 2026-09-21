package com.eeinspired.mantel.ui.gallery

import android.content.Context
import com.eeinspired.mantel.data.Config
import com.eeinspired.mantel.data.CredentialStore
import com.eeinspired.mantel.data.HttpClients
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * An [OkHttpClient] that attaches the Nextcloud Basic-auth header, but only for
 * requests to the configured host. Shared by image loading (Coil) and video
 * playback (ExoPlayer) — both fetch private, authenticated media. One instance per
 * process, derived from [HttpClients.base] so it shares the connection pool.
 */
object NextcloudHttpClient {

    private const val PREVIEW_PATH_SUFFIX = "/core/preview"
    private const val PREVIEW_READ_TIMEOUT_SECONDS = 20
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val CALL_TIMEOUT_SECONDS = 90L

    // The per-host cap must stay high enough to keep the server busy: at 3 a gallery
    // of several hundred photos sat queued client-side for minutes while the server
    // idled. 24 matches the Nextcloud web UI; HTTP/2 multiplexes these over one
    // connection, so the extra parallelism is cheap.
    private const val MAX_REQUESTS_PER_HOST = 24

    @Volatile
    private var instance: OkHttpClient? = null

    fun get(context: Context): OkHttpClient =
        instance ?: synchronized(this) { instance ?: build(context.applicationContext).also { instance = it } }

    private fun build(appContext: Context): OkHttpClient {
        val host = Config.baseUrl.toHttpUrlOrNull()?.host
        val store = CredentialStore(appContext)
        val dispatcher = Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST }

        return HttpClients.base.newBuilder()
            .dispatcher(dispatcher)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val creds = store.load()
                var next = chain
                if (request.url.encodedPath.endsWith(PREVIEW_PATH_SUFFIX)) {
                    // A preview the server can't generate just hangs, and every hung
                    // request holds one of the 24 slots until it times out. Give up
                    // quickly on thumbnails so one bad file can't stall the whole grid.
                    next = next.withReadTimeout(PREVIEW_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
                if (creds != null && request.url.host == host) {
                    next.proceed(request.newBuilder().header("Authorization", creds.basicAuthHeader()).build())
                } else {
                    next.proceed(request)
                }
            }
            .build()
    }
}
