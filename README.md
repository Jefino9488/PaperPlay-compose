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
- Selectable icon family in **Settings → Appearance**:
  - Material 3 Expressive
  - curated Tabler outline icons
- Only the Tabler icons used by PaperPlay are included; the complete Tabler
  library is not bundled.

## Build

Use JDK 17 and run these commands from the repository root.

```powershell
.\gradlew.bat :ui:compileKotlinJvm
.\gradlew.bat :core:jvmTest :player:jvmTest
.\gradlew.bat :androidApp:assembleDebug
.\gradlew.bat :desktopApp:compileKotlinJvm
```

Build both application targets and the JVM tests:

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
The desktop app requires VLC to be installed.

## Configuration

You provide your own Telegram API ID, API hash, and bot token. PaperPlay does
not upload this configuration to another service.

## Known limitations

Duplicates, task management, uploads, sync, notifications, and some settings
actions are still incomplete. Recent searches are currently kept for the
current search screen session rather than persisted.
