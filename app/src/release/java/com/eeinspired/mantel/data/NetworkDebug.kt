package com.eeinspired.mantel.data

import okhttp3.OkHttpClient

/** Release-build variant: no network logging (Requirements §8). */
object NetworkDebug {
    fun install(builder: OkHttpClient.Builder) = Unit
}
