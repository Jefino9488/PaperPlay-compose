package io.max.paperplay.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDraggableArea
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

fun main() {
    val logDir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PaperPlay")
    runCatching { File(logDir, "startup.log").takeIf { it.length() > 64_000 }?.delete() }
    mark(logDir, "main start")
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
        Window(onCloseRequest = {
            // libvlc writes its plugin cache (plugins.dat) only when released.
            liveServices?.releasePlayback()
            exitApplication()
        }, title = "Paper Play", state = rememberWindowState(width = 1320.dp, height = 840.dp), icon = androidx.compose.ui.res.painterResource("icon.png")) {
            titleBarWindow = window
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
                // No title bar: the whole card drags the window, and the close button sits on the card.
                WindowDraggableArea {
                    io.max.paperplay.ui.app.DesktopMiniContent(onClose = { io.max.paperplay.ui.app.DesktopMini.visible.value = false })
                }
            }
        }
    }
}
