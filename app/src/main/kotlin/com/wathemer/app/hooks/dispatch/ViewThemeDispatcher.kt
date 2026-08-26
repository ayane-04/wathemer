// One base hook on View.onAttachedToWindow, fanned out to every registered handler.
// It fires on every attach, so a tab-swap reattach re-runs our paint for free.
package com.wathemer.app.hooks.dispatch

import android.view.View
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.WaIds
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

    /** Ledger names, built once at registration: this runs per attach and a concat here is per row. */
    private val labels = HashMap<Int, String>()

    private fun label(id: Int) = labels.getOrPut(id) { "view/${WaIds.nameOf(id)}" }

    /** Register an id-targeted callback. `id == -1` or `id == 0` is ignored. */
    fun onId(id: Int, action: (View) -> Unit) {
        if (id == -1 || id == 0) return
        val list = idActions.getOrPut(id) { ArrayList() }
        list.add(action)
        // Armed, not yet fired. The summary's job is to show which of these never attach.
        HookLog.arm(label(id), if (list.size > 1) "${list.size} handlers" else "")
        ensureHooked()
    }

    /** Register a fallback predicate callback. Return true to consume; false to continue. */
    fun onView(action: (View) -> Boolean) {
        viewActions.add(action)
        HookLog.arm("view/predicate#${viewActions.size}")
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
                    if (id != -1) idActions[id]?.let { handlers ->
                        // Counted here rather than per handler: one attach of this id is one event.
                        HookLog.hit(label(id), view.javaClass.simpleName)
                        for (a in handlers) {
                            try { a(view) } catch (t: Throwable) {
                                logThrowOnce(a, t)
                                HookLog.fail(label(id), t)
                            }
                        }
                    }
                    for (h in viewActions) {
                        val consumed = try { h(view) } catch (t: Throwable) { logThrowOnce(h, t); false }
                        if (consumed) return
                    }
                }
            },
        )
    }

    /** Ids registered but never attached, which is the state a screenshot cannot tell you about. */
    fun report() {
        val armed = idActions.keys.filterNot { HookLog.isHit(label(it)) }
        XposedBridge.log(
            "WaThemer.Dispatch: ${idActions.size} ids registered, ${armed.size} never attached" +
                if (armed.isEmpty()) "" else ": ${armed.map { WaIds.nameOf(it) }.sorted()}"
        )
    }
}
