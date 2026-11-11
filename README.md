# PaperPlay (Compose)

PaperPlay is a Kotlin Compose Multiplatform music player that uses your own
Telegram bot and chats as storage. It targets Android and desktop JVM.

## Modules

- `core`: database, Telegram client, and library logic
- `player`: playback and queue logic
- `ui`: shared Compose UI
- `androidApp`: Android application
- `desktopApp`: desktop JVM application

## Current features

- Library browsing, playback, queue, restore from backup, settings, and profile
- Phone-oriented home, library, player, and search improvements:
  - scan status chip below the greeting
  - one-line mix subtitles
  - profile task badge while scanning
  - sticky library play/shuffle controls
  - library sorting bottom sheet
  - swipe-up queue opening and long-press seek preview
  - recent search chips above genre tiles
- Profile cards and statistics are interactive on Android and desktop:
  profile editing, library tabs, channels, tasks, listening, duplicates/song
  info settings, devices, storage, and playlist browsing all route to their
  corresponding screen.
- Selectable icon family in **Settings → Appearance**:
  - Material 3 Expressive
  - curated Tabler outline icons
- Only the Tabler icons used by PaperPlay are included; the complete Tabler
  library is not bundled.
- Telegram channel settings let you choose one configured chat as the main
  filing chat; the choice is persisted with the library.
- **Self-contained desktop playback** — a curated libvlc runtime (~40 MB) is
  bundled inside the app distribution. No VLC install is required. Supported
  formats at full lossless quality:
  - **Lossless**: FLAC, ALAC (Apple Lossless), WAV, AIFF, TTA, CAF
  - **Lossy**: AAC / M4A, MP3, Opus, Vorbis (OGG), WMA, Musepack, Speex
  - **Containers**: MP4, Matroska (MKV), OGG, FLAC, ASF, AIFF, CAF, WAV
  - **Audio output**: WASAPI (bit-perfect), DirectSound, WaveOut
  - **Audio processing**: equalizer, normalizer, resampler, compressor,
    surround, stereo-widener

- Detail screens (album, artist, playlist) with hero cover, play and shuffle bar, playlist rename, delete and remove.
- Settings pages for mixes and learning, weather, connection, bot activity, Spotify import (public link), equalizer,
  ListenBrainz, listening statistics, clear cache, and the duplicate finder.
- Player: play next, sleep timer, lyrics, saved volume, quality labels learned from played songs.
- Own Telegram account (phone, code, password or QR login), a saved index of your chats and channels that updates
  live and refreshes only newer posts, copying songs into the main chat, a plus button for songs the index has for a
  Spotify playlist, and automatic refresh of imported playlists.
- Release and debug builds are signed with the same key; the desktop app has its own blue icon.

## Build

Use JDK 17 and run these commands from the repository root.

```powershell
.\gradlew.bat :player:compileKotlinJvm
.\gradlew.bat :player:jvmTest
.\gradlew.bat :ui:compileKotlinJvm
.\gradlew.bat :core:jvmTest :player:jvmTest
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :desktopApp:compileKotlinJvm
```

Build all application targets and JVM tests in one command:

```powershell
.\gradlew.bat :androidApp:assembleDebug :desktopApp:compileKotlinJvm :core:jvmTest :player:jvmTest
```

The Android debug APK is written to:

```text
androidApp\build\outputs\apk\debug\androidApp-debug.apk
```

## Install and run

List connected Android devices:

```powershell
adb devices
```

Install the APK on a selected device. Replace the serial with the value from
`adb devices`:

```powershell
adb -s <device-serial> install -r .\androidApp\build\outputs\apk\debug\androidApp-debug.apk
```

Run the desktop application:

```powershell
.\gradlew.bat :desktopApp:run
```

The current desktop Gradle configuration exposes `run` and
`packageDistributionForCurrentOS`; it does not expose a `packageMsi` task.

To create a single shareable Windows executable, run:

```powershell
.\build-and-install.bat
```

Desktop packaging requires a full JDK with `jpackage.exe`, not the JRE bundled
with some IDE extensions. The script uses `D:\jdk17` automatically when that
standard local installation exists; otherwise set `JAVA_HOME` to your full
JDK before running it.

The script creates `%USERPROFILE%\Downloads\PaperPlay-bundled.exe`. It is a
self-extracting portable executable: it contains the complete PaperPlay
desktop distribution, including the JVM runtime, VLC resources, and TDLib
runtime DLLs. Share that one file; the recipient does not need Java, VLC, or
an installer. The first launch extracts a temporary copy before starting the
app, so the file may take a few seconds to open and requires enough free
temporary disk space for the full desktop distribution.

The script also copies the normal portable distribution to
`%USERPROFILE%\Downloads\PaperPlay`. Keep that folder intact if you use the
portable folder instead of the bundled executable.

## Desktop VLC runtime setup (developers)

The bundled libvlc DLLs are excluded from Git (they are large platform
binaries). On a fresh clone, copy them once from a local VLC 3.x install:

```powershell
$vlc = "C:\Program Files\VideoLAN\VLC"
$dst = "desktopApp\src\jvmMain\resources\vlc\windows"

# Core DLLs
Copy-Item "$vlc\libvlc.dll"     "$dst\" -Force
Copy-Item "$vlc\libvlccore.dll" "$dst\" -Force

# Audio plugin directories (all files)
foreach ($dir in @("audio_output","audio_filter","audio_mixer","packetizer","stream_filter","stream_extractor","misc","keystore")) {
    New-Item -ItemType Directory -Force "$dst\plugins\$dir" | Out-Null
    Get-ChildItem "$vlc\plugins\$dir" -Filter "*.dll" |
        ForEach-Object { Copy-Item $_.FullName "$dst\plugins\$dir\" -Force }
}
```

Then copy the curated `codec`, `demux`, and `access` subsets by name as
documented in the git history. After copying, run
`.\gradlew.bat :desktopApp:compileKotlinJvm` to verify the build.

If VLC is installed on the developer machine but the bundled directory is
absent, `VlcLoader` falls back to the system install automatically.

## Configuration

You provide your own Telegram API ID, API hash, and bot token. PaperPlay does
not upload this configuration to another service.

## Known limitations & upcoming ports

- **Not built yet**: uploads and the Android share receiver, auto filing and topic moving, extra bots,
  the task centre, LAN sync and device pairing, offline pinning, loudness normalising, audio output choice,
  a backup writer, the metadata enricher run, and desktop tray and keyboard shortcuts.
- **Duplicates**: the finder lists repeats in the main chat; deleting them is not wired yet.
- **Own account**: login, chat index and copying are built but not yet verified on a device.
- macOS and Linux libvlc runtimes are not yet bundled (falls back to system VLC on those platforms).
