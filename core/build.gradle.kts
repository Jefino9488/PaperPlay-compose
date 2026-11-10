plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
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
    android {
        namespace = "io.max.paperplay.core"
        compileSdk = 37
        minSdk = 26
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(libs.sqldelight.runtime)
        }
        // One JNA binding to libtdjson shared by both targets; only the JNA artifact differs.
        val jnaSrc = "src/jnaMain/kotlin"
        val javaSrc = "src/javaMain/kotlin" // file, zip and crypto actuals shared by android and jvm
        androidMain {
            kotlin.srcDir(jnaSrc); kotlin.srcDir(javaSrc)
            dependencies {
                implementation("${libs.jna.android.get()}@aar")
                implementation(libs.sqldelight.android)
            }
        }
        jvmMain {
            kotlin.srcDir(jnaSrc); kotlin.srcDir(javaSrc)
            dependencies {
                implementation(libs.jna)
                implementation(libs.jna.platform)
                implementation(libs.sqldelight.jdbc)
            }
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
