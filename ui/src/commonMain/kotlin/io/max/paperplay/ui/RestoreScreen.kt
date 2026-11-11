package io.max.paperplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import org.jetbrains.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.max.paperplay.core.backup.BackupException
import io.max.paperplay.core.backup.BackupSource
import io.max.paperplay.core.backup.Outcome
import io.max.paperplay.core.backup.RestoreEvent
import io.max.paperplay.core.backup.RestoreResult
import io.max.paperplay.core.backup.WrongPassword
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** What the platform gives the first-launch restore screen. */
class RestoreHooks(
    /** Opens the platform file picker; calls back with the chosen backup, never if cancelled. */
    val pickBackup: (onPicked: (BackupSource) -> Unit) -> Unit,
    val restore: (BackupSource, password: String?) -> Flow<RestoreEvent>,
)

private sealed interface RestoreState {
    data object Idle : RestoreState
    class Running(val stage: String, val fraction: Double?) : RestoreState
    class Done(val result: RestoreResult) : RestoreState
    class Failed(val message: String) : RestoreState
}

@Composable
fun RestoreScreen(hooks: RestoreHooks, onContinue: () -> Unit, onSkip: (() -> Unit)? = null, skipLabel: String = "Skip (debug demo)") {
    val backdrop = rememberLayerBackdrop()
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<RestoreState>(RestoreState.Idle) }
    var password by remember { mutableStateOf("") }

    fun start(source: BackupSource) {
        state = RestoreState.Running("Reading the backup", null)
        scope.launch {
            state = try {
                var result: RestoreResult? = null
                hooks.restore(source, password.ifEmpty { null }).collect {
                    when (it) {
                        is RestoreEvent.Progress -> state = RestoreState.Running(it.stage, it.fraction)
                        is RestoreEvent.Finished -> result = it.result
                    }
                }
                RestoreState.Done(result!!)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WrongPassword) {
                RestoreState.Failed("That password doesn't open this backup. Nothing was changed.")
            } catch (e: BackupException) {
                RestoreState.Failed(e.message ?: "The backup could not be restored.")
            } catch (e: Exception) {
                RestoreState.Failed("The backup could not be restored.") // no details: they could hold backup contents
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF101014))) {
        Box(
            Modifier.fillMaxSize().layerBackdrop(backdrop)
                .background(Brush.linearGradient(listOf(Color(0xFF4B2E83), Color(0xFF0E3B5C), Color(0xFF101014)))),
        )
        GlassSurface(backdrop, Modifier.align(Alignment.Center).padding(24.dp).fillMaxWidth()) {
            Column(
                Modifier.padding(24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("PaperPlay", color = Color.White, fontWeight = FontWeight.Bold)
                when (val s = state) {
                    RestoreState.Idle, is RestoreState.Failed -> {
                        Text("Bring your library, playlists and settings over from a backup of the old app.", color = Color.White)
                        OutlinedTextField(
                            password, { password = it },
                            Modifier.fillMaxWidth(),
                            label = { Text("Backup password (for bot and account)") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                        if (s is RestoreState.Failed) Text(s.message, color = Color(0xFFFF8A80))
                        Button({ hooks.pickBackup(::start) }, Modifier.fillMaxWidth()) { Text("Restore from backup") }
                        onSkip?.let { TextButton(it, Modifier.fillMaxWidth()) { Text(skipLabel) } }
                    }
                    is RestoreState.Running -> {
                        Text(s.stage, color = Color.White)
                        if (s.fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        else LinearProgressIndicator({ s.fraction.toFloat() }, Modifier.fillMaxWidth())
                    }
                    is RestoreState.Done -> {
                        Text("Restored ${s.result.songs} songs", color = Color.White, fontWeight = FontWeight.Bold)
                        for (i in s.result.items) {
                            val mark = when (i.outcome) { Outcome.Restored -> "Restored"; Outcome.Skipped -> "Skipped"; Outcome.Failed -> "Failed" }
                            Text("$mark: ${i.name}" + if (i.note.isEmpty()) "" else " (${i.note})", color = Color.White)
                        }
                        Button(onContinue, Modifier.fillMaxWidth()) { Text("Continue") }
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun RestoreScreenPreview() {
    val dummyHooks = RestoreHooks(
        pickBackup = {},
        restore = { _, _ -> kotlinx.coroutines.flow.emptyFlow() },
    )
    RestoreScreen(hooks = dummyHooks, onContinue = {}, onSkip = {})
}
