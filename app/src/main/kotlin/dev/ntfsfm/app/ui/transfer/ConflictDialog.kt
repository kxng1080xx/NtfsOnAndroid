package dev.ntfsfm.app.ui.transfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.ntfsfm.app.ui.files.FileListRow
import dev.ntfsfm.core.transfer.ConflictAnswer
import dev.ntfsfm.core.transfer.ConflictDecision
import dev.ntfsfm.core.vfs.VfsEntry

/** The paste found a file of the same name; the transfer waits until this is answered. */
@Composable
fun ConflictDialog(existing: VfsEntry, onAnswer: (ConflictAnswer) -> Unit) {
    var applyToAll by remember { mutableStateOf(false) }
    fun answer(decision: ConflictDecision) = onAnswer(ConflictAnswer(decision, applyToAll))

    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Already here: ${existing.name}") },
        text = {
            Column {
                Text("A file with this name is already in this folder.")
                Spacer(Modifier.height(8.dp))
                FileListRow(existing, selected = false, onClick = {}, onLongClick = {})
                Row(
                    Modifier.fillMaxWidth().clickable { applyToAll = !applyToAll },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = applyToAll, onCheckedChange = { applyToAll = it })
                    Spacer(Modifier.width(4.dp))
                    Text("Do this for all conflicts", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { answer(ConflictDecision.SKIP) }) { Text("Skip") }
                TextButton(onClick = { answer(ConflictDecision.KEEP_BOTH) }) { Text("Keep both") }
                TextButton(onClick = { answer(ConflictDecision.OVERWRITE) }) { Text("Replace") }
            }
        },
    )
}
