package com.eeinspired.mantel.ui.gallery

import android.content.Context
import com.eeinspired.mantel.data.Config
import com.eeinspired.mantel.data.CredentialStore
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * An [OkHttpClient] that attaches the Nextcloud Basic-auth header, but only for
 * requests to the configured host. Shared by image loading (Coil) and video
 * playback (ExoPlayer) — both fetch private, authenticated media.
 */
object NextcloudHttpClient {

    fun create(context: Context): OkHttpClient {
        val appContext = context.applicationContext
        val host = Config.baseUrl.toHttpUrlOrNull()?.host
        val store = CredentialStore(appContext)

        return OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.RESTRICTED_TLS, ConnectionSpec.MODERN_TLS))
            .addInterceptor { chain ->
                val request = chain.request()
                val creds = store.load()
                if (creds != null && request.url.host == host) {
                    chain.proceed(
                        request.newBuilder()
                            .header("Authorization", creds.basicAuthHeader())
                            .build(),
                    )
                } else {
                    chain.proceed(request)
                }
            }
            .build()
    }
}
