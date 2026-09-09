package com.eeinspired.mantel.ui.gallery

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** Mandatory confirmation before any delete — the action is destructive and outward-facing. */
@Composable
fun DeleteConfirmDialog(
    destinationName: String,
    itemName: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Remove this photo?") },
        text = {
            Text(
                "\"$itemName\" will be removed from \"$destinationName\" and moved to the server's " +
                    "trash. Anyone viewing that frame will no longer see it.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "Removing…" else "Remove")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}
