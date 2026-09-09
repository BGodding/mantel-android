package com.eeinspired.mantel.ui.destinations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eeinspired.mantel.data.Destination
import com.eeinspired.mantel.data.Messages
import com.eeinspired.mantel.data.RefreshOutcome
import com.eeinspired.mantel.data.SessionRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationsScreen(
    repo: SessionRepository,
    pendingCount: Int,
    initialNotice: String?,
    galleryEnabled: Boolean,
    onChoosePhotos: () -> Unit,
    onDestinationPicked: (Destination) -> Unit,
    onOpenDestination: (Destination) -> Unit,
    onSignedOut: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val destinations = remember { mutableStateListOf<Destination>() }
    var loading by remember { mutableStateOf(true) }
    var loadedOnce by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf(initialNotice) }
    val picking = pendingCount > 0
    val lastUsedId = remember { repo.lastDestinationId }

    fun refresh() {
        loading = true
        scope.launch {
            when (val outcome = repo.refreshDestinations()) {
                is RefreshOutcome.Success -> {
                    destinations.clear()
                    destinations.addAll(outcome.destinations)
                    notice = null
                }
                RefreshOutcome.SessionExpired -> {
                    onSignedOut(Messages.SESSION_REVOKED)
                    return@launch
                }
                RefreshOutcome.Unreachable -> notice = Messages.OFFLINE_CACHED
                is RefreshOutcome.ServerProblem -> notice = Messages.serverError(outcome.code)
            }
            loadedOnce = true
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        destinations.addAll(repo.cachedDestinations())
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (picking) "Send to which frame?" else "Your frames") },
                actions = {
                    TextButton(onClick = { refresh() }, enabled = !loading) { Text("Refresh") }
                    TextButton(
                        onClick = {
                            repo.logOut()
                            onSignedOut("")
                        },
                    ) { Text("Sign out") }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (picking) {
                Text(
                    text = "$pendingCount ${if (pendingCount == 1) "item" else "items"} ready — " +
                        "choose where to send ${if (pendingCount == 1) "it" else "them"}.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            notice?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    loading && destinations.isEmpty() ->
                        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }

                    destinations.isEmpty() && loadedOnce ->
                        Text(
                            text = "No frames are shared with you yet. Ask your admin to share a " +
                                "frame folder with your account, then tap Refresh.",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(destinations, key = { it.id }) { destination ->
                            FrameRow(
                                destination = destination,
                                selectable = picking || galleryEnabled,
                                highlighted = picking && destination.id == lastUsedId,
                                subtitle = when {
                                    picking && destination.id == lastUsedId -> "Last used"
                                    galleryEnabled && !picking -> "Tap to view photos"
                                    else -> destination.remotePath
                                },
                                onClick = {
                                    if (picking) onDestinationPicked(destination) else onOpenDestination(destination)
                                },
                            )
                        }
                    }
                }
            }

            if (!picking) {
                Button(
                    onClick = onChoosePhotos,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
                ) { Text("Choose photos to upload") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrameRow(
    destination: Destination,
    selectable: Boolean,
    highlighted: Boolean,
    subtitle: String,
    onClick: () -> Unit,
) {
    val colors =
        if (highlighted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        else CardDefaults.cardColors()

    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = destination.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (selectable) {
        Card(onClick = onClick, colors = colors, modifier = Modifier.fillMaxWidth()) { content() }
    } else {
        Card(colors = colors, modifier = Modifier.fillMaxWidth()) { content() }
    }
}
