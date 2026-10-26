package com.princeniithompson.rapcha.service

/**
 * Chooses the app the user is actually in.
 * Input-method windows, Rapcha's own windows, and a launcher flash do not
 * replace a real app that is still on screen. A Safe Mode package wins over
 * any other application window that is only passing through, including a
 * mis-attributed package such as an overlay reported as the foreground.
 */
object ForegroundAppPicker {

    data class AppWindow(
        val packageName: String,
        val active: Boolean = false,
        val focused: Boolean = false,
        val typeApplication: Boolean = true
    )

    fun pick(
        windows: List<AppWindow>,
        ignored: (String) -> Boolean,
        packageSensitive: (String) -> Boolean = { false }
    ): String? {
        val apps = windows.filter { window ->
            window.typeApplication &&
                window.packageName.isNotBlank() &&
                !ignored(window.packageName)
        }
        if (apps.isEmpty()) return null
        val nonLauncher = apps.filter { !isLauncherPackage(it.packageName) }
        val sensitive = nonLauncher.filter { packageSensitive(it.packageName) }
        if (sensitive.isNotEmpty()) {
            return (sensitive.firstOrNull { it.active || it.focused } ?: sensitive.first()).packageName
        }
        val pool = if (nonLauncher.isNotEmpty()) nonLauncher else apps
        return (pool.firstOrNull { it.active } ?: pool.firstOrNull { it.focused } ?: pool.first()).packageName
    }

    /**
     * Prefer the window list. An event package is only a fallback, and it must
     * not clear a Safe Mode app when no real replacement window is visible.
     */
    fun resolve(
        windows: List<AppWindow>,
        eventPackage: String?,
        heldPackage: String?,
        ignored: (String) -> Boolean,
        packageSensitive: (String) -> Boolean
    ): String? {
        val picked = pick(windows, ignored, packageSensitive)
        val event = eventPackage?.takeUnless { it.isBlank() || ignored(it) }
        if (event != null && packageSensitive(event) && (picked == null || !packageSensitive(picked))) {
            return event
        }
        if (heldPackage != null && packageSensitive(heldPackage)) {
            val heldVisible = windows.any { window ->
                window.typeApplication && window.packageName.equals(heldPackage, ignoreCase = true)
            }
            if (heldVisible) return heldPackage
            if (windows.isEmpty() && (picked == null || !packageSensitive(picked))) {
                return null
            }
        }
        if (picked != null) return picked
        if (event == null) return null
        if (isLauncherPackage(event) && heldPackage != null && packageSensitive(heldPackage)) return null
        return event
    }

    fun isLauncherPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return lower.contains("launcher") ||
            lower == "com.miui.home" ||
            lower == "com.teslacoilsw.launcher"
    }
}
