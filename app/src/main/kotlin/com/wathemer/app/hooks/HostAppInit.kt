// One anchor for work that needs the host Application.
// android.app.Application.onCreate is empty, so ART inlines it away and a hook there can never fire.
package com.wathemer.app.hooks

import android.app.Application
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

object HostAppInit {

    private const val TAG = "WaThemer.AppInit"
    private const val WHATSAPP_PKG = "com.whatsapp"

    // Runs low to high. Set here so the sequence never depends on Xposed's callback ordering.
    const val ORDER_FONT = 0
    const val ORDER_MAIN = 1
    const val ORDER_HOME = 2

    private class Client(val order: Int, val name: String, val action: (Application) -> Unit)

    @Volatile private var hostOnCreate: Method? = null
    // Registration and dispatch both run on the main thread, registration first, so a plain list is safe.
    private val clients = ArrayList<Client>()
    private var hooked = false
    private val fired = AtomicBoolean(false)

    /** Finds the host's own onCreate override. Must run before any [onCreate] registration. */
    fun resolve(appClassName: String?, classLoader: ClassLoader) {
        // Loading the host class early must never cost the installers that follow, so nothing escapes here.
        val m = runCatching { findHostOnCreate(appClassName, classLoader) }
            .onFailure { XposedBridge.log("[$TAG] anchor lookup threw: $it") }
            .getOrNull()
        hostOnCreate = m
        if (m == null) {
            XposedBridge.log("[$TAG] host declares no onCreate; framework anchor only")
            HookLog.skip("anchor/appCreate", "host declares no onCreate")
        } else {
            XposedBridge.log("[$TAG] anchor ${m.declaringClass.name}.onCreate")
            HookLog.arm("anchor/appCreate", m.declaringClass.name)
        }
    }

    /** Runs [action] once with the host Application, in [order]; both anchors are hooked and the host override is entered first, so it wins wherever it resolves and the framework one idles. */
    fun onCreate(order: Int, name: String, action: (Application) -> Unit) {
        clients.add(Client(order, name, action))
        ensureHooked()
    }

    private fun ensureHooked() {
        if (hooked) return
        hooked = true
        val cb = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) = dispatch(param)
        }
        // Kept as the second anchor: it is what fires for a host that overrides nothing.
        runCatching { XposedHelpers.findAndHookMethod(Application::class.java, "onCreate", cb) }
            .onFailure { HookLog.fail("anchor/appCreate", it) }
        hostOnCreate?.let { m ->
            runCatching { XposedBridge.hookMethod(m, cb) }.onFailure { HookLog.fail("anchor/appCreate", it) }
        }
    }

    private fun dispatch(param: XC_MethodHook.MethodHookParam) {
        val app = param.thisObject as? Application ?: return
        if (app.packageName != WHATSAPP_PKG) return
        if (!fired.compareAndSet(false, true)) return
        logAnchor(param.method)
        // Sorted here rather than left to Xposed's callback order, which is reversed for after-hooks.
        for (c in clients.sortedBy { it.order }) {
            // Isolated per client: one thrower must not cost the installers behind it.
            try {
                c.action(app)
            } catch (t: Throwable) {
                HookLog.fail("appCreate/${c.name}", t)
                XposedBridge.log(t)
            }
        }
    }

    /** Which anchor fired, once per process; without it a dead anchor reads as a clean install. */
    private fun logAnchor(anchor: Any?) {
        val via = if (anchor != null && anchor == hostOnCreate) "host override" else "framework"
        XposedBridge.log("[$TAG] app create fired via $via")
        HookLog.hit("anchor/appCreate", via)
    }

    /** First declared no-arg onCreate at or above the manifest's application class, stopping short of the framework's. */
    private fun findHostOnCreate(appClassName: String?, classLoader: ClassLoader): Method? {
        val declared = appClassName ?: return null
        var c: Class<*>? = XposedHelpers.findClassIfExists(declared, classLoader)
        while (c != null && c != Application::class.java) {
            val m = runCatching { c.getDeclaredMethod("onCreate") }.getOrNull()
            // Walking up from the concrete class, the first override is the one virtual dispatch reaches.
            if (m != null && overridesAppCreate(m)) return m
            c = c.superclass
        }
        return null
    }

    /** A private or static same-name method is not the override, and hooking it would anchor to nothing. */
    private fun overridesAppCreate(m: Method): Boolean {
        val mods = m.modifiers
        if (Modifier.isStatic(mods) || Modifier.isPrivate(mods)) return false
        return m.returnType == Void.TYPE
    }
}
