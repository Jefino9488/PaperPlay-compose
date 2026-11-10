# JNA resolves native methods and Structure fields reflectively.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.platform.**
# TdJson is a JNA Library proxy interface; its method names must match libtdjson symbols.
-keep interface io.max.paperplay.core.telegram.TdJson { *; }
