plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    jvm()
    sourceSets {
        commonMain.dependencies { api(libs.coroutines.core) }
    }
}

android {
    namespace = "io.max.paperplay.player"
    compileSdk = 36
    defaultConfig.minSdk = 26
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
