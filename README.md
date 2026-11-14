# PaperPlay

A music player for Android and desktop that keeps your music in **your own Telegram bot and chats**. You bring a Telegram
API id, API hash and bot token; PaperPlay scans your chats, builds a library and plays the songs. Nothing is sent to any
other service.

Built with Kotlin and Compose Multiplatform.

> **Status:** everything compiles and the apps build in CI, but most features have not yet been tried on a real device.
> [`CHECKLIST.md`](CHECKLIST.md) lists what is done, half done and not started.

## What it does

**Your library**
- Reads songs from the chats and channels you choose. Scan, full rescan, forum topics, and a **Repair library** page that
  re-reads every chat (with extra bots and your own account too) and never deletes anything by itself.
- Your own account can be added (phone, code, password or QR) to index chats, copy songs into the main chat, and play
  songs the bot cannot see.
- Find and delete duplicate songs, move songs between topics, upload songs from your phone or desktop.
- Search by title, artist, album, genre and year. Albums with the same name are split by artist.

**Playing**
- Android uses Media3; desktop bundles libvlc, so nothing else needs installing.
- Queue editing, play next, shuffle and repeat, sleep timer, speed, a 10-band equalizer with presets, loudness
  levelling, and restore of your last session.
- Synced lyrics (tap a line to jump) and plain lyrics that scroll along; format badge and an output-info dialog.
- Android: media notification, **standby mode** (a dark clock screen with the song), volume follows the system volume.
- Desktop: now playing view, full-screen player, always-on-top mini player, tray menu, media keys, keyboard shortcuts.

**Info, covers and mixes**
- Song info from MusicBrainz, Deezer, iTunes and TheAudioDB, plus tags and covers read from the audio files.
- Lyrics from LRCLIB, NetEase, Kugou, QQ Music and lyrics.ovh.
- Daily mixes, genre and weather mixes, listening statistics, ListenBrainz.
- Import Spotify playlists (public link, or log in with your own Spotify client id).

**Several devices**
- Local sync between your own devices on the same network: encrypted, paired with a code, merges your library both ways.
- Linked devices show what is playing and can play, pause and skip.
- **Network play**: let other people on your Wi-Fi join a listening session with a PIN or link.

**Backup**
- Save everything to a zip (optionally password protected) and restore it on a new device. Favorites, plays, playlists
  and edited tags also go to your Telegram Saved Messages.

**Look and feel**
- Material or glass looks, many backgrounds (cover, gradient, wallpaper, animated), accent colour from the cover,
  Material 3 Expressive or Tabler icons.
- Pages slide and fade, the full player is a sheet that follows your finger and Android's back swipe, lists glide, and
  covers and titles fade between songs. **Settings > Style > Reduce motion** turns all of it off.

## Get the apps

Every push to `main` or `beta` is built by GitHub Actions. Open the latest run under the **Actions** tab and download:

| Artifact | What it is |
|---|---|
| `PaperPlay-Android-debug` / `PaperPlay-Android-release` | Android APKs, signed with the key stored in the repository secrets |
| `PaperPlay-Windows` | zip, MSI installer and setup exe |
| `PaperPlay-Linux` | `.tar.gz` of the app |

The Windows and Linux builds only include TDLib (the Telegram library) when the repository has a release tagged `deps`
with a `tdlib-windows.zip`; without it they build but cannot reach Telegram.

The workflow is `.github/workflows/build.yml`. The tests are switched off in it because one test in `:core:jvmTest` hangs
on the runner.

## Build it yourself

You need JDK 17. Gradle itself runs on a JDK 25 daemon (`gradle/gradle-daemon-jvm.properties`) that it downloads the
first time, so the first build needs internet access. Run from the repository root:

```powershell
.\gradlew.bat :androidApp:assembleDebug          # Android debug APK
.\gradlew.bat :desktopApp:run                    # run the desktop app
.\gradlew.bat :core:jvmTest :player:jvmTest      # tests
```

The debug APK ends up in `androidApp\build\outputs\apk\debug\`. To install it on a phone: `adb devices`, then
`adb -s <serial> install -r <apk>`.

### Desktop installer and release builds

`scripts\release.ps1` builds the signed Android release, installs it when exactly one `adb` device is connected, builds the
desktop app, runs Inno Setup (`desktopApp\installer\PaperPlay.iss`) and copies `PaperPlay-<version>-setup.exe` and
`PaperPlay-<version>.apk` to Downloads. Use `-SkipAndroid` or `-SkipDesktop` to do half.

```powershell
powershell -File scripts\release.ps1
```

Versions live in `packageVersion` in `desktopApp\build.gradle.kts` and `AppVersion` in the `.iss` file (desktop), and
`versionName` / `versionCode` in `androidApp\build.gradle.kts`. Packaging needs a full JDK with `jpackage`.

### Desktop playback runtime (libvlc)

The bundled libvlc files are large, so they are not in Git. For a local desktop build, copy them once from a VLC 3.x
install:

```powershell
$vlc = "C:\Program Files\VideoLAN\VLC"
$dst = "desktopApp\src\jvmMain\resources\vlc\windows"
Copy-Item "$vlc\libvlc.dll", "$vlc\libvlccore.dll" "$dst\" -Force
foreach ($dir in "audio_output","audio_filter","audio_mixer","packetizer","stream_filter","stream_extractor","misc","keystore") {
    New-Item -ItemType Directory -Force "$dst\plugins\$dir" | Out-Null
    Get-ChildItem "$vlc\plugins\$dir" -Filter "*.dll" | ForEach-Object { Copy-Item $_.FullName "$dst\plugins\$dir\" -Force }
}
```

The curated `codec`, `demux` and `access` plugin subsets are copied by name (see the git history). If VLC is installed
but the bundled folder is missing, the app falls back to the system install. CI downloads VLC itself.

## Configuration

You provide your own Telegram API id, API hash and bot token in the first-run setup or Settings. They are stored on the
device only: in the Android Keystore on Android, and with Windows DPAPI on Windows (plain files on Linux and macOS).

## Project layout

| Module | Contents |
|---|---|
| `core` | database, Telegram client, library logic, sync, backup |
| `player` | playback engines and the queue |
| `ui` | the shared Compose screens, theme and motion (`ui/.../theme/Motion.kt`) |
| `androidApp` | the Android app |
| `desktopApp` | the desktop app (Windows and Linux) |

The shared UI has `@Preview` functions for phone and desktop sizes (`ui/.../PreviewSupport.kt` has the fake data). Screens
that need the database or Telegram have no preview yet.

## Known limitations

- **Other bots' posts:** a bot cannot see songs another bot posted as itself in a group (channels are fine). Repair can read
  with every extra bot and with your own account to find them.
- **Not tried on a device yet:** system volume on Windows, the tray, borderless full screen, the themed Spotify login page,
  the device popups, reading with extra bots, and the new animations.
- **Memory:** the desktop app uses about 300 to 400 MB at rest, mostly the graphics driver and class data. Each extra bot is
  its own Telegram session started at launch.
- **Not built yet:** playback handoff between devices, automatic filing, backup to a Telegram topic, a live equalizer level
  meter, macOS packaging.
- **Written but not wired up:** `sync/WorkOwner.kt` (splitting background work between linked devices) and
  `recs/Suggestions.kt` (a "neighbours" view).
- **Secrets on Linux and macOS** are stored as plain files in the app's data folder.
