package com.eeinspired.mantel.data

import android.content.Context
import androidx.core.content.edit
import androidx.work.WorkManager
import coil3.SingletonImageLoader
import com.eeinspired.mantel.telemetry.Telemetry
import com.eeinspired.mantel.upload.MediaStaging
import com.eeinspired.mantel.upload.UploadWorker
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Result of the launch-time credential check (Requirements §4). */
sealed interface Bootstrap {
    data object NeedsLogin : Bootstrap
    data class Revoked(val message: String) : Bootstrap
    data class Ready(val offlineNotice: String?) : Bootstrap
}

sealed interface LoginOutcome {
    data class Success(val user: UserInfo) : LoginOutcome
    data object InvalidCredentials : LoginOutcome
    data object Unreachable : LoginOutcome
    data class ServerProblem(val code: Int) : LoginOutcome
}

sealed interface RefreshOutcome {
    data class Success(val destinations: List<Destination>) : RefreshOutcome
    data object SessionExpired : RefreshOutcome
    data object Unreachable : RefreshOutcome
    data class ServerProblem(val code: Int) : RefreshOutcome
}

sealed interface FolderOutcome {
    data class Success(val items: List<RemoteItem>) : FolderOutcome
    data object SessionExpired : FolderOutcome
    data object Unreachable : FolderOutcome
    data class ServerProblem(val code: Int) : FolderOutcome
}

sealed interface DeleteOutcome {
    data object Success : DeleteOutcome
    data object SessionExpired : DeleteOutcome
    data object Forbidden : DeleteOutcome
    data object AlreadyGone : DeleteOutcome
    data object Unreachable : DeleteOutcome
    data class ServerProblem(val code: Int) : DeleteOutcome
}

/**
 * Single owner of session state: encrypted credentials, the live/cached
 * destination list, and the "am I still logged in" check.
 *
 * There is no local database (Requirements §9). The destination list is fetched live
 * every time and only *cached* for instant display — the server is always the
 * source of truth.
 */
class SessionRepository(context: Context) {

    private val appContext = context.applicationContext
    private val client = NextcloudClient()
    private val store = CredentialStore(appContext)
    private val cache = appContext.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var credentials: Credentials? = null

    /** The server's canonical uid — what the WebDAV paths are keyed by (not the typed login name). */
    val userId: String?
        get() = credentials?.userId ?: cache.getString(KEY_USER_ID, null)

    /** Last destination the user uploaded to — a pure UX convenience, never authoritative (§6/§9). */
    var lastDestinationId: String?
        get() = cache.getString(KEY_LAST_DEST, null)
        set(value) {
            cache.edit { if (value == null) remove(KEY_LAST_DEST) else putString(KEY_LAST_DEST, value) }
        }

    suspend fun bootstrap(): Bootstrap {
        val stored = store.load() ?: return Bootstrap.NeedsLogin
        credentials = stored
        return when (val result = client.validateSession(stored)) {
            is ApiResult.Success -> {
                // Installs that stored only the typed login name learn their real uid here.
                val resolved = result.value.id.ifBlank { stored.userId }
                val current = when (resolved) {
                    stored.userId -> stored
                    else -> stored.copy(userId = resolved).also(store::save)
                }
                credentials = current
                cache.edit { putString(KEY_USER_ID, current.userId) }
                Bootstrap.Ready(offlineNotice = null)
            }
            ApiResult.Unauthorized -> {
                logOut()
                Telemetry.event(Telemetry.Events.SESSION_REVOKED, mapOf("at" to "launch"))
                Bootstrap.Revoked(Messages.SESSION_REVOKED)
            }
            is ApiResult.NetworkError -> Bootstrap.Ready(Messages.OFFLINE_CACHED)
            is ApiResult.ServerError -> Bootstrap.Ready(Messages.SERVER_CACHED)
            is ApiResult.MalformedResponse -> {
                Telemetry.recordApiDrift("bootstrap:/cloud/user", result.detail)
                Bootstrap.Ready(Messages.SERVER_CACHED)
            }
        }
    }

    suspend fun logIn(username: String, appPassword: String): LoginOutcome {
        val typed = Credentials(username.trim(), appPassword)
        return when (val result = client.validateSession(typed)) {
            is ApiResult.Success -> {
                val creds = typed.copy(userId = result.value.id.ifBlank { typed.username })
                store.save(creds)
                credentials = creds
                cache.edit { putString(KEY_USER_ID, creds.userId) }
                Telemetry.event(Telemetry.Events.LOGIN_SUCCESS)
                LoginOutcome.Success(result.value)
            }
            ApiResult.Unauthorized -> {
                Telemetry.event(Telemetry.Events.LOGIN_FAILURE, mapOf("reason" to "invalid_credentials"))
                LoginOutcome.InvalidCredentials
            }
            is ApiResult.NetworkError -> {
                Telemetry.event(Telemetry.Events.LOGIN_FAILURE, mapOf("reason" to "unreachable"))
                LoginOutcome.Unreachable
            }
            is ApiResult.ServerError -> {
                Telemetry.event(
                    Telemetry.Events.LOGIN_FAILURE,
                    mapOf("reason" to "server", "code" to result.code),
                )
                LoginOutcome.ServerProblem(result.code)
            }
            is ApiResult.MalformedResponse -> {
                Telemetry.event(Telemetry.Events.LOGIN_FAILURE, mapOf("reason" to "malformed"))
                Telemetry.recordApiDrift("login:/cloud/user", result.detail)
                LoginOutcome.ServerProblem(0)
            }
        }
    }

    private fun currentCredentials(): Credentials? =
        credentials ?: store.load()?.also { credentials = it }

    /**
     * A 401 on a data call can come from a proxy or rate limiter, and acting on it wipes the stored
     * credentials. Only a failing session check proves the app password was actually revoked.
     */
    private suspend fun sessionRevoked(creds: Credentials): Boolean =
        client.validateSession(creds) is ApiResult.Unauthorized

    fun cachedDestinations(): List<Destination> {
        val raw = cache.getString(KEY_DESTINATIONS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(
                        Destination(
                            id = o.getString("id"),
                            displayName = o.getString("displayName"),
                            remotePath = o.getString("remotePath"),
                            permissions = o.getInt("permissions"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun refreshDestinations(): RefreshOutcome {
        val creds = currentCredentials() ?: return RefreshOutcome.SessionExpired

        return when (val result = client.listDestinations(creds)) {
            is ApiResult.Success -> {
                persistDestinations(result.value)
                Telemetry.event(
                    Telemetry.Events.DESTINATIONS_REFRESH,
                    mapOf("outcome" to "success", "count" to result.value.size),
                )
                Telemetry.setKey("destination_count", result.value.size)
                RefreshOutcome.Success(result.value)
            }
            ApiResult.Unauthorized -> if (sessionRevoked(creds)) {
                logOut()
                Telemetry.event(Telemetry.Events.DESTINATIONS_REFRESH, mapOf("outcome" to "session_expired"))
                Telemetry.event(Telemetry.Events.SESSION_REVOKED, mapOf("at" to "refresh"))
                RefreshOutcome.SessionExpired
            } else {
                RefreshOutcome.ServerProblem(HTTP_UNAUTHORIZED)
            }
            is ApiResult.NetworkError -> {
                Telemetry.event(Telemetry.Events.DESTINATIONS_REFRESH, mapOf("outcome" to "unreachable"))
                RefreshOutcome.Unreachable
            }
            is ApiResult.ServerError -> {
                Telemetry.event(
                    Telemetry.Events.DESTINATIONS_REFRESH,
                    mapOf("outcome" to "server_error", "code" to result.code),
                )
                RefreshOutcome.ServerProblem(result.code)
            }
            is ApiResult.MalformedResponse -> {
                Telemetry.event(Telemetry.Events.DESTINATIONS_REFRESH, mapOf("outcome" to "malformed"))
                Telemetry.recordApiDrift("discovery:/shares", result.detail)
                RefreshOutcome.ServerProblem(0)
            }
        }
    }

    /** Feature-flagged gallery: list one destination folder's files. */
    suspend fun listFolder(destination: Destination): FolderOutcome {
        val creds = currentCredentials() ?: return FolderOutcome.SessionExpired

        return when (val result = client.listFolder(creds, destination.remotePath)) {
            is ApiResult.Success -> {
                Telemetry.event("gallery_open", mapOf("count" to result.value.size))
                FolderOutcome.Success(result.value)
            }
            ApiResult.Unauthorized -> if (sessionRevoked(creds)) {
                logOut()
                FolderOutcome.SessionExpired
            } else {
                FolderOutcome.ServerProblem(HTTP_UNAUTHORIZED)
            }
            is ApiResult.NetworkError -> FolderOutcome.Unreachable
            is ApiResult.ServerError -> FolderOutcome.ServerProblem(result.code)
            is ApiResult.MalformedResponse -> {
                Telemetry.recordApiDrift("gallery:PROPFIND", result.detail)
                FolderOutcome.ServerProblem(0)
            }
        }
    }

    /** Feature-flagged, permission-gated: delete one file from a destination folder. */
    suspend fun deleteItem(item: RemoteItem): DeleteOutcome {
        val creds = currentCredentials() ?: return DeleteOutcome.SessionExpired

        val (outcome, label) = when (val result = client.deleteItem(creds, item.href)) {
            UploadResult.Success -> DeleteOutcome.Success to "success"
            UploadResult.Unauthorized ->
                if (sessionRevoked(creds)) {
                    logOut()
                    DeleteOutcome.SessionExpired to "session_expired"
                } else {
                    DeleteOutcome.ServerProblem(HTTP_UNAUTHORIZED) to "server_error"
                }
            UploadResult.Forbidden -> DeleteOutcome.Forbidden to "forbidden"
            UploadResult.DestinationMissing -> DeleteOutcome.AlreadyGone to "already_gone"
            UploadResult.Conflict -> DeleteOutcome.ServerProblem(HTTP_CONFLICT) to "server_error"
            UploadResult.QuotaExceeded -> DeleteOutcome.ServerProblem(HTTP_INSUFFICIENT_STORAGE) to "server_error"
            is UploadResult.Rejected -> DeleteOutcome.ServerProblem(result.code) to "server_error"
            is UploadResult.ServerError -> DeleteOutcome.ServerProblem(result.code) to "server_error"
            is UploadResult.Network -> DeleteOutcome.Unreachable to "unreachable"
        }
        // Explicit labels: `outcome::class.simpleName` is obfuscated by R8 in release builds.
        Telemetry.event("gallery_item_deleted", mapOf("outcome" to label))
        return outcome
    }

    /**
     * Signs out and removes everything that outlives the session: the credential, cached
     * lists, queued/running uploads, staged copies of the user's photos, and Coil's cached
     * thumbnails and downloads (private family photos).
     */
    fun logOut() {
        store.clear()
        credentials = null
        cache.edit { clear() }
        WorkManager.getInstance(appContext).cancelAllWorkByTag(UploadWorker.TAG_ALL)
        SingletonImageLoader.get(appContext).let { loader ->
            loader.memoryCache?.clear()
            AppScope.io.launch {
                loader.diskCache?.clear()
                MediaStaging.deleteAll(appContext)
            }
        }
    }

    private fun persistDestinations(destinations: List<Destination>) {
        val arr = JSONArray()
        destinations.forEach { destination ->
            arr.put(
                JSONObject()
                    .put("id", destination.id)
                    .put("displayName", destination.displayName)
                    .put("remotePath", destination.remotePath)
                    .put("permissions", destination.permissions),
            )
        }
        cache.edit { putString(KEY_DESTINATIONS, arr.toString()) }
    }

    private companion object {
        const val CACHE_PREFS = "mantel_cache"
        const val KEY_DESTINATIONS = "destinations_json"
        const val KEY_USER_ID = "user_id"
        const val KEY_LAST_DEST = "last_destination_id"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_CONFLICT = 409
        const val HTTP_INSUFFICIENT_STORAGE = 507
    }
}
