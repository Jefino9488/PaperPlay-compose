plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

kotlin {
    android {
        namespace = "io.max.paperplay.player"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    jvm()
    sourceSets {
        commonMain.dependencies { api(libs.coroutines.core) }
        androidMain.dependencies {
            api(libs.media3.exoplayer)
            api(libs.media3.session)
        }
        jvmMain.dependencies {
            implementation(libs.vlcj)
            implementation(libs.jna)
        }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}
