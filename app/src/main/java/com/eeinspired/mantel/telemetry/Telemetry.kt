package com.eeinspired.mantel.telemetry

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/** Where telemetry goes. Firebase in the app; a recording fake in unit tests. */
interface TelemetryBackend {
    fun logEvent(name: String, params: Map<String, Any>)
    fun log(message: String)
    fun setKey(key: String, value: Any)
    fun recordException(throwable: Throwable)
}

private object FirebaseBackend : TelemetryBackend {
    @Volatile
    var analytics: FirebaseAnalytics? = null

    override fun logEvent(name: String, params: Map<String, Any>) {
        val bundle = Bundle().apply {
            params.forEach { (key, value) ->
                when (value) {
                    is Int -> putLong(key, value.toLong())
                    is Long -> putLong(key, value)
                    is Double -> putDouble(key, value)
                    else -> putString(key, value.toString())
                }
            }
        }
        analytics?.logEvent(name, bundle)
    }

    override fun log(message: String) = FirebaseCrashlytics.getInstance().log(message)

    override fun setKey(key: String, value: Any) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        when (value) {
            is Boolean -> crashlytics.setCustomKey(key, value)
            is Int -> crashlytics.setCustomKey(key, value)
            is Long -> crashlytics.setCustomKey(key, value)
            is Double -> crashlytics.setCustomKey(key, value)
            else -> crashlytics.setCustomKey(key, value.toString())
        }
    }

    override fun recordException(throwable: Throwable) = FirebaseCrashlytics.getInstance().recordException(throwable)
}

/**
 * Single entry point for Firebase Crashlytics + Analytics.
 *
 * Requirements §2/§8 originally barred telemetry SDKs; this was added later at the
 * owner's explicit request. To stay as close to that original posture as possible:
 *
 * - Collection is **off by default** (manifest meta-data) and only switched on from
 *   [init] for non-debuggable builds, so nothing is reported before this runs and
 *   local development never reports to Firebase.
 * - No credentials, tokens, photo content, file names, or share paths are ever
 *   passed in here. Event params are limited to coarse enums and counts, and crash
 *   context is limited to response *shape* (see [ApiDiagnostics]), never content.
 */
object Telemetry {

    @Volatile
    internal var backend: TelemetryBackend = FirebaseBackend

    /** Call from `Application.onCreate` so worker-only processes report too. */
    fun init(context: Context) {
        val app = context.applicationContext
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val collect = !debuggable

        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = collect
        FirebaseAnalytics.getInstance(app).also {
            it.setAnalyticsCollectionEnabled(collect)
            FirebaseBackend.analytics = it
        }
    }

    /** Log a coarse product event. Keys/values must contain no user or file data. */
    fun event(name: String, params: Map<String, Any> = emptyMap()) = backend.logEvent(name, params)

    /** Non-sensitive breadcrumb attached to the next crash report. */
    fun breadcrumb(message: String) = backend.log(message)

    /**
     * Non-sensitive key/value attached to every subsequent crash report
     * (e.g. current screen, feature-flag state, coarse counts). Never a name/path.
     */
    fun setKey(key: String, value: Any) = backend.setKey(key, value)

    /** Report a handled exception without crashing (e.g. a swallowed upload failure). */
    fun recordNonFatal(throwable: Throwable) = backend.recordException(throwable)

    /**
     * A parseable-but-unexpected server response. The exception carries only the
     * [ApiDiagnostics] description (status, content type, size, key-name shape); the
     * same text is attached as a custom key so it shows on the report itself.
     */
    fun recordApiDrift(where: String, diagnostics: String) {
        setKey("drift_where", where)
        setKey("drift_detail", diagnostics.take(MAX_KEY_LENGTH))
        recordNonFatal(ApiDriftException(where, diagnostics))
    }

    /** Names the failing endpoint in the title so Crashlytics groups per endpoint, not per call site. */
    class ApiDriftException(where: String, detail: String) : RuntimeException("API drift at $where: $detail")

    private const val MAX_KEY_LENGTH = 1000

    object Events {
        const val LOGIN_SUCCESS = "login_success"
        const val LOGIN_FAILURE = "login_failure"
        const val SESSION_REVOKED = "session_revoked"
        const val DESTINATIONS_REFRESH = "destinations_refresh"
    }
}
