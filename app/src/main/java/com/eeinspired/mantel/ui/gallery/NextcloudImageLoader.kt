package com.eeinspired.mantel.ui.gallery

import android.content.Context
import coil3.ImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory

/**
 * A Coil [ImageLoader] whose network fetches carry the Nextcloud Basic-auth header,
 * but only for requests to the configured host. Preview thumbnails and full images
 * are private, so every request needs credentials.
 */
object NextcloudImageLoader {

    fun create(context: Context): ImageLoader {
        val client = NextcloudHttpClient.get(context)
        return ImageLoader.Builder(context.applicationContext)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .build()
    }
}
