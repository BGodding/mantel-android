package com.eeinspired.mantel.config

import android.content.Context
import com.eeinspired.mantel.data.Config
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Immutable read of every feature flag, taken once per launch. */
data class FlagSnapshot(
    val galleryEnabled: Boolean = false,
    /** Independent of [galleryEnabled] and defaults off — a kill switch for a destructive action. */
    val deleteEnabled: Boolean = false,
)

/**
 * Firebase Remote Config wrapper. Flags ship bundled as **off**; a successful fetch
 * can turn them on without a rebuild or reinstall. Values are read once at launch
 * (see [load]); a change takes effect on the next app start.
 *
 * The same fetch also carries `server_base_url` — handed to [Config.applyRemote],
 * which validates and caches it for the next launch (never a mid-session swap).
 */
object RemoteFlags {

    const val KEY_GALLERY = "gallery_enabled"
    const val KEY_DELETE = "delete_enabled"
    const val KEY_SERVER_BASE_URL = "server_base_url"

    private val defaults: Map<String, Any> = mapOf(
        KEY_GALLERY to false,
        KEY_DELETE to false,
        KEY_SERVER_BASE_URL to Config.DEFAULT_BASE_URL,
    )

    private val config: FirebaseRemoteConfig by lazy {
        FirebaseRemoteConfig.getInstance().apply {
            setDefaultsAsync(defaults)
            setConfigSettingsAsync(
                FirebaseRemoteConfigSettings.Builder()
                    .setMinimumFetchIntervalInSeconds(MIN_FETCH_INTERVAL_SECONDS)
                    .build(),
            )
        }
    }

    suspend fun load(context: Context): FlagSnapshot {
        runCatching { awaitFetchAndActivate() }
        Config.applyRemote(context, config.getString(KEY_SERVER_BASE_URL))
        return FlagSnapshot(
            galleryEnabled = config.getBoolean(KEY_GALLERY),
            deleteEnabled = config.getBoolean(KEY_DELETE),
        )
    }

    private suspend fun awaitFetchAndActivate(): Boolean =
        suspendCancellableCoroutine { cont ->
            config.fetchAndActivate().addOnCompleteListener { task ->
                cont.resume(task.isSuccessful && task.result == true)
            }
        }

    private const val MIN_FETCH_INTERVAL_SECONDS = 3600L
}
