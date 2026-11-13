import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Debug and release signing read the keystore details from the properties file named by signing.file in
// local.properties (git-ignored), so no secret is stored in the project. Without it, release uses the debug key.
val signing = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }
    ?.getProperty("signing.file")?.let { path -> file(path).takeIf { it.exists() } }
    ?.let { f -> Properties().apply { f.inputStream().use(::load) } }

android {
    namespace = "io.max.paperplay.compose"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.max.paperplay.compose"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.3.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        // Debug and release share one key, so a debug build installs over a release one and back.
        if (signing != null) {
            for (name in listOf("debug", "release")) {
                maybeCreate(name).apply {
                    storeFile = file(signing.getProperty("keystore.path"))
                    storePassword = signing.getProperty("keystore.password")
                    keyAlias = signing.getProperty("key.alias")
                    keyPassword = signing.getProperty("key.password")
                }
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // Licence texts, build metadata and Kotlin builtins (only kotlin-reflect reads those) are dead weight in the APK.
        resources.excludes += listOf(
            "/META-INF/{AL2.0,LGPL2.1,LICENSE*,NOTICE*,DEPENDENCIES}",
            "/META-INF/androidx/**",
            "/META-INF/*.version",
            "/META-INF/com/android/build/gradle/**",
            "/META-INF/proguard/**",
            "**/*.kotlin_builtins",
            "/kotlin-tooling-metadata.json",
            "/DebugProbesKt.bin",
            "/META-INF/*.kotlin_module",
        )
        // Compress libtdjson.so inside the APK (installed copy is extracted, so the on-device size is unchanged).
        jniLibs.useLegacyPackaging = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":ui"))
    implementation(project(":core"))
    implementation(project(":player"))
    implementation(libs.androidx.activity.compose)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
