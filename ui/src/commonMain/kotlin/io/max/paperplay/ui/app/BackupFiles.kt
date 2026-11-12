package io.max.paperplay.ui.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Copies a finished backup file to the place the person chose. Blocking; false if it could not. */
fun interface BackupDestination {
    fun save(backupFilePath: String): Boolean
}

/**
 * The platform's "save as" for a backup. The Android and desktop entry points set [pickDestination]; it stays null
 * where saving is not supported, and the Save backup row says so.
 */
object BackupFiles {
    /** Asks where to save a file named [suggestedName]; calls back with the destination, or null if canceled. */
    var pickDestination: ((suggestedName: String, onPicked: (BackupDestination?) -> Unit) -> Unit)? = null
}

/** Asks for the optional password that seals the keys and account session in a backup. */
@Composable
internal fun BackupPasswordDialog(onSave: (password: String?) -> Unit, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save a backup") },
        text = {
            Column {
                Text(
                    "Song info, covers, playlists, favorites and the app layout. Add a password to also keep your bot, Spotify, " +
                        "ListenBrainz and account sign-in in the file, locked with it. Without one they are left out.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    pw, { pw = it }, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("Password (optional)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = { TextButton({ onDismiss(); onSave(pw.ifEmpty { null }) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
