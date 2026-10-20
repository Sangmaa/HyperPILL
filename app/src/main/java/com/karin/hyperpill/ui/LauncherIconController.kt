package com.karin.hyperpill.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Turns this module's launcher entry on and off.
 *
 * The visible icon belongs to the [.LauncherAlias] activity-alias, never to
 * .MainActivity itself. The alias can be disabled while MainActivity keeps working, so the
 * screen the user is currently looking at stays reachable; disabling the activity's own
 * component would drop it from the system's component list and break every other entry
 * point into the app (LSPosed, adb, other apps, the intent the notification uses).
 *
 * No preference is stored: PackageManager already persists the component's enabled state
 * across reboots, and keeping a second copy in SharedPreferences would only create a way
 * for the two to disagree.
 */
object LauncherIconController {
    private const val ALIAS_CLASS = "com.karin.hyperpill.LauncherAlias"

    private fun alias(context: Context) = ComponentName(context.packageName, ALIAS_CLASS)

    fun isVisible(context: Context): Boolean {
        val pm = context.packageManager
        return runCatching {
            pm.getComponentEnabledSetting(alias(context)) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }.getOrDefault(true)
    }

    /**
     * DONT_KILL_APP matters here: killing the process right after the toggle would throw
     * away the mirrored session state and the screen would come back showing "未连接".
     */
    fun setVisible(context: Context, visible: Boolean) {
        val pm = context.packageManager
        runCatching {
            pm.setComponentEnabledSetting(
                alias(context),
                if (visible) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP
            )
        }
    }
}
