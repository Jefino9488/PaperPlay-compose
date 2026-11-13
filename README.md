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
- Back (phone swipe or button, desktop mouse back button) returns to where you
  came from: previous tab or page, album or playlist, Profile page or Settings
  page, not always Home.
- Albums that share a name are split by artist, except compilations and albums
  where one artist owns most songs.
- The seek bar wave stops animating while the Android app is in the background.
- **Settings > Repair library**: reads every chat again, adds missed songs, starts
  info and cover lookups and shows counts. It never deletes by itself; songs whose
  post looks gone are kept until you confirm removing them.
- **Settings > Telegram**: one **Bots** page (main bot credentials plus extra bots
  with an Add bot token box), a Channels page with Add channel and Scan now, an
  account page that links to Chats and channels, and an **Activity log** with
  search, source and problem filters, copy, save to file, crash log and clear.
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
- **Player**: Apple Music style phone player (round star and menu buttons, rewind, bare play and fast-forward glyphs,
  volume bar, output device pill); the PC bar, now playing view and mini player use the same glyphs. A format badge
  (FLAC, ALAC, Hi-Res Lossless, Dolby, MP3) sits under the seek bar; tapping it, or "Output info" in the three dot menu,
  shows the source format, the decoded format, the output device, the effects in the path and whether the sound is
  bit-perfect (desktop never is: Windows mixes at its own format).
- **Volume** follows the system volume on Android (media stream) and Windows (master volume of the default output);
  the app has no second volume where the system has one.
- **Equalizer**: ten octave bands with a draggable response curve, 13 presets, a preamp that stops boosts from clipping.
- **Desktop**: system tray with a themed menu (left click opens the app), one running copy (a second launch shows the
  first), borderless full screen on Windows, a mini player window whose width follows the song text, media keys,
  Explorer style file dialogs (zenity or kdialog on Linux), format badge switch in Player bar settings.
- **Devices and sync**: the Connection page lists one entry per device with all its addresses (edit, delete, add), your
  own addresses, pairing and auto link when a paired device shows up. Linked devices show what they play and take
  play, pause, next and previous. The player bar popup (PC) and the "Play on" sheet (phone) list them without
  addresses and open Connection and Network play. A sync goes both ways and reports both sides.
- **Search** finds songs by title, artist, album (the film, for film songs), genre and year. Plain lyrics (no
  timestamps, any language) scroll along with the song.
- **Telegram reading**: the reading speed is the real number of requests a minute (slider on Repair and above Full
  rescan). Repair can read each chat with every extra bot and with your own account, then combines the songs; a song only
  an extra bot can read is played, downloaded and given covers through that bot.
- **Bots page** shows the name, @username and picture of the main bot and every extra bot.
- Settings: profile changes show a saved message, the Spotify client id is hidden with an eye button, and the browser
  page after the Spotify login follows your light or dark setting.

## Build

Use JDK 17 and run these commands from the repository root. Gradle itself runs on a JDK 25 daemon
(`gradle/gradle-daemon-jvm.properties`) that it downloads on first use, so the first build needs internet access.

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

## Release build, install and installer

`scripts\release.ps1` builds the signed Android release, installs it on the phone when exactly one `adb` device is
listed, builds the desktop distribution, runs Inno Setup (`desktopApp\installer\PaperPlay.iss`) and copies
`PaperPlay-<version>-setup.exe` and `PaperPlay-<version>.apk` to Downloads. Use `-SkipAndroid` or `-SkipDesktop` to do half.
It stops if `D:\PaperPlayCompose.lock` exists. Versions: `packageVersion` in `desktopApp\build.gradle.kts` and
`AppVersion` in the `.iss` file (desktop), `versionName` and `versionCode` in `androidApp\build.gradle.kts`.

```powershell
powershell -File scripts\release.ps1
```

The desktop app needs JDK 25 to run from Gradle (`C:\Program Files\Zulu\zulu-25`); builds use JDK 17.

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

## Previews (Android Studio)

Shared UI has `@Preview` functions for phone and desktop sizes, using
`org.jetbrains.compose.ui.tooling.preview.Preview`. Open a file in the `ui`
module and use the Split or Design view.

- `ui/.../PreviewSupport.kt` holds the theme wrapper, sample songs, fake
  `PlayerControls` and fake prefs used by previews.
- Screens that read the database or Telegram through `CoreBackend` have no
  preview yet; the player, search and settings list use small interfaces or
  plain parameters instead so they can be previewed with fake data.
- Previews use the fallback theme, default prefs and no cover images, so colours
  and glass effects can differ from the running app.

## Configuration

You provide your own Telegram API ID, API hash, and bot token. PaperPlay does
not upload this configuration to another service.

## Known limitations

- **Other bots' posts**: a bot cannot see songs another bot posted as itself in a group (channels are fine). Repair can
  read with every extra bot and with your own account to find them; a device that lacks a song's owner bot falls back to
  the main bot, which cannot read it.
- **Not tried on a device or screen yet** (all of it compiles): system volume on Windows, the tray click, borderless full
  screen, the themed login page, the device popups, reading with extra bots and the account.
- **Memory**: the desktop app uses about 300 to 400 MB at rest. About 175 MB of that is the Intel graphics driver the
  Direct3D backend loads, 64 MB is JVM class data and libvlc is created at startup. Each extra bot is its own Telegram
  session that starts at launch. Two growth leaks (stream sources kept for every played song) are fixed; longer sessions
  still need measuring.
- **Not built yet**: Spotify style handoff of playback between devices (send the queue and position to another device and
  mirror it), auto filing and the Telegram backup topic, a live level meter for the equalizer, macOS and Linux libvlc
  runtimes (they fall back to a system VLC), a repair that finds which bot sent each song.
- **Previews**: pages that still take `CoreBackend` cannot be previewed yet.
- **Secrets on Linux and macOS**: the Android Keystore and Windows DPAPI protect the bot token and the other secrets;
  on Linux and macOS the desktop app stores them as plain files in its data folder.
- **Continuous integration** builds only the Android debug APK for now (on `main` and `beta`). The tests, the release
  APK and the desktop jobs are switched off in `.github/workflows/build.yml`: a test in `:core:jvmTest` hangs on the
  runner and the Windows job fails fetching libvlc.
- **Written but not wired up**: `sync/WorkOwner.kt` (splitting background work between linked devices) and
  `recs/Suggestions.kt` (the Neighbours view) have no caller yet.
