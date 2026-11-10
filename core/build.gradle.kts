plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    jvm()
    sourceSets {
        commonMain.dependencies { implementation(libs.coroutines.core) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "io.max.paperplay.core"
    compileSdk = 36
    defaultConfig.minSdk = 26
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
