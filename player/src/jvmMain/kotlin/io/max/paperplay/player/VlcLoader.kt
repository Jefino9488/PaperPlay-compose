package io.max.paperplay.player

import java.io.File

/**
 * Resolves a usable libvlc root directory for the current process.
 *
 * Priority:
 * 1. Bundled VLC shipped inside the app resources (`resources/vlc/<os>/`).
 *    At runtime these live under `compose.application.resources.dir`; during
 *    development they come from `desktopApp/src/jvmMain/resources/`.
 * 2. `PAPERPLAY_VLC` environment variable (manual override / CI).
 * 3. Well-known system install locations (Windows Program Files, macOS /Applications,
 *    Linux /usr).
 *
 * Returns `null` if no libvlc binary can be found anywhere.
 */
object VlcLoader {

    private val os = System.getProperty("os.name", "").lowercase()
    private val isWindows = os.startsWith("windows")
    private val isMac = os.startsWith("mac")

    /** The main library filename expected in the VLC root directory. */
    private val libName = when {
        isWindows -> "libvlc.dll"
        isMac     -> "libvlc.dylib"
        else      -> "libvlc.so"
    }

    /** Sub-folder name inside `resources/vlc/` for the current platform. */
    private val osDirName = when {
        isWindows -> "windows"
        isMac     -> "macos"
        else      -> "linux"
    }

    /**
     * Locates VLC, sets `jna.library.path` and the VLC plugin path argument,
     * then returns the `--plugin-path=…` argument to pass to [MediaPlayerFactory],
     * or an empty list if the bundled discovery already set everything via
     * `jna.library.path`.
     *
     * Must be called once before the first [MediaPlayerFactory] is created.
     *
     * @return extra libvlc init arguments to prepend (may be empty).
     */
    fun init(): List<String> {
        val dir = findVlcDir() ?: return emptyList()
        // Tell JNA where to load libvlc from.
        val current = System.getProperty("jna.library.path")
        System.setProperty(
            "jna.library.path",
            if (current.isNullOrBlank()) dir.path else "${dir.path}${File.pathSeparator}$current",
        )
        // Tell libvlc where its plugins live.
        // VLC 3.x reads --plugin-path; VLC 4.x (not yet released) will read
        // VLC_PLUGIN_PATH. We pass --plugin-path here (safe for VLC 3.x) and
        // also set the system property as a forward-compatibility hint for
        // vlcj's own NativeDiscovery mechanism.
        val plugins = File(dir, "plugins")
        if (!plugins.isDirectory) return emptyList()
        System.setProperty("VLC_PLUGIN_PATH", plugins.absolutePath)
        return listOf("--plugin-path=${plugins.absolutePath}")
    }

    // ----- discovery --------------------------------------------------------

    private fun findVlcDir(): File? =
        findBundled() ?: findEnvOverride() ?: findSystem()

    /** Looks for the VLC directory that was packaged alongside the app. */
    private fun findBundled(): File? {
        // Compose Desktop sets this property at runtime; it points to the
        // platform-specific resources folder inside the distribution.
        val resourcesDir = System.getProperty("compose.application.resources.dir")
            ?.let { File(it) }

        val candidates = buildList {
            if (resourcesDir != null) {
                // Packaged distribution: resources/vlc/<os>/ is at the root of the resources dir.
                add(File(resourcesDir, "vlc/$osDirName"))
                // Also try directly under resources in case the packager flattened it.
                add(File(resourcesDir, osDirName))
                add(resourcesDir)
            }
            // Dev / Gradle run: resources are served from the source tree.
            add(File("desktopApp/src/jvmMain/resources/vlc/$osDirName"))
            add(File("../desktopApp/src/jvmMain/resources/vlc/$osDirName"))
        }
        return candidates.firstOrNull { File(it, libName).exists() }
    }

    private fun findEnvOverride(): File? =
        System.getenv("PAPERPLAY_VLC")?.let { File(it) }?.takeIf { File(it, libName).exists() }

    private fun findSystem(): File? {
        val systemCandidates = when {
            isWindows -> listOf(
                "C:/Program Files/VideoLAN/VLC",
                "C:/Program Files (x86)/VideoLAN/VLC",
                System.getenv("ProgramFiles")?.let { "$it/VideoLAN/VLC" },
                System.getenv("ProgramFiles(x86)")?.let { "$it/VideoLAN/VLC" },
            )
            isMac -> listOf(
                "/Applications/VLC.app/Contents/MacOS",
                "/Applications/VLC.app/Contents/MacOS/lib",
            )
            else -> listOf(
                "/usr/lib",
                "/usr/lib/x86_64-linux-gnu",
                "/usr/lib/aarch64-linux-gnu",
                "/usr/local/lib",
                "/snap/vlc/current/usr/lib",
            )
        }
        return systemCandidates
            .filterNotNull()
            .map(::File)
            .firstOrNull { File(it, libName).exists() }
    }
}
