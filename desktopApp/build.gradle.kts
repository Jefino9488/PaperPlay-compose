plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(17)
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":ui"))
            implementation(project(":core"))
            implementation(project(":player"))
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.max.paperplay.desktop.MainKt"
        nativeDistributions {
            packageName = "PaperPlay"
            packageVersion = "1.0.0"
        }
    }
}
