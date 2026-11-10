# Paper Play (Compose)

Kotlin Compose Multiplatform port of Paper Play for Android and Desktop (JVM).

Modules: `core`, `player`, `ui`, `androidApp`, `desktopApp`.

    ./gradlew :core:jvmTest
    ./gradlew :desktopApp:compileKotlinJvm
    ./gradlew :androidApp:assembleDebug
    ./gradlew :desktopApp:run
