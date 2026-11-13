package io.max.paperplay.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.max.paperplay.core.app.AppServices
import io.max.paperplay.core.backup.BackupRestorer
import io.max.paperplay.core.backup.RestoreOptions
import io.max.paperplay.core.backup.desktopRestoreTarget
import io.max.paperplay.core.backup.fileBackupSource
import io.max.paperplay.ui.App
import io.max.paperplay.ui.AppEnv
import io.max.paperplay.ui.RestoreHooks
import java.io.File

private const val PICTURES = "*.jpg;*.jpeg;*.png;*.webp"

/**
 * Where tdjson.dll (and the libraries beside it) are found.
 *
 * Priority: PAPERPLAY_TDLIB env var → `native/` next to the app resources →
 * `native/` in the working directory → dev copy inside the Flutter tree.
 *
 * VLC loading is handled separately by VlcLoader inside JvmPlayerEngine; there
 * is no need to mix VLC paths into jna.library.path here.
 */
private fun tdlibDir(): String? = listOfNotNull(
    System.getenv("PAPERPLAY_TDLIB"),
    File(System.getProperty("compose.application.resources.dir") ?: ".", "native").path,
    File("native").path,
    "D:/Git forks/Teleplayer/windows/third_party/tdlib",
).firstOrNull { File(it, if (System.getProperty("os.name").startsWith("Windows")) "tdjson.dll" else "libtdjson.so").exists() }

private val t0 = System.nanoTime()
@Volatile private var liveServices: AppServices? = null

/** Appends elapsed milliseconds since main() began to startup.log in the app data folder. */
private fun mark(dir: File, what: String) {
    runCatching {
        dir.mkdirs()
        val ms = (System.nanoTime() - t0) / 1_000_000
        val sinceJvm = System.currentTimeMillis() - java.lang.management.ManagementFactory.getRuntimeMXBean().startTime
        File(dir, "startup.log").appendText("${java.time.LocalDateTime.now()}  +${ms} ms (jvm +${sinceJvm} ms)  $what\n")
    }
}

/** Every audio file in [roots]: files as given, folders searched through all subfolders. */
private fun audioFilesIn(roots: List<File>): List<String> = roots.flatMap { r ->
    if (r.isDirectory) r.walkTopDown().filter { it.isFile }.toList() else listOf(r)
}.filter { it.extension.lowercase() in io.max.paperplay.core.app.audioExtensions }.map { it.path }

/** Lets files and folders be dropped on the window; they go to the uploads page. */
private fun installFileDrop(window: java.awt.Window) {
    val target = java.awt.dnd.DropTarget(window, java.awt.dnd.DnDConstants.ACTION_COPY, object : java.awt.dnd.DropTargetAdapter() {
        override fun drop(e: java.awt.dnd.DropTargetDropEvent) {
            try {
                e.acceptDrop(java.awt.dnd.DnDConstants.ACTION_COPY)
                if (!e.transferable.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) { e.dropComplete(false); return }
                @Suppress("UNCHECKED_CAST")
                val dropped = e.transferable.getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor) as List<File>
                val found = audioFilesIn(dropped)
                if (found.isNotEmpty()) io.max.paperplay.ui.app.IncomingDrops.files.value = io.max.paperplay.ui.app.IncomingDrops.files.value + found
                e.dropComplete(true)
            } catch (_: Exception) {
                e.dropComplete(false)
            }
        }
        override fun dragEnter(e: java.awt.dnd.DropTargetDragEvent) = e.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
        override fun dragOver(e: java.awt.dnd.DropTargetDragEvent) = e.acceptDrag(java.awt.dnd.DnDConstants.ACTION_COPY)
    })
    // Compose's own canvas may hold a drop target of its own; put ours on every component so it is never skipped.
    fun apply(c: java.awt.Component) {
        runCatching { c.dropTarget = if (c === window) target else java.awt.dnd.DropTarget(c, java.awt.dnd.DnDConstants.ACTION_COPY, target) }
        if (c is java.awt.Container) c.components.forEach(::apply)
    }
    apply(window)
}

/** Held for the life of the process so the file lock is never collected and released early. */
private var instanceLock: Any? = null
private val showMain = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

fun main() {
    val logDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PaperPlay")
    runCatching { File(logDir, "startup.log").takeIf { it.length() > 64_000 }?.delete() }
    mark(logDir, "main start")
    // One copy only: a second launch asks the running copy to show its window, then exits. The OS drops the lock if the process dies.
    val lock = runCatching {
        logDir.mkdirs()
        java.io.RandomAccessFile(File(logDir, "app.lock"), "rw").channel.tryLock()
    }
    if (lock.isSuccess && lock.getOrNull() == null) {
        mark(logDir, "another copy is running, asking it to show")
        runCatching {
            val port = File(logDir, "app.port").readText().trim().toInt()
            java.net.Socket(java.net.InetAddress.getLoopbackAddress(), port).use { it.getOutputStream().write(1) }
        }
        return
    }
    if (lock.isSuccess) runCatching {
        instanceLock = lock.getOrNull()
        val server = java.net.ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
        File(logDir, "app.port").writeText(server.localPort.toString())
        Thread {
            while (true) runCatching { server.accept().use { it.getInputStream().read(); showMain.tryEmit(Unit) } }
        }.apply { isDaemon = true; name = "single-instance" }.start()
    }
    // Set jna.library.path for TDLib only. VlcLoader will append the VLC path
    // automatically when the first JvmPlayerEngine is created.
    tdlibDir()?.let { System.setProperty("jna.library.path", it) }
    io.max.paperplay.ui.app.BackupFiles.pickDestination = { name, onPicked ->
        javax.swing.SwingUtilities.invokeLater {
            val picked = NativeDialogs.saveFile("Save backup", name)
            if (picked == null) onPicked(null)
            else {
                val chosen = picked.let { if (it.extension.isEmpty()) File(it.path + ".zip") else it }
                onPicked(io.max.paperplay.ui.app.BackupDestination { path -> runCatching { File(path).copyTo(chosen, overwrite = true) }.isSuccess })
            }
        }
    }
    WindowsMedia.start { key ->
        val s = liveServices?.takeIf { it.playbackStarted } ?: return@start
        val p = s.playback
        when (key) {
            "playpause" -> if (p.nowPlayingStatic.value.isPlaying) p.pause() else p.play()
            "next" -> p.next()
            "previous" -> p.previous()
            "stop" -> p.pause()
        }
    }
    application {
        val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PaperPlay")
        val target = desktopRestoreTarget(dir)
        var titleBarWindow: java.awt.Window? = null
        val env = AppEnv(
            restore = RestoreHooks(
                pickBackup = { onPicked ->
                    NativeDialogs.openFiles("Choose a backup", "*.zip").firstOrNull()?.let { onPicked(fileBackupSource(it.path)) }
                },
                restore = { source, pw -> BackupRestorer().restore(source, target, RestoreOptions(password = pw)) },
            ),
            libraryExists = { File(dir, "library.sqlite").exists() },
            createServices = { AppServices(DesktopPlatform(dir)).also { liveServices = it } },
            mark = { mark(dir, it) },
            pickImage = { onPicked ->
                val file = NativeDialogs.openFiles("Choose a picture", PICTURES).firstOrNull()
                if (file == null) onPicked(null) else onPicked(runCatching { file.readBytes() }.getOrNull())
            },
            pickPhoto = { onPicked ->
                val file = NativeDialogs.openFiles("Choose a photo", PICTURES).firstOrNull()
                if (file == null) onPicked(null)
                else onPicked(runCatching {
                    val folder = File(dir, "profile").apply { mkdirs() }
                    folder.listFiles()?.filter { it.name.startsWith("photo-") }?.forEach { it.delete() }
                    file.copyTo(File(folder, "photo-${System.currentTimeMillis()}.${file.extension}")).path
                }.getOrNull())
            },
            pickAudio = { onPicked ->
                val files = NativeDialogs.openFiles("Choose songs", io.max.paperplay.core.app.audioExtensions.joinToString(";") { "*.$it" }, multiple = true)
                if (files.isNotEmpty()) onPicked(files.map { it.path }, false)
            },
            pickAudioFolder = { onPicked ->
                Thread { NativeDialogs.pickFolder("Choose a music folder")?.let { onPicked(audioFilesIn(listOf(it))) } }.start()
            },
            pickFolderPath = { onPicked ->
                Thread { NativeDialogs.pickFolder("Choose a music folder")?.let { onPicked(it.path) } }.start()
            },
            authRedirect = LoopbackAuth(),
            authRedirectUri = SPOTIFY_REDIRECT,
            onboarded = { File(dir, "onboarded").exists() },
            setOnboarded = { runCatching { dir.mkdirs(); File(dir, "onboarded").writeText("1") } },
            windowTheme = { dark, bg, text -> WindowsTitleBar.apply(titleBarWindow, dark, bg, text) },
        )
        // The close button hides the window to the tray and music keeps playing; Quit in the tray menu ends the app.
        var mainVisible by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
        val mainState = rememberWindowState(width = 1320.dp, height = 840.dp)
        var raise by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
        androidx.compose.runtime.LaunchedEffect(Unit) { showMain.collect { mainVisible = true; mainState.isMinimized = false; raise++ } }
        val trayOk = androidx.compose.ui.window.isTraySupported
        val fullscreen by io.max.paperplay.ui.app.WindowFullscreen.on.collectAsState()
        androidx.compose.runtime.LaunchedEffect(Unit) { io.max.paperplay.ui.app.WindowFullscreen.supported.value = true }
        // Windows uses a borderless window over the monitor (BorderlessFullscreen); elsewhere the toolkit's full screen.
        androidx.compose.runtime.LaunchedEffect(fullscreen) {
            if (!BorderlessFullscreen.supported) mainState.placement = if (fullscreen) androidx.compose.ui.window.WindowPlacement.Fullscreen else androidx.compose.ui.window.WindowPlacement.Floating
        }
        val quit = {
            // libvlc writes its plugin cache (plugins.dat) only when released.
            liveServices?.releasePlayback()
            exitApplication()
        }
        // The tray menu is the app's own themed window (TrayMenuContent) rather than the native Windows menu.
        var trayMenuAt by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<java.awt.Point?>(null) }
        val control = { f: (io.max.paperplay.core.app.PlaybackController) -> Unit -> liveServices?.takeIf { it.playbackStarted }?.let { f(it.playback) } }
        if (trayOk) {
            androidx.compose.runtime.DisposableEffect(Unit) {
                val image = javax.imageio.ImageIO.read(Thread.currentThread().contextClassLoader.getResource("icon.png"))
                val trayIcon = java.awt.TrayIcon(image, "Paper Play").apply {
                    isImageAutoSize = true
                    addMouseListener(object : java.awt.event.MouseAdapter() {
                        // Windows delivers a left click reliably only as press and release, not as mouseClicked.
                        override fun mouseReleased(e: java.awt.event.MouseEvent) {
                            if (e.isPopupTrigger || e.button == java.awt.event.MouseEvent.BUTTON3) trayMenuAt = e.point
                            else if (e.button == java.awt.event.MouseEvent.BUTTON1) {
                                javax.swing.SwingUtilities.invokeLater { trayMenuAt = null; mainVisible = true; mainState.isMinimized = false; raise++ }
                            }
                        }
                    })
                    addActionListener { javax.swing.SwingUtilities.invokeLater { mainVisible = true; mainState.isMinimized = false; raise++ } }
                }
                java.awt.SystemTray.getSystemTray().add(trayIcon)
                onDispose { java.awt.SystemTray.getSystemTray().remove(trayIcon) }
            }
        }
        trayMenuAt?.let { at ->
            val screens = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
            val gc = screens.screenDevices.map { it.defaultConfiguration }.firstOrNull { it.bounds.contains(at) } ?: screens.defaultScreenDevice.defaultConfiguration
            val b = gc.bounds
            val insets = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(gc)
            val w = io.max.paperplay.ui.app.TrayMenuWidth.value.toInt(); val h = io.max.paperplay.ui.app.TrayMenuHeight.value.toInt()
            val x = (at.x - w / 2).coerceIn(b.x, maxOf(b.x, b.x + b.width - w))
            // Taskbar at the bottom: sit just above it; otherwise start at the click.
            val y = if (at.y > b.y + b.height / 2) b.y + b.height - insets.bottom - h else at.y
            androidx.compose.runtime.key(at) {
                androidx.compose.ui.window.DialogWindow(
                    onCloseRequest = { trayMenuAt = null },
                    state = androidx.compose.ui.window.rememberDialogState(
                        position = androidx.compose.ui.window.WindowPosition(x.dp, y.dp),
                        size = androidx.compose.ui.unit.DpSize(io.max.paperplay.ui.app.TrayMenuWidth, io.max.paperplay.ui.app.TrayMenuHeight),
                    ),
                    title = "Paper Play menu", undecorated = true, resizable = false, alwaysOnTop = true,
                    onKeyEvent = {
                        if (it.key == androidx.compose.ui.input.key.Key.Escape && it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { trayMenuAt = null; true } else false
                    },
                ) {
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        // Closes when the user clicks anywhere else.
                        window.addWindowFocusListener(object : java.awt.event.WindowAdapter() {
                            override fun windowLostFocus(e: java.awt.event.WindowEvent?) { trayMenuAt = null }
                        })
                        WindowsTitleBar.roundCorners(window)
                        window.toFront(); window.requestFocus()
                    }
                    io.max.paperplay.ui.app.TrayMenuContent(
                        onOpen = { mainVisible = true; mainState.isMinimized = false; raise++ },
                        onPlayPause = { control { p -> if (p.nowPlayingStatic.value.isPlaying) p.pause() else p.play() } },
                        onNext = { control { it.next() } },
                        onPrevious = { control { it.previous() } },
                        onMini = { io.max.paperplay.ui.app.DesktopMini.visible.value = !io.max.paperplay.ui.app.DesktopMini.visible.value },
                        onQuit = quit,
                        dismiss = { trayMenuAt = null },
                    )
                }
            }
        }
        Window(
            onCloseRequest = { if (trayOk) mainVisible = false else quit() },
            visible = mainVisible, title = "Paper Play", state = mainState, icon = androidx.compose.ui.res.painterResource("icon.png"),
            onPreviewKeyEvent = {
                if (fullscreen && it.key == androidx.compose.ui.input.key.Key.Escape && it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                    io.max.paperplay.ui.app.WindowFullscreen.on.value = false; true
                } else false
            },
        ) {
            titleBarWindow = window
            androidx.compose.runtime.LaunchedEffect(fullscreen) { BorderlessFullscreen.set(window, fullscreen) }
            // Windows only lets a window forward itself when it is not minimised and briefly topmost, so do all three.
            androidx.compose.runtime.LaunchedEffect(mainVisible, raise) {
                if (mainVisible) {
                    kotlinx.coroutines.delay(30)
                    window.extendedState = window.extendedState and java.awt.Frame.ICONIFIED.inv()
                    window.toFront(); window.requestFocus()
                    if (!fullscreen) { window.isAlwaysOnTop = true; window.isAlwaysOnTop = false }
                }
            }
            androidx.compose.runtime.LaunchedEffect(Unit) { installFileDrop(window) }
            androidx.compose.runtime.LaunchedEffect(Unit) { mark(dir, "window first composition") }
            App(env)
        }
        val miniVisible by io.max.paperplay.ui.app.DesktopMini.visible.collectAsState()
        if (miniVisible) {
            Window(
                onCloseRequest = { io.max.paperplay.ui.app.DesktopMini.visible.value = false },
                title = "Paper Play mini", state = rememberWindowState(width = 380.dp, height = 110.dp),
                undecorated = true, transparent = true,
                alwaysOnTop = true, resizable = false, icon = androidx.compose.ui.res.painterResource("icon.png"),
            ) {
                // No title bar: the whole card drags the window, and the close button sits on the card. Windows rounds the corners.
                androidx.compose.runtime.LaunchedEffect(Unit) { WindowsTitleBar.roundCorners(window) }
                WindowDraggableArea {
                    io.max.paperplay.ui.app.DesktopMiniContent(onClose = { io.max.paperplay.ui.app.DesktopMini.visible.value = false })
                }
            }
        }
    }
}
