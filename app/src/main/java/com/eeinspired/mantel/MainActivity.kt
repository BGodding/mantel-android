package com.eeinspired.mantel

import android.Manifest
import android.content.ContentResolver
import android.content.Context
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

    LoadFlagsEffect(context) { flags = it }

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

    NavigationTelemetry(screen, viewerItem, galleryDestination, batchTag, pending)

    ConsumeSharedUris(sharedUris, onSharedUrisConsumed) { uris ->
        pending = uris
        batchTag = null
        galleryDestination = null
        viewerItem = null
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

    BootstrapEffect(repo, onLogin = { screen = Screen.Login(it) }, onReady = { bootNotice = it; screen = Screen.Ready })

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

        Screen.Ready -> ReadyContent(
            repo = repo,
            flags = flags,
            bootNotice = bootNotice,
            pending = pending,
            galleryDestination = galleryDestination,
            viewerItem = viewerItem,
            batchTag = batchTag,
            onPickPhotos = {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onDestinationPicked = { picked ->
                val user = repo.username
                val toSend = pending
                if (user != null && toSend.isNotEmpty()) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    repo.lastDestinationId = picked.id
                    scope.launch { batchTag = UploadEnqueuer.enqueue(context, picked, user, toSend).tag }
                }
            },
            onOpenDestination = { galleryDestination = it },
            onOpenItem = { viewerItem = it },
            onGalleryBack = { galleryDestination = null },
            onViewerBack = { viewerItem = null },
            onItemDeleted = { viewerItem = null },
            onUploadDone = { batchTag = null; pending = emptyList() },
            onSignedOut = ::signOut,
        )
    }
}

/** [Screen.Ready]'s content: routes to whichever of the four post-login screens applies. */
@Composable
private fun ReadyContent(
    repo: SessionRepository,
    flags: FlagSnapshot,
    bootNotice: String?,
    pending: List<Uri>,
    galleryDestination: Destination?,
    viewerItem: RemoteItem?,
    batchTag: String?,
    onPickPhotos: () -> Unit,
    onDestinationPicked: (Destination) -> Unit,
    onOpenDestination: (Destination) -> Unit,
    onOpenItem: (RemoteItem) -> Unit,
    onGalleryBack: () -> Unit,
    onViewerBack: () -> Unit,
    onItemDeleted: () -> Unit,
    onUploadDone: () -> Unit,
    onSignedOut: (String) -> Unit,
) {
    when {
        galleryDestination != null && viewerItem != null -> PhotoScreen(
            destination = galleryDestination,
            item = viewerItem,
            repo = repo,
            canDelete = flags.deleteEnabled && galleryDestination.canDelete,
            onDeleted = onItemDeleted,
            onBack = onViewerBack,
            onSignedOut = onSignedOut,
        )

        galleryDestination != null -> GalleryScreen(
            destination = galleryDestination,
            repo = repo,
            canDelete = flags.deleteEnabled && galleryDestination.canDelete,
            onOpenItem = onOpenItem,
            onBack = onGalleryBack,
            onSignedOut = onSignedOut,
        )

        batchTag != null -> UploadStatusScreen(batchTag = batchTag, onDone = onUploadDone)

        else -> DestinationsScreen(
            repo = repo,
            pendingCount = pending.size,
            initialNotice = bootNotice,
            galleryEnabled = flags.galleryEnabled,
            onChoosePhotos = onPickPhotos,
            onDestinationPicked = onDestinationPicked,
            onOpenDestination = onOpenDestination,
            onSignedOut = onSignedOut,
        )
    }
}

private fun screenNameFor(
    screen: Screen,
    viewerItem: RemoteItem?,
    galleryDestination: Destination?,
    batchTag: String?,
    pending: List<Uri>,
): String = when {
    screen is Screen.Loading -> "loading"
    screen is Screen.Login -> "login"
    viewerItem != null -> "viewer"
    galleryDestination != null -> "gallery"
    batchTag != null -> "upload_status"
    pending.isNotEmpty() -> "pick_destination"
    else -> "destinations"
}

/** Loads feature flags once per launch (also caches `server_base_url` for next launch). */
@Composable
private fun LoadFlagsEffect(context: Context, onLoaded: (FlagSnapshot) -> Unit) {
    LaunchedEffect(Unit) {
        val loaded = RemoteFlags.load(context)
        Telemetry.setKey("flag_gallery", loaded.galleryEnabled)
        Telemetry.setKey("flag_delete", loaded.deleteEnabled)
        onLoaded(loaded)
    }
}

@Composable
private fun NavigationTelemetry(
    screen: Screen,
    viewerItem: RemoteItem?,
    galleryDestination: Destination?,
    batchTag: String?,
    pending: List<Uri>,
) {
    val screenName = screenNameFor(screen, viewerItem, galleryDestination, batchTag, pending)
    LaunchedEffect(screenName) {
        Telemetry.setKey("screen", screenName)
        Telemetry.breadcrumb("nav → $screenName")
    }
}

@Composable
private fun ConsumeSharedUris(sharedUris: List<Uri>, onConsumed: () -> Unit, onPending: (List<Uri>) -> Unit) {
    LaunchedEffect(sharedUris) {
        if (sharedUris.isNotEmpty()) {
            onPending(sharedUris)
            onConsumed()
        }
    }
}

@Composable
private fun BootstrapEffect(repo: SessionRepository, onLogin: (String?) -> Unit, onReady: (String?) -> Unit) {
    LaunchedEffect(Unit) {
        when (val result = repo.bootstrap()) {
            Bootstrap.NeedsLogin -> onLogin(null)
            is Bootstrap.Revoked -> onLogin(result.message)
            is Bootstrap.Ready -> onReady(result.offlineNotice)
        }
    }
}
