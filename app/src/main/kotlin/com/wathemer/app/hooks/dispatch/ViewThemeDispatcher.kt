// One base hook on View.onAttachedToWindow, fanned out to every registered handler.
// It fires on every attach, so a tab-swap reattach re-runs our paint for free.
package com.wathemer.app.hooks.dispatch

import android.view.View
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.IdentityHashMap

object ViewThemeDispatcher {
    private val idActions = HashMap<Int, MutableList<(View) -> Unit>>()
    private val viewActions = ArrayList<(View) -> Boolean>()
    private var hooked = false

    /** Handlers that already logged a throw; a persistent thrower runs every attach and must not spam. */
    private val loggedThrowers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    private fun logThrowOnce(handler: Any, t: Throwable) {
        if (loggedThrowers.add(handler)) XposedBridge.log("WaThemer.Dispatch: view handler threw (logged once): $t")
    }

    /** Register an id-targeted callback. `id == -1` or `id == 0` is ignored. */
    fun onId(id: Int, action: (View) -> Unit) {
        if (id == -1 || id == 0) return
        idActions.getOrPut(id) { ArrayList() }.add(action)
        ensureHooked()
    }

    /** Register a fallback predicate callback. Return true to consume; false to continue. */
    fun onView(action: (View) -> Boolean) {
        viewActions.add(action)
        ensureHooked()
    }

    private fun ensureHooked() {
        if (hooked) return
        hooked = true
        XposedHelpers.findAndHookMethod(
            View::class.java, "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    val id = view.id
                    // Isolated per handler: one thrower must not starve the rest of the fan-out.
                    if (id != -1) idActions[id]?.forEach { a ->
                        try { a(view) } catch (t: Throwable) { logThrowOnce(a, t) }
                    }
                    for (h in viewActions) {
                        val consumed = try { h(view) } catch (t: Throwable) { logThrowOnce(h, t); false }
                        if (consumed) return
                    }
                }
            },
        )
    }
}
