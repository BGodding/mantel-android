package com.eeinspired.mantel.ui.gallery

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.eeinspired.mantel.data.Config
import com.eeinspired.mantel.data.CredentialStore
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * A Coil [ImageLoader] whose network fetches carry the Nextcloud Basic-auth header,
 * but only for requests to the configured host. Preview thumbnails and full images
 * are private, so every request needs credentials.
 */
object NextcloudImageLoader {

    fun create(context: Context): ImageLoader {
        val appContext = context.applicationContext
        val host = Config.baseUrl.toHttpUrlOrNull()?.host
        val store = CredentialStore(appContext)

        val client = OkHttpClient.Builder()
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

        return ImageLoader.Builder(appContext)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}
