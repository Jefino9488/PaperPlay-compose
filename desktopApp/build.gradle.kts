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

// libvlc (git-ignored, bundled for playback) and TDLib (PAPERPLAY_TDLIB, else the Flutter tree) go into the app.
val stagePackagingResources = tasks.register<Copy>("stagePackagingResources") {
    into(packagingResources.map { it.dir("windows") })
    from("src/jvmMain/resources/vlc") { into("vlc") }
    val tdlib = file(System.getenv("PAPERPLAY_TDLIB") ?: "D:/Git forks/Teleplayer/windows/third_party/tdlib")
    if (tdlib.exists()) from(tdlib) { into("native"); include("*.dll") }
}
tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stagePackagingResources) }

compose.desktop {
    application {
        mainClass = "io.max.paperplay.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
            )
            packageName = "PaperPlay"
            packageVersion = "1.0.0"
            // The packaged runtime only holds the modules named here: JDBC (the library database), naming and TLS.
            modules("java.sql", "java.naming", "jdk.unsupported", "jdk.crypto.ec")
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
