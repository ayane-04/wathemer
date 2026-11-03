// SharedPreferences in memory, for previews: per key the last editor call wins, as in the platform's own.
package com.wathemer.app.settings.prefs

import android.content.SharedPreferences

internal class MemoryPrefs(initial: Map<String, *>) : SharedPreferences {
    private val map = HashMap<String, Any?>(initial)

    override fun getAll(): MutableMap<String, *> = HashMap(map)

    override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue

    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String, Any?>()
        private var clearFirst = false

        private fun set(k: String?, v: Any?): SharedPreferences.Editor {
            if (k != null) pending[k] = v
            return this
        }

        override fun putString(k: String?, v: String?) = if (v == null) remove(k) else set(k, v)
        override fun putStringSet(k: String?, v: MutableSet<String>?) = if (v == null) remove(k) else set(k, v.toSet())
        override fun putInt(k: String?, v: Int) = set(k, v)
        override fun putLong(k: String?, v: Long) = set(k, v)
        override fun putFloat(k: String?, v: Float) = set(k, v)
        override fun putBoolean(k: String?, v: Boolean) = set(k, v)
        override fun remove(k: String?) = set(k, REMOVED)

        override fun clear(): SharedPreferences.Editor {
            clearFirst = true
            return this
        }

        override fun commit(): Boolean {
            if (clearFirst) map.clear()
            for ((k, v) in pending) if (v === REMOVED) map.remove(k) else map[k] = v
            return true
        }

        override fun apply() {
            commit()
        }
    }

    private companion object {
        val REMOVED = Any()
    }
}
