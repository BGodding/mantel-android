package com.eeinspired.mantel

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import coil3.compose.setSingletonImageLoaderFactory
import com.eeinspired.mantel.config.FlagSnapshot
import com.eeinspired.mantel.config.RemoteFlags
import com.eeinspired.mantel.data.Bootstrap
import com.eeinspired.mantel.data.Config
import com.eeinspired.mantel.data.Destination
import com.eeinspired.mantel.data.RemoteItem
import com.eeinspired.mantel.data.SessionRepository
import com.eeinspired.mantel.telemetry.Telemetry
import com.eeinspired.mantel.ui.common.LoadingScreen
import com.eeinspired.mantel.ui.destinations.DestinationsScreen
import com.eeinspired.mantel.ui.gallery.GalleryScreen
import com.eeinspired.mantel.ui.gallery.NextcloudImageLoader
import com.eeinspired.mantel.ui.gallery.PhotoScreen
import com.eeinspired.mantel.ui.login.LoginScreen
import com.eeinspired.mantel.ui.theme.MantelTheme
import com.eeinspired.mantel.ui.upload.UploadStatusScreen
import com.eeinspired.mantel.upload.MediaStaging
import com.eeinspired.mantel.upload.UploadEnqueuer
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Telemetry.init(applicationContext)
        Config.initialize(applicationContext) // resolve the server host before any network use
        MediaStaging.sweepStale(applicationContext)
        sharedUris.value = extractSharedUris(intent)
        enableEdgeToEdge()
        setContent {
            setSingletonImageLoaderFactory { context -> NextcloudImageLoader.create(context) }
            MantelTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    FrameUploaderApp(
                        sharedUris = sharedUris.value,
                        onSharedUrisConsumed = { sharedUris.value = emptyList() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incoming = extractSharedUris(intent)
        if (incoming.isNotEmpty()) sharedUris.value = incoming
    }

    private fun extractSharedUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val raw = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        return raw.filter(::isAcceptableSharedUri).take(MAX_SHARED_ITEMS)
    }

    /**
     * MainActivity is exported and any app can target it directly, bypassing the
     * intent-filter MIME match. So every shared URI is checked here: it must be a
     * `content://` URI (no `file://` self-reads), must not be hosted by this app,
     * and must resolve to an image or video.
     */
    private fun isAcceptableSharedUri(uri: Uri): Boolean {
        if (!uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true)) return false
        val authority = uri.authority.orEmpty()
        if (authority == packageName || authority.startsWith("$packageName.")) return false
        val type = runCatching { contentResolver.getType(uri) }.getOrNull() ?: return false
        return type.startsWith("image/") || type.startsWith("video/")
    }

    private companion object {
        const val MAX_SHARED_ITEMS = 50
    }
}

private sealed interface Screen {
    data object Loading : Screen
    data class Login(val message: String?) : Screen
    data object Ready : Screen
}

@Composable
private fun FrameUploaderApp(
    sharedUris: List<Uri>,
    onSharedUrisConsumed: () -> Unit,
) {
    val context = LocalContext.current
    val repo = remember { SessionRepository(context) }
    val scope = rememberCoroutineScope()

    var screen by remember { mutableStateOf<Screen>(Screen.Loading) }
    var bootNotice by remember { mutableStateOf<String?>(null) }
    var flags by remember { mutableStateOf(FlagSnapshot()) }
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var batchTag by remember { mutableStateOf<String?>(null) }
    var galleryDestination by remember { mutableStateOf<Destination?>(null) }
    var viewerItem by remember { mutableStateOf<RemoteItem?>(null) }

    LaunchedEffect(Unit) {
        flags = RemoteFlags.load(context) // also caches server_base_url for next launch
        Telemetry.setKey("flag_gallery", flags.galleryEnabled)
        Telemetry.setKey("flag_delete", flags.deleteEnabled)
    }

    // In-app Back: step back through the screen stack instead of leaving the app.
    // Null on the root screens (destinations / login / loading) — there the OS default wins.
    val goBack: (() -> Unit)? = when {
        screen != Screen.Ready -> null
        viewerItem != null -> ({ viewerItem = null })
        galleryDestination != null -> ({ galleryDestination = null })
        batchTag != null -> ({ batchTag = null; pending = emptyList() })
        pending.isNotEmpty() -> ({ pending = emptyList() })
        else -> null
    }
    BackHandler(enabled = goBack != null) { goBack?.invoke() }

    val screenName = when {
        screen is Screen.Loading -> "loading"
        screen is Screen.Login -> "login"
        viewerItem != null -> "viewer"
        galleryDestination != null -> "gallery"
        batchTag != null -> "upload_status"
        pending.isNotEmpty() -> "pick_destination"
        else -> "destinations"
    }
    LaunchedEffect(screenName) {
        Telemetry.setKey("screen", screenName)
        Telemetry.breadcrumb("nav → $screenName")
    }

    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            pending = sharedUris
            batchTag = null
            galleryDestination = null
            viewerItem = null
            onSharedUrisConsumed()
        }
    }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris ->
        if (uris.isNotEmpty()) {
            pending = uris
            batchTag = null
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* upload proceeds whether or not it's granted */ }

    LaunchedEffect(Unit) {
        screen = when (val result = repo.bootstrap()) {
            Bootstrap.NeedsLogin -> Screen.Login(null)
            is Bootstrap.Revoked -> Screen.Login(result.message)
            is Bootstrap.Ready -> {
                bootNotice = result.offlineNotice
                Screen.Ready
            }
        }
    }

    fun signOut(message: String) {
        pending = emptyList()
        batchTag = null
        galleryDestination = null
        viewerItem = null
        screen = Screen.Login(message.ifBlank { null })
    }

    when (val current = screen) {
        Screen.Loading -> LoadingScreen()

        is Screen.Login -> LoginScreen(
            repo = repo,
            initialMessage = current.message,
            onAuthenticated = { screen = Screen.Ready },
        )

        Screen.Ready -> {
            val destination = galleryDestination
            val item = viewerItem
            val tag = batchTag
            when {
                destination != null && item != null -> PhotoScreen(
                    destination = destination,
                    item = item,
                    repo = repo,
                    canDelete = flags.deleteEnabled && destination.canDelete,
                    onDeleted = { viewerItem = null },
                    onBack = { viewerItem = null },
                    onSignedOut = ::signOut,
                )

                destination != null -> GalleryScreen(
                    destination = destination,
                    repo = repo,
                    canDelete = flags.deleteEnabled && destination.canDelete,
                    onOpenItem = { viewerItem = it },
                    onBack = { galleryDestination = null },
                    onSignedOut = ::signOut,
                )

                tag != null -> UploadStatusScreen(
                    batchTag = tag,
                    onDone = {
                        batchTag = null
                        pending = emptyList()
                    },
                )

                else -> DestinationsScreen(
                    repo = repo,
                    pendingCount = pending.size,
                    initialNotice = bootNotice,
                    galleryEnabled = flags.galleryEnabled,
                    onChoosePhotos = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                        )
                    },
                    onDestinationPicked = { picked ->
                        val user = repo.username
                        val toSend = pending
                        if (user != null && toSend.isNotEmpty()) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            repo.lastDestinationId = picked.id
                            scope.launch {
                                batchTag = UploadEnqueuer.enqueue(context, picked, user, toSend).tag
                            }
                        }
                    },
                    onOpenDestination = { galleryDestination = it },
                    onSignedOut = ::signOut,
                )
            }
        }
    }
}
