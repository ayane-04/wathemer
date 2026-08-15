// On-disk cache of resolved names, keyed on WA's versionName so an upgrade invalidates it.
package com.wathemer.app.hooks.dexkit

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import de.robv.android.xposed.XposedBridge
import org.json.JSONObject

private const val TAG = "WaThemer.DeobCache"
private const val SP_FILE = "wathemer_deobfuscator"
private const val KEY_WA_VERSION = "wa_version"
private const val KEY_CACHE_BLOB = "cache"

class DeobfuscatorCache {

    private val data: MutableMap<String, String> = mutableMapOf()
    private var waVersion: String = ""

    fun getString(key: String): String? = data[key]
    fun putString(key: String, value: String) { data[key] = value }
    fun remove(key: String) { data.remove(key) }

    fun load(app: Application) {
        try {
            val sp = app.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
            val storedWa = sp.getString(KEY_WA_VERSION, "") ?: ""
            val currentWa = readWaVersion(app)
            waVersion = currentWa
            if (storedWa != currentWa) {
                XposedBridge.log("$TAG: WA version changed ($storedWa -> $currentWa); invalidating cache")
                sp.edit().clear().putString(KEY_WA_VERSION, currentWa).apply()
                data.clear()
                return
            }
            val blob = sp.getString(KEY_CACHE_BLOB, null) ?: return
            val json = JSONObject(blob)
            val it = json.keys()
            while (it.hasNext()) {
                val k = it.next()
                data[k] = json.getString(k)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: load failed: ${t.message}")
        }
    }

    fun save(app: Application) {
        try {
            val sp = app.getSharedPreferences(SP_FILE, Context.MODE_PRIVATE)
            val json = JSONObject()
            for ((k, v) in data) json.put(k, v)
            sp.edit()
                .putString(KEY_WA_VERSION, waVersion)
                .putString(KEY_CACHE_BLOB, json.toString())
                .apply()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: save failed: ${t.message}")
        }
    }

    private fun readWaVersion(app: Application): String = try {
        @Suppress("DEPRECATION")
        val info = if (Build.VERSION.SDK_INT >= 33) {
            app.packageManager.getPackageInfo(app.packageName, PackageManager.PackageInfoFlags.of(0L))
        } else {
            app.packageManager.getPackageInfo(app.packageName, 0)
        }
        info.versionName ?: ""
    } catch (t: Throwable) {
        XposedBridge.log("$TAG: readWaVersion failed: ${t.message}")
        ""
    }
}
