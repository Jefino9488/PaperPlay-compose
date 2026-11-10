plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases.create("LibraryDatabase") {
        packageName.set("io.max.paperplay.core.db")
        // Must match the Drift file: SQLDelight has no .sqm history, the version lives in LibrarySchema.
        verifyMigrations.set(false)
    }
}

kotlin {
    androidTarget()
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines.core)
            api(libs.sqldelight.runtime)
        }
        androidMain.dependencies { implementation(libs.sqldelight.android) }
        jvmMain.dependencies { implementation(libs.sqldelight.jdbc) }
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
