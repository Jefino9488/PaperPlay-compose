package io.max.paperplay.core.backup

import com.sun.jna.platform.win32.Crypt32Util
import java.io.File

/**
 * Windows: secrets sealed with DPAPI (bound to the Windows user). Elsewhere: plain files in [dir].
 * TODO: libsecret / macOS Keychain for non-Windows desktops.
 */
fun desktopSecretStore(dir: File): SecretStore =
    if (System.getProperty("os.name").startsWith("Windows")) {
        FileSecretStore(dir, { Crypt32Util.cryptProtectData(it) }, { Crypt32Util.cryptUnprotectData(it) })
    } else {
        FileSecretStore(dir, { it }, { it })
    }

/** Library, covers, profile and secrets all under [dir]. */
fun desktopRestoreTarget(dir: File): RestoreTarget {
    val d = dir.path.replace(File.separatorChar, '/')
    return RestoreTarget("$d/library.sqlite", "$d/covers", "$d/profile", desktopSecretStore(File(dir, "secrets"))) {
        io.max.paperplay.core.db.libraryDriver(it)
    }
}
