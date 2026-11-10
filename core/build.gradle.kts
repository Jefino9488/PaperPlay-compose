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
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(libs.sqldelight.runtime)
        }
        // One JNA binding to libtdjson shared by both targets; only the JNA artifact differs.
        val jnaSrc = "src/jnaMain/kotlin"
        androidMain {
            kotlin.srcDir(jnaSrc)
            dependencies {
                implementation("${libs.jna.android.get()}@aar")
                implementation(libs.sqldelight.android)
            }
        }
        jvmMain {
            kotlin.srcDir(jnaSrc)
            dependencies {
                implementation(libs.jna)
                implementation(libs.sqldelight.jdbc)
            }
        }
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
