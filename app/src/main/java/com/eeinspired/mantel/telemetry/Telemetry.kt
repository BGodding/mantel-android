package com.eeinspired.mantel.telemetry

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Single entry point for Firebase Crashlytics + Analytics.
 *
 * Requirements §2/§8 originally barred telemetry SDKs; this was added later at the
 * owner's explicit request. To stay as close to that original posture as possible:
 *
 * - Collection is **disabled in debuggable builds** so local development never
 *   reports to Firebase — only real (release) installs send data.
 * - No credentials, tokens, photo content, file names, or share paths are ever
 *   passed in here. Event params are limited to coarse enums and counts.
 */
object Telemetry {

    @Volatile
    private var analytics: FirebaseAnalytics? = null

    fun init(context: Context) {
        val app = context.applicationContext
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val collect = !debuggable

        FirebaseCrashlytics.getInstance().isCrashlyticsCollectionEnabled = collect
        FirebaseAnalytics.getInstance(app).also {
            it.setAnalyticsCollectionEnabled(collect)
            analytics = it
        }
    }

    /** Log a coarse product event. Keys/values must contain no user or file data. */
    fun event(name: String, params: Map<String, Any> = emptyMap()) {
        val bundle = Bundle().apply {
            params.forEach { (key, value) ->
                when (value) {
                    is Int -> putLong(key, value.toLong())
                    is Long -> putLong(key, value)
                    is Double -> putDouble(key, value)
                    is Boolean -> putString(key, value.toString())
                    else -> putString(key, value.toString())
                }
            }
        }
        analytics?.logEvent(name, bundle)
    }

    /** Non-sensitive breadcrumb attached to the next crash report. */
    fun breadcrumb(message: String) {
        FirebaseCrashlytics.getInstance().log(message)
    }

    /**
     * Non-sensitive key/value attached to every subsequent crash report
     * (e.g. current screen, feature-flag state, coarse counts). Never a name/path.
     */
    fun setKey(key: String, value: Any) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        when (value) {
            is Boolean -> crashlytics.setCustomKey(key, value)
            is Int -> crashlytics.setCustomKey(key, value)
            is Long -> crashlytics.setCustomKey(key, value)
            is Double -> crashlytics.setCustomKey(key, value)
            else -> crashlytics.setCustomKey(key, value.toString())
        }
    }

    /** Report a handled exception without crashing (e.g. a swallowed upload failure). */
    fun recordNonFatal(throwable: Throwable) {
        FirebaseCrashlytics.getInstance().recordException(throwable)
    }

    object Events {
        const val LOGIN_SUCCESS = "login_success"
        const val LOGIN_FAILURE = "login_failure"
        const val SESSION_REVOKED = "session_revoked"
        const val DESTINATIONS_REFRESH = "destinations_refresh"
    }
}
