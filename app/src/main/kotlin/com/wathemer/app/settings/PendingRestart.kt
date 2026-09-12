// Whether WhatsApp still has to restart to see a change; the store reports every write and the restart clears it.
package com.wathemer.app.settings

import android.content.Context
import androidx.compose.runtime.mutableStateOf

object PendingRestart {
    private const val FILE = "wathemer_ui"
    private const val KEY = "pending_restart"

    val pending = mutableStateOf(false)

    fun load(context: Context) {
        pending.value = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun mark(context: Context) {
        if (pending.value) return
        pending.value = true
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
    }

    fun clear(context: Context) {
        pending.value = false
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, false).apply()
    }
}
