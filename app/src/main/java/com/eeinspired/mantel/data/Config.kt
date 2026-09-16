package com.eeinspired.mantel.data

import android.content.Context
import androidx.core.content.edit
import com.eeinspired.mantel.BuildConfig
import com.eeinspired.mantel.telemetry.Telemetry
import java.net.URI

/**
 * Server base URL.
 *
 * Bundled default is [DEFAULT_BASE_URL]. A Firebase Remote Config value
 * (`server_base_url`, see [com.eeinspired.mantel.config.RemoteFlags]) can override
 * it — but only if it passes [isAllowed] (HTTPS, origin only, host on the
 * compiled-in allowlist), so a compromised Remote Config cannot redirect the app
 * (and the app password) to an arbitrary server.
 *
 * A validated value is cached and used from the **next** launch on — never swapped
 * mid-session, so every request in a run uses one consistent host.
 */
object Config {

    /** Bundled default origin — from `secrets.properties` (`MANTEL_BASE_URL`) at build time. */
    const val DEFAULT_BASE_URL: String = BuildConfig.BASE_URL

    /**
     * Hosts the app may ever talk to: the suffix from `secrets.properties`
     * (`MANTEL_ALLOWED_HOST_SUFFIX`), matched exactly or as `.<suffix>`.
     */
    private val ALLOWED_HOSTS: (String) -> Boolean = { host ->
        val suffix = BuildConfig.ALLOWED_HOST_SUFFIX
        host == suffix || host.endsWith(".$suffix")
    }

    @Volatile
    var baseUrl: String = DEFAULT_BASE_URL
        private set

    /** Call once, synchronously, before any network use (MainActivity.onCreate). */
    fun initialize(context: Context) {
        val cached = prefs(context).getString(KEY_BASE_URL, null)
        if (cached != null && isAllowed(cached)) baseUrl = cached
        Telemetry.setKey("server_host", hostOf(baseUrl))
    }

    /** Apply a Remote Config candidate if well-formed and allow-listed; cache it for next launch. */
    fun applyRemote(context: Context, candidate: String?) {
        val value = candidate?.trim()?.trimEnd('/').orEmpty()
        if (value.isEmpty() || value == baseUrl) return
        if (!isAllowed(value)) {
            if (value != DEFAULT_BASE_URL) {
                Telemetry.recordNonFatal(
                    IllegalArgumentException("rejected server_base_url from Remote Config: host=${hostOf(value)}"),
                )
            }
            return
        }
        prefs(context).edit { putString(KEY_BASE_URL, value) }
        // Takes effect next launch via initialize(); do not mutate baseUrl here.
    }

    fun isAllowed(url: String): Boolean = try {
        val uri = URI(url)
        uri.scheme == "https" &&
            !uri.host.isNullOrBlank() &&
            ALLOWED_HOSTS(uri.host) &&
            (uri.path.isNullOrEmpty() || uri.path == "/") &&
            uri.query == null &&
            uri.userInfo == null
    } catch (_: Exception) {
        false
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host.orEmpty() }.getOrDefault("")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "mantel_config"
    private const val KEY_BASE_URL = "base_url"
}
