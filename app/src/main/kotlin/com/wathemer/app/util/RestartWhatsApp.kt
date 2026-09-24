package com.wathemer.app.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/** Kills every installed host and relaunches the first; needs KILL_BACKGROUND_PROCESSES. Keep the launcher intent vanilla or WA gets locked to portrait. */
object RestartWhatsApp {

    private const val TAG = "WaThemer.Restart"

    enum class Result {
        SUCCESS,
        NOT_INSTALLED,
        LAUNCH_FAILED,
    }

    /** Installed hosts, in HostPackages order; an absent one has no launch intent. */
    private fun installedHosts(context: Context): List<String> =
        HostPackages.ALL.filter { context.packageManager.getLaunchIntentForPackage(it) != null }

    /** Best-effort restart. The relaunch stays inside the worker: the kill must finish first, and su can block on the root prompt. */
    fun restart(context: Context, onResult: (Result) -> Unit) {
        // One store serves both hosts, so a write dirties every installed one and all of them are killed.
        val hosts = installedHosts(context)
        if (hosts.isEmpty()) {
            Log.w(TAG, "no launch intent for any host; not installed?")
            onResult(Result.NOT_INSTALLED)
            return
        }
        // Only one can be brought to the front, so the first installed host wins and the rest come back cold.
        val launchIntent = context.packageManager.getLaunchIntentForPackage(hosts.first())
        if (launchIntent == null) {
            onResult(Result.NOT_INSTALLED)
            return
        }
        // Hold the application context; the relaunch can land after the caller's Activity is gone.
        val app = context.applicationContext

        // Non-root kill, a cheap binder call; a no-op while WA is foreground, and on Android 14 and later always.
        for (pkg in hosts) tryKillBackgroundProcesses(context, pkg)

        Thread({
            // Root force-stop, silent without su; blocks until su returns, so the process is gone below here.
            for (pkg in hosts) tryRootForceStop(pkg)

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

    private fun tryKillBackgroundProcesses(context: Context, pkg: String) {
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            am?.killBackgroundProcesses(pkg)
            Log.i(TAG, "killBackgroundProcesses($pkg) called")
        } catch (t: Throwable) {
            Log.w(TAG, "killBackgroundProcesses($pkg) failed: $t")
        }
    }

    /** Blocking. Call it off the main thread. See the note on [restart]. */
    private fun tryRootForceStop(pkg: String) {
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "am force-stop $pkg"))
            val exit = proc.waitFor()
            if (exit == 0) Log.i(TAG, "su force-stop $pkg succeeded")
            else Log.i(TAG, "su force-stop $pkg exited $exit (non-rooted or denied, fine)")
        } catch (t: Throwable) {
            Log.i(TAG, "su not available: ${t.message} (fine, non-rooted)")
        }
    }
}
