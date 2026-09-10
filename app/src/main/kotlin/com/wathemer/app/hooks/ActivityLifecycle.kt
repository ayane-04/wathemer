// Activity callbacks the framework dispatches itself, so there is no anchor for ART to inline away.
// Clients run in registration order, unlike Xposed after-hooks, which run in reverse.
package com.wathemer.app.hooks

import android.app.Activity
import android.app.Application
import android.os.Bundle
import de.robv.android.xposed.XposedBridge
import java.util.Collections
import java.util.WeakHashMap

object ActivityLifecycle {

    private const val TAG = "WaThemer.Lifecycle"

    private class Client(val name: String, val action: (Activity) -> Unit)

    // Registration and dispatch both run on the main thread, registration first, so plain lists are safe.
    private val created = ArrayList<Client>()
    private val resumed = ArrayList<Client>()
    private val createdEarly = ArrayList<EarlyClient>()
    private var attached = false

    private class EarlyClient(val name: String, val action: (Activity, Bundle?) -> Unit)

    /** Runs [action] inside the host's super.onCreate, before any layout exists. Read the Intent and hand work off; touch no view. */
    fun onCreatedEarly(name: String, action: (Activity, Bundle?) -> Unit) {
        createdEarly.add(EarlyClient(name, action))
    }

    /** Activities whose created-clients have run; weak so a finished Activity leaves no trace. */
    private val createdDone: MutableSet<Activity> = Collections.newSetFromMap(WeakHashMap())

    /** Runs [action] once per Activity at its first resume: after the host's onPostCreate, before the first traversal. */
    fun onCreated(name: String, action: (Activity) -> Unit) {
        created.add(Client(name, action))
    }

    /** Runs [action] on every Activity resume, in registration order. */
    fun onResumed(name: String, action: (Activity) -> Unit) {
        resumed.add(Client(name, action))
    }

    /** Registers the one callback set. Runs inside app create, before any Activity can exist. */
    fun attach(app: Application) {
        if (attached) return
        attached = true
        runCatching {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(a: Activity, saved: Bundle?) {
                    // The ledger line that separates a dead anchor from a feature that declined to run.
                    HookLog.hit("lifecycle/activity", a.javaClass.simpleName)
                    for (c in createdEarly) {
                        // Isolated per client: one thrower must not cost the features behind it.
                        try {
                            c.action(a, saved)
                        } catch (t: Throwable) {
                            HookLog.fail("lifecycle/${c.name}", t)
                        }
                    }
                }
                override fun onActivityResumed(a: Activity) {
                    // Never post from onCreate: a post before attach runs after the first frame, and the panes miss the wallpaper.
                    if (createdDone.add(a)) dispatch(created, a)
                    dispatch(resumed, a)
                }
                override fun onActivityStarted(a: Activity) {}
                override fun onActivityPaused(a: Activity) {}
                override fun onActivityStopped(a: Activity) {}
                override fun onActivitySaveInstanceState(a: Activity, out: Bundle) {}
                override fun onActivityDestroyed(a: Activity) {}
            })
            XposedBridge.log("[$TAG] activity callbacks registered")
            HookLog.arm("lifecycle/activity")
        }.onFailure { HookLog.fail("lifecycle/activity", it) }
    }

    private fun dispatch(clients: List<Client>, a: Activity) {
        for (c in clients) {
            // Isolated per client: one thrower must not cost the features behind it.
            try {
                c.action(a)
            } catch (t: Throwable) {
                HookLog.fail("lifecycle/${c.name}", t)
            }
        }
    }
}
