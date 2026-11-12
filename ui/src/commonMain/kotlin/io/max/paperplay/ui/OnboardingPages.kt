package io.max.paperplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.backup.BackupException
import io.max.paperplay.core.backup.BackupSource
import io.max.paperplay.core.backup.Outcome
import io.max.paperplay.core.backup.RestoreEvent
import io.max.paperplay.core.backup.RestoreResult
import io.max.paperplay.core.backup.WrongPassword
import io.max.paperplay.ui.app.ChoiceRow
import io.max.paperplay.ui.app.CoreBackend
import io.max.paperplay.ui.app.CredentialsForm
import io.max.paperplay.ui.app.Look
import io.max.paperplay.ui.app.LookGroup
import io.max.paperplay.ui.app.SectionLabel
import io.max.paperplay.ui.app.Swatch
import io.max.paperplay.ui.app.SwitchRow
import io.max.paperplay.ui.app.ThemeMode
import io.max.paperplay.ui.app.UiPrefs
import io.max.paperplay.ui.app.accentOptions
import io.max.paperplay.ui.theme.PaperPlayTheme
import org.jetbrains.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun RestorePage(hooks: RestoreHooks?, onContinue: () -> Unit, onSkip: (() -> Unit)? = null, skipLabel: String = "Set up without a backup") = OnboardingPage(
    "Restore backup", "Bring your library, playlists and settings over from a backup file.",
) {
    val scope = rememberCoroutineScope()
    var chosenSource by remember { mutableStateOf<BackupSource?>(null) }
    var isEncrypted by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<RestoreState>(RestoreState.Idle) }

    fun runRestore(source: BackupSource, pwd: String?) {
        if (hooks == null) return
        state = RestoreState.Running("Reading the backup", null)
        scope.launch {
            state = try {
                var result: RestoreResult? = null
                hooks.restore(source, pwd?.ifEmpty { null }).collect {
                    when (it) {
                        is RestoreEvent.Progress -> state = RestoreState.Running(it.stage, it.fraction)
                        is RestoreEvent.Finished -> result = it.result
                    }
                }
                RestoreState.Done(result!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WrongPassword) {
                RestoreState.Failed("That password doesn't open this backup. Please check your password and try again.")
            } catch (e: BackupException) {
                RestoreState.Failed(e.message ?: "The backup could not be restored.")
            } catch (e: Exception) {
                RestoreState.Failed("The backup could not be restored.")
            }
        }
    }

    fun pickFile() {
        hooks?.pickBackup { source ->
            chosenSource = source
            val encrypted = isBackupEncrypted(source)
            isEncrypted = encrypted
            if (!encrypted) {
                runRestore(source, null)
            }
        }
    }

    when (val s = state) {
        RestoreState.Idle, is RestoreState.Failed -> {
            val source = chosenSource
            if (source != null && isEncrypted) {
                Text("This backup file is encrypted. Enter the backup password to restore your credentials:", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    password, { password = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Backup password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(12.dp))
                if (s is RestoreState.Failed) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                Button({ runRestore(source, password) }, Modifier.fillMaxWidth()) {
                    Text("Restore with password")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton({ chosenSource = null; password = ""; state = RestoreState.Idle; pickFile() }, Modifier.fillMaxWidth()) {
                    Text("Select a different backup file")
                }
            } else {
                if (s is RestoreState.Failed) {
                    Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                Button({ pickFile() }, Modifier.fillMaxWidth(), enabled = hooks != null) {
                    Text("Select backup file")
                }
            }
            onSkip?.let {
                Spacer(Modifier.height(8.dp))
                TextButton(it, Modifier.fillMaxWidth()) { Text(skipLabel) }
            }
        }
        is RestoreState.Running -> {
            Text(s.stage, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp))
            if (s.fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { s.fraction.toFloat() }, modifier = Modifier.fillMaxWidth())
        }
        is RestoreState.Done -> {
            Text("Restored ${s.result.songs} songs", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            for (i in s.result.items) {
                val mark = when (i.outcome) { Outcome.Restored -> "Restored"; Outcome.Skipped -> "Skipped"; Outcome.Failed -> "Failed" }
                Text("$mark: ${i.name}" + if (i.note.isEmpty()) "" else " (${i.note})", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(16.dp))
            Button(onContinue, Modifier.fillMaxWidth()) { Text("Continue") }
        }
    }
}

@Preview
@Composable
private fun RestorePagePreview() {
    PaperPlayTheme {
        RestorePage(hooks = null, onContinue = {})
    }
}
