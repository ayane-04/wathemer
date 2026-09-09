// The hook side's one view of the settings store: the framework's remote preferences.
// No XSharedPreferences here: the modern API reads the framework's store, never a file.
package com.wathemer.app.hooks

import android.content.SharedPreferences
import android.os.SystemClock
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XposedBridge
import io.github.libxposed.api.XposedInterface

object ModulePrefs {

    private const val TAG = "WaThemer.Prefs"

    /** Refetch floor: shouldTheme reads on every resume and focus gain, and a binder call there would be felt. */
    private const val RELOAD_FLOOR_MS = 500L

    @Volatile private var iface: XposedInterface? = null
    @Volatile private var store: WtPrefs? = null

    /** Must run before any installer; every open() below this in the stack reads through it. */
    fun attach(x: XposedInterface) {
        iface = x
    }

    /** The store, empty-backed when the framework has none, so callers keep their unset-means-off behaviour. */
    fun open(): WtPrefs {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val s = WtPrefs(::fetch)
            store = s
            return s
        }
    }

    private fun fetch(): SharedPreferences? {
        val x = iface ?: return null
        return runCatching { x.getRemotePreferences(Prefs.FILE) }
            .onFailure { XposedBridge.log("[$TAG] remote preferences unavailable: $it") }
            .getOrNull()
    }

    /**
     * SharedPreferences over the remote store, refreshable in place so the fields holding it stay
     * valid. Absent a framework store every read returns its default, which is stock behaviour.
     */
    class WtPrefs internal constructor(private val provider: () -> SharedPreferences?) : SharedPreferences {

        @Volatile private var current: SharedPreferences? = provider()
        @Volatile private var lastFetch = SystemClock.elapsedRealtime()

        /** Refetches the store; floored because shouldTheme calls this on every resume and focus gain. */
        fun reload() {
            val now = SystemClock.elapsedRealtime()
            if (current != null && now - lastFetch < RELOAD_FLOOR_MS) return
            lastFetch = now
            provider()?.let { current = it }
        }

        override fun getAll(): MutableMap<String, *> = current?.all ?: mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? =
            current?.getString(key, defValue) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            current?.getStringSet(key, defValues) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = current?.getInt(key, defValue) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = current?.getLong(key, defValue) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = current?.getFloat(key, defValue) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            current?.getBoolean(key, defValue) ?: defValue
        override fun contains(key: String?): Boolean = current?.contains(key) ?: false

        /** Hooked apps read only; the framework enforces it, this just says so first. */
        override fun edit(): SharedPreferences.Editor =
            throw UnsupportedOperationException("remote preferences are read-only in the hooked app")

        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
            current?.registerOnSharedPreferenceChangeListener(l)
        }

        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {
            current?.unregisterOnSharedPreferenceChangeListener(l)
        }
    }
}
