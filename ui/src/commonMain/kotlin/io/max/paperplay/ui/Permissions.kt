package io.max.paperplay.ui

/** One permission or system setting the app can use, with its current state. */
data class PermissionState(
    val id: String,
    val title: String,
    /** What the app uses it for. */
    val why: String,
    val granted: Boolean,
    /** Extra state shown under the reason, such as "Bluetooth is off". Empty when there is none. */
    val detail: String = "",
    /** Button text when not granted: "Allow" for a system prompt, "Open settings" when only Settings can change it. */
    val action: String = "Allow",
)

/** Platform side of the permission checker. [check] must be cheap; the page calls it about once a second. */
class PermissionHooks(
    val check: () -> List<PermissionState>,
    /** Asks for the permission, or opens the Settings screen that controls it. */
    val request: (id: String) -> Unit,
    /** Opens this app's page in system settings. */
    val openAppSettings: () -> Unit,
)
