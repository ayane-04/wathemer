// WaEnhancer compatibility: one switch over every treatment of a surface that module adds or reveals.
package com.wathemer.app.hooks

import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XposedBridge

object WaeCompat {

    private const val TAG = "WaThemer.WaeCompat"

    /** Read once at app create; off leaves every WaEnhancer surface as stock WaThemer paints it. */
    @Volatile var enabled = true

    fun load() {
        enabled = ModulePrefs.open().getBoolean(Prefs.KEY_WAE_COMPAT, true)
        XposedBridge.log("[$TAG] ${if (enabled) "on" else "off"}")
    }
}
