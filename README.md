# Paper Play (Compose)

A music player that uses your own Telegram bot and chats as storage. Kotlin Compose Multiplatform for Android and Desktop (JVM).

Modules: `core` (database, Telegram client, library logic), `player`, `ui`, `androidApp`, `desktopApp`.

Status: early. Library, playback, restore from a backup, settings and the main screens work on Android. Duplicates, tasks, uploads, sync and notifications are still to come. The desktop app needs VLC installed.

Build (JDK 17):

    ./gradlew :core:jvmTest
    ./gradlew :desktopApp:compileKotlinJvm
    ./gradlew :androidApp:assembleDebug
    ./gradlew :desktopApp:run

You bring your own Telegram API id, API hash and bot token. Nothing is stored anywhere else.
