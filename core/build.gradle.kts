plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget()
    jvm()
    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
        }
        // One JNA binding to libtdjson shared by both targets; only the JNA artifact differs.
        val jnaSrc = "src/jnaMain/kotlin"
        androidMain {
            kotlin.srcDir(jnaSrc)
            dependencies { implementation("${libs.jna.android.get()}@aar") }
        }
        jvmMain {
            kotlin.srcDir(jnaSrc)
            dependencies { implementation(libs.jna) }
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
