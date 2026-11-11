import java.util.Properties
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing reads the keystore details from the properties file named by signing.file in
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
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    signingConfigs {
        if (signing != null) create("release") {
            storeFile = file(signing.getProperty("keystore.path"))
            storePassword = signing.getProperty("keystore.password")
            keyAlias = signing.getProperty("key.alias")
            keyPassword = signing.getProperty("key.password")
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
}
