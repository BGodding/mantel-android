package com.eeinspired.mantel.ui.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImagePainter
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.eeinspired.mantel.data.DeleteOutcome
import com.eeinspired.mantel.data.FolderOutcome
import com.eeinspired.mantel.data.Destination
import com.eeinspired.mantel.data.Messages
import com.eeinspired.mantel.data.RemoteItem
import com.eeinspired.mantel.data.SessionRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    destination: Destination,
    repo: SessionRepository,
    canDelete: Boolean,
    onOpenItem: (RemoteItem) -> Unit,
    onBack: () -> Unit,
    onSignedOut: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items = remember { mutableStateListOf<RemoteItem>() }
    var loading by remember { mutableStateOf(true) }
    var loadedOnce by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<RemoteItem?>(null) }
    var deleteBusy by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    fun load() {
        loading = true
        scope.launch {
            when (val result = repo.listFolder(destination)) {
                is FolderOutcome.Success -> {
                    items.clear()
                    items.addAll(result.items)
                    notice = null
                }
                FolderOutcome.SessionExpired -> {
                    onSignedOut(Messages.SESSION_REVOKED)
                    return@launch
                }
                FolderOutcome.Unreachable -> notice = Messages.NO_CONNECTION
                is FolderOutcome.ServerProblem -> notice = Messages.serverError(result.code)
            }
            loadedOnce = true
            loading = false
        }
    }

    LaunchedEffect(destination.id) { load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(destination.displayName) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = { load() }, enabled = !loading) { Text("Refresh") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (loadedOnce && items.isNotEmpty()) {
                Text(
                    text = "${items.size} ${if (items.size == 1) "item" else "items"}" +
                        if (canDelete) " · long-press to remove" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            when {
                loading && items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                items.isEmpty() && loadedOnce ->
                    Text(
                        text = "This frame has no photos yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(110.dp),
                    contentPadding = PaddingValues(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.href }) { item ->
                        GalleryTile(
                            item = item,
                            onClick = { onOpenItem(item) },
                            onLongClick = if (canDelete) ({ pendingDelete = item }) else null,
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        DeleteConfirmDialog(
            destinationName = destination.displayName,
            itemName = target.name,
            busy = deleteBusy,
            onConfirm = {
                deleteBusy = true
                scope.launch {
                    when (val result = repo.deleteItem(target)) {
                        DeleteOutcome.Success, DeleteOutcome.AlreadyGone -> {
                            items.remove(target)
                            snackbar.showSnackbar("Removed \"${target.name}\".")
                        }
                        DeleteOutcome.SessionExpired -> {
                            onSignedOut(Messages.SESSION_REVOKED)
                            return@launch
                        }
                        DeleteOutcome.Forbidden ->
                            snackbar.showSnackbar("You don't have permission to remove photos from this frame.")
                        DeleteOutcome.Unreachable ->
                            snackbar.showSnackbar(Messages.NO_CONNECTION)
                        is DeleteOutcome.ServerProblem ->
                            snackbar.showSnackbar(Messages.serverError(result.code))
                    }
                    deleteBusy = false
                    pendingDelete = null
                }
            },
            onDismiss = { if (!deleteBusy) pendingDelete = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GalleryTile(
    item: RemoteItem,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .padding(3.dp)
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        SubcomposeAsyncImage(
            model = item.previewUrl() ?: item.downloadUrl(),
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        ) {
            when (painter.state.collectAsState().value) {
                is AsyncImagePainter.State.Loading ->
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                is AsyncImagePainter.State.Error ->
                    // Nextcloud generates previews lazily, so a just-uploaded item
                    // can 404 here briefly. A framed-square placeholder reads as
                    // "preview pending", not broken; tapping still opens the full
                    // download, and re-entering the gallery re-requests.
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(4.dp),
                                ),
                        )
                    }
                else -> SubcomposeAsyncImageContent()
            }
        }
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text("▶", color = MaterialTheme.colorScheme.inverseOnSurface)
            }
        }
    }
}
