package com.eeinspired.mantel.ui.upload

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.eeinspired.mantel.data.Messages
import com.eeinspired.mantel.upload.UploadWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadStatusScreen(
    batchTag: String,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val infos by remember(batchTag) {
        WorkManager.getInstance(context).getWorkInfosByTagFlow(batchTag)
    }.collectAsState(initial = emptyList())

    val total = infos.size
    val sent = infos.count { it.state == WorkInfo.State.SUCCEEDED }
    val failed = infos.count { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }
    val allFinished = total > 0 && infos.all { it.state.isFinished }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Uploads") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = when {
                    total == 0 -> "Preparing…"
                    allFinished && failed == 0 -> "All $total sent."
                    allFinished -> "$sent of $total sent · $failed didn't finish."
                    else -> "$sent of $total sent…"
                },
                style = MaterialTheme.typography.titleMedium,
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(infos, key = { it.id }) { info -> UploadRow(info) }
            }

            Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(if (allFinished) "Done" else "Keep uploading in the background")
            }
        }
    }
}

@Composable
private fun UploadRow(info: WorkInfo) {
    val name = info.progress.getString(UploadWorker.KEY_DISPLAY_NAME)
        ?: info.outputData.getString(UploadWorker.KEY_DISPLAY_NAME)
        ?: "Preparing…"

    val (status, isError) = when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting" to false
        WorkInfo.State.RUNNING -> "Uploading…" to false
        WorkInfo.State.SUCCEEDED -> "Sent" to false
        WorkInfo.State.FAILED ->
            Messages.uploadError(info.outputData.getString(UploadWorker.KEY_ERROR_KIND)) to true
        WorkInfo.State.CANCELLED -> "Cancelled" to true
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = if (isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
