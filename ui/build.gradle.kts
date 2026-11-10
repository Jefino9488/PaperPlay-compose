plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    android {
        namespace = "io.max.paperplay.ui"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(libs.backdrop)
            implementation(project(":core"))
            implementation(project(":player"))
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
