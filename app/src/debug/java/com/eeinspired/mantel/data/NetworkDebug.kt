package com.eeinspired.mantel.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Debug-build variant: attaches an OkHttp logging interceptor (headers level,
 * `Authorization` redacted) so request/response status, `X-OC-Mtime`, and the
 * chunked `MKCOL`/`MOVE` sequence are visible in logcat under the tag `NextcloudHTTP`.
 * The `release` source set provides a no-op with the same signature.
 */
object NetworkDebug {
    fun install(builder: OkHttpClient.Builder) {
        val logger = HttpLoggingInterceptor { message ->
            android.util.Log.d("NextcloudHTTP", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
            redactHeader("Authorization")
            redactHeader("Proxy-Authorization")
        }
        builder.addInterceptor(logger)
    }
}
