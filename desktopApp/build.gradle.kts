import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(17)
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":core"))
            implementation(project(":player"))
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        }
    }
}

val packagingResources = layout.buildDirectory.dir("packagingResources")

// libvlc (git-ignored; the full copy stays in src/jvmMain/resources/vlc) is staged here with only the audio plugins
// the player needs, so the image carries about 25 MB of VLC instead of 40. TDLib comes from PAPERPLAY_TDLIB, else the Flutter tree.
// To keep another plugin, add it to vlcKeep. Format check after any change: one FLAC, one M4A/AAC, MP3, Opus, OGG, WAV, WMA, ALAC.
val vlcKeep = mapOf(
    "access" to setOf("filesystem", "http", "tcp", "idummy", "imem", "access_concat"),
    "audio_filter" to setOf(
        "audio_format", "equalizer", "gain", "remap", "scaletempo", "scaletempo_pitch", "simple_channel_mixer",
        "trivial_channel_mixer", "ugly_resampler", "speex_resampler", "mono", "headphone_channel_mixer",
    ),
    "audio_mixer" to setOf("float_mixer", "integer_mixer"),
    "audio_output" to setOf("mmdevice", "wasapi", "directsound", "waveout", "adummy"),
    "codec" to setOf("avcodec", "vorbis", "opus", "faad", "mpg123", "flac", "lpcm", "adpcm", "g711", "araw", "mft"),
    "demux" to setOf("aiff", "asf", "au", "caf", "es", "flacsys", "mkv", "mp4", "mpc", "ogg", "playlist", "rawaud", "tta", "wav"),
    "misc" to setOf("logger", "xml"),
    "packetizer" to setOf("copy", "flac", "mpeg4audio", "mpegaudio", "a52", "dts", "mlp"),
    "stream_filter" to setOf("cache_block", "cache_read", "inflate", "prefetch", "skiptags"),
)
fun vlcWanted(path: String): Boolean {
    val p = path.replace("\\", "/").removePrefix("windows/")
    if (!p.startsWith("plugins/")) return true // libvlc.dll, libvlccore.dll and any helper DLL
    val parts = p.removePrefix("plugins/").split('/')
    if (parts.size < 2) return false
    val keep = vlcKeep[parts[0]] ?: return false
    return parts[1].removePrefix("lib").removeSuffix("_plugin.dll") in keep
}

val stagePackagingResources = tasks.register<Copy>("stagePackagingResources") {
    doFirst { delete(packagingResources) } // Copy never removes stale files
    into(packagingResources.map { it.dir("windows") })
    from("src/jvmMain/resources/vlc") {
        into("vlc")
        eachFile { if (!file.isDirectory && !vlcWanted(relativePath.pathString.removePrefix("vlc/").removePrefix("vlc\\"))) exclude() }
        includeEmptyDirs = false
    }
    val tdlib = file(System.getenv("PAPERPLAY_TDLIB") ?: "D:/Git forks/Teleplayer/windows/third_party/tdlib")
    if (tdlib.exists()) from(tdlib) { into("native"); include("*.dll") }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stagePackagingResources) }

// libvlc is shipped through appResourcesRootDir; keeping a second copy inside the app jar doubled it.
tasks.named<ProcessResources>("jvmProcessResources") { exclude("vlc/**") }

// jlink in the Compose plugin leaves the runtime image uncompressed; ask for ZIP (about half the size).
afterEvaluate {
    tasks.matching { it.name == "createRuntimeImage" || it.name == "createReleaseRuntimeImage" }.configureEach {
        val prop = javaClass.methods.firstOrNull { it.name.startsWith("getCompressionLevel") }?.invoke(this)
        @Suppress("UNCHECKED_CAST")
        (prop as? org.gradle.api.provider.Property<Any>)?.let { p ->
            val level = Class.forName("org.jetbrains.compose.desktop.application.internal.RuntimeCompressionLevel")
            p.set(level.enumConstants.first { it.toString() == "ZIP" })
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.max.paperplay.desktop.MainKt"
        buildTypes.release.proguard {
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("proguard-rules.pro"))
        }
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "PaperPlay"
            packageVersion = "1.0.0"
            // The packaged runtime only holds the modules named here: JDBC (the library database), naming and TLS.
            modules("java.sql", "java.naming", "jdk.unsupported", "jdk.crypto.ec", "jdk.httpserver")
            windows {
                iconFile.set(project.file("src/jvmMain/resources/app.ico"))
                menu = true
                shortcut = true
            }

            // Bundle the curated libvlc runtime so the app plays FLAC, ALAC,
            // AAC, MP3, Opus, Vorbis, WAV, AIFF, and all other audio formats
            // without requiring VLC to be installed on the user's machine.
            //
            // The resources directory layout mirrors src/jvmMain/resources/:
            //   vlc/windows/   <- libvlc.dll, libvlccore.dll, plugins/
            //   vlc/macos/     <- libvlc.dylib, libvlccore.dylib, plugins/   (future)
            //   vlc/linux/     <- libvlc.so, libvlccore.so, plugins/         (future)
            //
            // VlcLoader in the player module reads compose.application.resources.dir
            // at runtime to locate the correct platform sub-folder.
            // Compose copies only the common/ and windows/ folders of this root into the app. The local libvlc runtime
            // and TDLib are staged into windows/ by stagePackagingResources below.
            appResourcesRootDir.set(packagingResources)
        }
    }
}

// sqlite-jdbc and JNA ship natives for every OS; the Windows image only needs win x64. Rewrite those jars in the image.
fun stripForeignNatives(jar: File) {
    val drop = Regex("^(org/sqlite/native/(?!Windows/x86_64/).*|com/sun/jna/(?!win32-x86-64/)[a-z0-9-]+-[a-z0-9-]+/.*)$")
    val tmp = File(jar.path + ".tmp")
    ZipFile(jar).use { zf ->
        ZipOutputStream(tmp.outputStream().buffered()).use { out ->
            for (e in zf.entries()) {
                if (e.isDirectory || drop.matches(e.name) && !e.name.endsWith(".class")) continue
                out.putNextEntry(ZipEntry(e.name))
                zf.getInputStream(e).use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
    jar.delete(); tmp.renameTo(jar)
}
afterEvaluate {
    tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
        doLast {
            outputs.files.files.flatMap { d -> d.walkTopDown().filter { it.isFile && Regex("(sqlite-jdbc|jna(-jpms)?)-.*[.]jar").matches(it.name) }.toList() }
                .forEach(::stripForeignNatives)
        }
    }
}
