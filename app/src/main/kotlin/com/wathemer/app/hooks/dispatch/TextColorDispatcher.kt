// One base hook on TextView.setTextColor (Int and ColorStateList overloads), fanned out to handlers.
// It rewrites the arg on every call, so WA's repaints cannot reset our colours.
package com.wathemer.app.hooks.dispatch

import android.content.res.ColorStateList
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.Collections
import java.util.IdentityHashMap

object TextColorDispatcher {
    private val idColors = HashMap<Int, Int>()
    private val handlers = ArrayList<(TextView) -> Int>()
    private var hooked = false

    /** Handlers that already logged a throw; this path runs on every setTextColor in the process. */
    private val loggedThrowers = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())

    private fun logThrowOnce(handler: Any, t: Throwable) {
        if (loggedThrowers.add(handler)) XposedBridge.log("WaThemer.Dispatch: text handler threw (logged once): $t")
    }

    /** Register a direct id->color override. id==0 / color==0 ignored. */
    fun mapColor(id: Int, color: Int) {
        if (id == 0 || color == 0) return
        idColors[id] = color
        ensureHooked()
    }

    /** Register a fallback handler returning 0 to abstain. First non-zero wins. */
    fun addHandler(handler: (TextView) -> Int) {
        handlers.add(handler)
        ensureHooked()
    }

    private fun ensureHooked() {
        if (hooked) return
        hooked = true
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val tv = param.thisObject as? TextView ?: return
                val id = tv.id
                val mapped: Int? = if (id != -1) idColors[id] else null
                if (mapped != null) { replaceArg(param, mapped); return }
                for (h in handlers) {
                    // Isolated per handler: one thrower must not starve the rest or log a stack per call.
                    val v = try { h(tv) } catch (t: Throwable) { logThrowOnce(h, t); 0 }
                    if (v != 0) { replaceArg(param, v); return }
                }
            }
        }
        XposedHelpers.findAndHookMethod(TextView::class.java, "setTextColor", Int::class.javaPrimitiveType, hook)
        XposedHelpers.findAndHookMethod(TextView::class.java, "setTextColor", ColorStateList::class.java, hook)
    }

    private fun replaceArg(param: XC_MethodHook.MethodHookParam, color: Int) {
        when (param.args[0]) {
            is Int -> param.args[0] = color
            is ColorStateList -> param.args[0] = ColorStateList.valueOf(color)
        }
    }
}
