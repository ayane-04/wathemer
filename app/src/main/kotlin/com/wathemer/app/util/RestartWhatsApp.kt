package com.wathemer.app.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Kills and relaunches WhatsApp; needs KILL_BACKGROUND_PROCESSES. Keep the launcher intent vanilla or WA gets locked to portrait. */
object RestartWhatsApp {

    private const val TAG = "WaThemer.Restart"
    private const val WA_PKG = "com.whatsapp"

    enum class Result {
        SUCCESS,
        NOT_INSTALLED,
        LAUNCH_FAILED,
    }

    /** Best-effort restart. The relaunch stays inside the worker: the kill must finish first, and su can block on the root prompt. */
    fun restart(context: Context, onResult: (Result) -> Unit) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(WA_PKG)
        if (launchIntent == null) {
            Log.w(TAG, "No launch intent for $WA_PKG; not installed?")
            onResult(Result.NOT_INSTALLED)
            return
        }
        // Hold the application context; the relaunch can land after the caller's Activity is gone.
        val app = context.applicationContext

        // Non-root kill, a cheap binder call; a no-op while WA is foreground, and on Android 14 and later always.
        tryKillBackgroundProcesses(context)

        Thread({
            // Root force-stop, silent without su; blocks until su returns, so the process is gone below here.
            tryRootForceStop()

            // Small delay to let the kill propagate before we relaunch.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    // Vanilla launcher intent; NewTask is required, nothing else gets added.
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    app.startActivity(launchIntent)
                    onResult(Result.SUCCESS)
                } catch (t: Throwable) {
                    Log.e(TAG, "launch failed", t)
                    onResult(Result.LAUNCH_FAILED)
                }
            }, 250L)
        }, "wathemer-restart").start()
    }

    private fun tryKillBackgroundProcesses(context: Context) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses(WA_PKG)
            Log.i(TAG, "killBackgroundProcesses($WA_PKG) called")
        } catch (t: Throwable) {
            Log.w(TAG, "killBackgroundProcesses failed: $t")
        }
    }

    /** Blocking. Call it off the main thread. See the note on [restart]. */
    private fun tryRootForceStop() {
        try {
            // su -c am force-stop; fails silently where su is missing or denied.
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $WA_PKG"))
            val exit = proc.waitFor()
            if (exit == 0) Log.i(TAG, "su force-stop succeeded")
            else Log.i(TAG, "su force-stop exited $exit (non-rooted or denied, fine)")
        } catch (t: Throwable) {
            Log.i(TAG, "su not available: ${t.message} (fine, non-rooted)")
        }
    }
}
