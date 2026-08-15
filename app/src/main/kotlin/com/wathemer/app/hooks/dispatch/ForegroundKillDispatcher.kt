// One hook on View.setForeground, shared by every feature that needs to suppress one.
// Keep it single: competing setForeground hooks cause hard-to-find defects.
package com.wathemer.app.hooks.dispatch

import android.graphics.drawable.Drawable
import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object ForegroundKillDispatcher {

    /** A gate list per id: features can share an id; gates run at call time since a pref can be read after arming. */
    private val gates = HashMap<Int, MutableList<() -> Boolean>>()
    private var hooked = false

    /** Suppress the foreground on [id], when [gate] says so. `id == 0` is ignored. */
    @Synchronized
    fun kill(id: Int, gate: () -> Boolean = { true }) {
        if (id == 0 || id == -1) return
        gates.getOrPut(id) { ArrayList(1) }.add(gate)
        ensureHook()
    }

    private fun ensureHook() {
        if (hooked) return
        hooked = true
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java, "setForeground", Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        // Fires for every View in the process; the common case must stay a map miss and early return.
                        if (p.args[0] == null) return
                        val v = p.thisObject as? View ?: return
                        val id = v.id
                        if (id == View.NO_ID) return
                        val list = synchronized(this@ForegroundKillDispatcher) { gates[id] } ?: return
                        for (g in list) {
                            if (runCatching { g() }.getOrDefault(false)) {
                                p.args[0] = null
                                return
                            }
                        }
                    }
                },
            )
            XposedBridge.log("WaThemer.FgKill: armed")
        }.onFailure { XposedBridge.log("WaThemer.FgKill: hook FAILED: ${it.stackTraceToString()}") }
    }
}
