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

compose.desktop {
    application {
        mainClass = "io.max.paperplay.desktop.MainKt"
        nativeDistributions {
            packageName = "PaperPlay"
            packageVersion = "1.0.0"

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
            appResourcesRootDir = project.file("src/jvmMain/resources")
        }
    }
}
