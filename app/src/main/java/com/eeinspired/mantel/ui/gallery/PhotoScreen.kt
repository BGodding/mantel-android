package com.eeinspired.mantel.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.eeinspired.mantel.data.DeleteOutcome
import com.eeinspired.mantel.data.Destination
import com.eeinspired.mantel.data.Messages
import com.eeinspired.mantel.data.RemoteItem
import com.eeinspired.mantel.data.SessionRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoScreen(
    destination: Destination,
    item: RemoteItem,
    repo: SessionRepository,
    canDelete: Boolean,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
    onSignedOut: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            PhotoTopBar(
                title = item.name,
                canDelete = canDelete,
                onBack = onBack,
                onDeleteRequested = { confirming = true },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = if (item.isVideo) (item.previewUrl() ?: item.downloadUrl()) else item.downloadUrl(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            val footer = when {
                error != null -> error!!
                item.isVideo -> "Video — open in Nextcloud to play"
                else -> null
            }
            footer?.let {
                Text(
                    text = it,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                )
            }
        }
    }

    if (confirming) {
        DeleteConfirmDialog(
            destinationName = destination.displayName,
            itemName = item.name,
            busy = busy,
            onConfirm = {
                busy = true
                error = null
                scope.launch {
                    val failure = performDelete(repo, item, onDeleted, onSignedOut)
                    if (failure != null) {
                        error = failure
                        busy = false
                        confirming = false
                    }
                }
            },
            onDismiss = { if (!busy) confirming = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoTopBar(title: String, canDelete: Boolean, onBack: () -> Unit, onDeleteRequested: () -> Unit) {
    TopAppBar(
        title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { TextButton(onClick = onBack) { Text("Back", color = Color.White) } },
        actions = {
            if (canDelete) {
                TextButton(onClick = onDeleteRequested) { Text("Delete", color = Color.White) }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black, titleContentColor = Color.White),
    )
}

/** Returns null on success (and has already called [onDeleted]/[onSignedOut]), or an error message. */
private suspend fun performDelete(
    repo: SessionRepository,
    item: RemoteItem,
    onDeleted: () -> Unit,
    onSignedOut: (String) -> Unit,
): String? = when (val result = repo.deleteItem(item)) {
    DeleteOutcome.Success, DeleteOutcome.AlreadyGone -> {
        onDeleted()
        null
    }
    DeleteOutcome.SessionExpired -> {
        onSignedOut(Messages.SESSION_REVOKED)
        null
    }
    DeleteOutcome.Forbidden -> "You don't have permission to remove photos from this frame."
    DeleteOutcome.Unreachable -> Messages.NO_CONNECTION
    is DeleteOutcome.ServerProblem -> Messages.serverError(result.code)
}
