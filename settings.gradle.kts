pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "PaperPlayCompose"
include(":core", ":player", ":ui", ":androidApp", ":desktopApp")
