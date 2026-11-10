plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    jvm()
    sourceSets {
        commonMain.dependencies { api(libs.coroutines.core) }
        androidMain.dependencies {
            api(libs.media3.exoplayer)
            api(libs.media3.session)
        }
        jvmMain.dependencies { implementation(libs.vlcj) }
        jvmTest.dependencies { implementation(kotlin("test")) }
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
