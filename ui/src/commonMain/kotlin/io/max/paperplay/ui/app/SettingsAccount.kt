package io.max.paperplay.ui.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.max.paperplay.core.account.LoginStep
import io.max.paperplay.core.account.QrCode
import kotlinx.coroutines.launch

/** Settings > Telegram > Your account. Logs in as yourself, so chats and channels can be read and copied from. */
@Composable
internal fun AccountPage(backend: CoreBackend, onOpenIndex: () -> Unit, onLogOut: () -> Unit) {
    val account = backend.account
    val step by account.step.collectAsState()
    val indexed by backend.chatIndex.indexedChats.collectAsState()
    AccountContent(
        step, onLogOut,
        onBegin = { account.begin() }, onBeginQr = { account.beginQr() },
        onPhone = { account.phone(it) }, onCode = { account.code(it) }, onPassword = { account.password(it) },
        onRestore = { runCatching { backend.services.indexCloud.restore() }.getOrDefault(false) },
        indexedChats = indexed.size, onOpenIndex = onOpenIndex,
    )
}

@Composable
internal fun AccountContent(
    step: LoginStep,
    onLogOut: () -> Unit,
    onBegin: suspend () -> Unit,
    onBeginQr: suspend () -> Unit,
    onPhone: suspend (String) -> Unit,
    onCode: suspend (String) -> Unit,
    onPassword: suspend (String) -> Unit,
    onRestore: suspend () -> Boolean,
    indexedChats: Int = 0,
    onOpenIndex: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var input by remember(step) { mutableStateOf("") }
    var restored by remember { mutableStateOf<String?>(null) }

    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (val s = step) {
            LoginStep.LoggedOut -> {
                Text(
                    "Log in with your own Telegram account to read your chats and channels, find songs in them and copy songs into your main chat. Nothing is posted for you except those copies.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button({ scope.launch { onBegin() } }) { Text("Log in with phone number") }
                OutlinedButton({ scope.launch { onBeginQr() } }) { Text("Log in with QR code") }
            }
            LoginStep.Starting -> Text("Starting", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LoginStep.Phone -> {
                Text("Phone number with country code, like +91 98765 43210.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Phone number") }, singleLine = true)
                Button({ scope.launch { onPhone(input.trim()) } }, enabled = input.isNotBlank()) { Text("Send code") }
                OutlinedButton({ scope.launch { onBeginQr() } }) { Text("Use a QR code instead") }
            }
            is LoginStep.Qr -> {
                Text("On a phone where you are logged in to Telegram: Settings, Devices, Link Desktop Device, then scan this code.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                QrView(s.link)
                SelectionContainer { Text(s.link, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton({ scope.launch { onBegin() } }) { Text("Use a phone number instead") }
            }
            LoginStep.Code -> {
                Text("Telegram sent a code to your other devices or by SMS.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Code") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button({ scope.launch { onCode(input.trim()) } }, enabled = input.isNotBlank()) { Text("Confirm") }
            }
            LoginStep.Password -> {
                Text("Your account has a two-step password.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Button({ scope.launch { onPassword(input) } }, enabled = input.isNotEmpty()) { Text("Confirm") }
            }
            LoginStep.Ready -> {
                // A first login picks up the index saved on Telegram, so it need not be read again.
                LaunchedEffect(Unit) {
                    restored = if (onRestore()) "Your saved index was restored from Telegram." else null
                }
                SettingsRow("Logged in", icon = Icons.Rounded.Person, subtitle = "Your chats and channels are available")
                restored?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                NavRow(
                    "Chats and channels",
                    if (indexedChats == 0) "Nothing indexed yet. Pick chats to find songs in" else "$indexedChats chats indexed. Add more or copy songs to the main chat",
                    Icons.Rounded.Forum, onOpenIndex,
                )
                Text(
                    "Your account is only used to read chats the bot is not in and to copy songs into your main chat.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onLogOut) { Text("Log out") }
            }
            is LoginStep.Failed -> {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Button({ scope.launch { onBegin() } }) { Text("Try again") }
            }
        }
    }
}

/** The link drawn as a QR code, black on white with a quiet border. */
@Composable
internal fun QrView(link: String) {
    val matrix = remember(link) { QrCode.encode(link) }
    if (matrix == null) {
        Text("This link is too long to show as a code.", color = MaterialTheme.colorScheme.error)
        return
    }
    val n = matrix.size
    val border = 4
    Canvas(Modifier.size(220.dp)) {
        val cell = size.width / (n + 2 * border)
        drawRect(Color.White, Offset.Zero, size)
        for (y in 0 until n) for (x in 0 until n) if (matrix[y][x]) {
            drawRect(Color.Black, Offset((x + border) * cell, (y + border) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun QrViewPhonePreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.size(390.dp, 400.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            QrView("https://t.me/token_sample")
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun QrViewDesktopPreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        androidx.compose.foundation.layout.Box(Modifier.size(1024.dp, 500.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            QrView("https://t.me/token_sample")
        }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun AccountPhonePreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        Column(Modifier.size(390.dp, 500.dp)) { AccountContent(LoginStep.LoggedOut, {}, {}, {}, {}, {}, {}, { false }) }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview
@Composable
private fun AccountDesktopPreview() {
    io.max.paperplay.ui.theme.PaperPlayTheme {
        Column(Modifier.size(1024.dp, 500.dp)) { AccountContent(LoginStep.Phone, {}, {}, {}, {}, {}, {}, { false }) }
    }
}

@org.jetbrains.compose.ui.tooling.preview.Preview(name = "Account logged in", widthDp = 390, heightDp = 500, showBackground = true)
@Composable
private fun AccountLoggedInPreview() = io.max.paperplay.ui.PreviewFrame {
    Column { AccountContent(LoginStep.Ready, {}, {}, {}, {}, {}, {}, { false }, indexedChats = 3) }
}
