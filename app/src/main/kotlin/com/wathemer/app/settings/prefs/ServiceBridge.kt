// The settings app's line to the framework's preference store; nothing in the harness set may import this.
package com.wathemer.app.settings.prefs

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object ServiceBridge {

    private const val TAG = "WaThemer.Bridge"

    /** Bind arrives fast when a framework is present; past this it is absent and the caller shows the banner. */
    private const val BIND_TIMEOUT_MS = 2000L

    @Volatile private var service: XposedService? = null
    private val bound = CountDownLatch(1)
    @Volatile private var registered = false

    /** registerListener may be called once per process, so this is the only place that calls it. */
    private fun ensureRegistered() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            registered = true
            runCatching {
                XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
                    override fun onServiceBind(s: XposedService) {
                        service = s
                        runCatching {
                            Log.i(TAG, "bound: ${s.frameworkName} ${s.frameworkVersion} (api ${s.apiVersion})")
                        }
                        bound.countDown()
                    }

                    override fun onServiceDied(s: XposedService) {
                        if (service === s) service = null
                        Log.w(TAG, "service died")
                    }
                })
            }.onFailure { Log.w(TAG, "registerListener failed: $it") }
        }
    }

    /** The remote store, or null when no framework answers in time; blocks at most [BIND_TIMEOUT_MS]. */
    fun awaitPrefs(): SharedPreferences? {
        ensureRegistered()
        if (service == null) {
            runCatching { bound.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        }
        val s = service ?: return null
        return runCatching { s.getRemotePreferences(Prefs.FILE) }
            .onFailure { Log.w(TAG, "getRemotePreferences failed: $it") }
            .getOrNull()
    }
}
