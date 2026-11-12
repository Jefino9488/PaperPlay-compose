package io.max.paperplay.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.max.paperplay.ui.theme.PaperPlayTheme
import org.jetbrains.compose.ui.tooling.preview.Preview

/** The three steps to get a bot running, then the fields, then live connection feedback. */
@Composable
internal fun BotSetupForm(
    onSave: (apiId: String, apiHash: String, token: String) -> String?,
    connected: Boolean,
    connectionError: String?,
    modifier: Modifier = Modifier,
    /** Called once after Save and connect, when the bot has really connected. */
    onConnected: () -> Unit = {},
) {
    var id by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    // Only a connect that this Save started moves on; a bot that was connected already does not skip the form.
    androidx.compose.runtime.LaunchedEffect(connected, saved) {
        if (saved && connected) { kotlinx.coroutines.delay(700); onConnected() }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Step(1, "Create a bot", "Open @BotFather in Telegram, send /newbot and copy the token it gives you.")
        Step(2, "Get your API id and hash", "Log in at my.telegram.org and open API development tools.")
        Step(3, "Add the bot to your music chat", "Make it an admin there so it can read and send songs.")
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Several bots in one chat?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    "Turn on Remain anonymous in each bot's admin rights. A bot cannot see songs another bot posted as itself, but it can see ones posted as the chat.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }

        OutlinedTextField(
            id, { id = it; saved = false }, Modifier.fillMaxWidth(), label = { Text("API id") }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Tag, null) }, shape = RoundedCornerShape(20.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            hash, { hash = it; saved = false }, Modifier.fillMaxWidth(), label = { Text("API hash") }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Lock, null) }, shape = RoundedCornerShape(20.dp),
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            token, { token = it; saved = false }, Modifier.fillMaxWidth(), label = { Text("Bot token") }, singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Key, null) }, shape = RoundedCornerShape(20.dp),
            trailingIcon = {
                IconButton({ showToken = !showToken }) { Icon(if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (showToken) "Hide token" else "Show token") }
            },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
        )
        if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)

        val source = remember { MutableInteractionSource() }
        Button(
            { error = onSave(id, hash, token); saved = error == null },
            Modifier.fillMaxWidth().bouncy(source), interactionSource = source,
            enabled = id.isNotBlank() && hash.isNotBlank() && token.isNotBlank(),
            shape = RoundedCornerShape(28.dp),
        ) { Text("Save and connect") }

        AnimatedContent(
            targetState = when {
                connected -> 2
                saved && connectionError == null -> 1
                saved -> 3
                else -> 0
            },
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) + scaleIn(spring(dampingRatio = 0.5f), initialScale = 0.8f)) togetherWith
                    (fadeOut() + scaleOut(targetScale = 0.9f))
            },
            modifier = Modifier.animateContentSize(), label = "status",
        ) { state ->
            when (state) {
                1 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 3.dp)
                    Text("Connecting to Telegram", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                2 -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Text("Bot connected. You can continue.", fontWeight = FontWeight.Medium)
                }
                3 -> Text(connectionError ?: "", color = MaterialTheme.colorScheme.error)
                else -> Box {}
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Preview(name = "Bot setup", widthDp = 420, heightDp = 1000, showBackground = true)
@Composable
private fun BotSetupFormPreview() = PaperPlayTheme { Column(Modifier.padding(16.dp)) { BotSetupForm({ _, _, _ -> null }, false, null) } }

@Preview(name = "Bot setup connected", widthDp = 420, heightDp = 1000, showBackground = true)
@Composable
private fun BotSetupFormConnectedPreview() = PaperPlayTheme { Column(Modifier.padding(16.dp)) { BotSetupForm({ _, _, _ -> null }, true, null) } }
