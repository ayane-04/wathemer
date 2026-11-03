// The store as the screen sees it: one catalogue per process, the check and the downloads behind it, off the main thread.
package com.wathemer.app.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object StoreSession {

    private const val TAG = "WaThemer.Store"

    /** The list on screen and what the last check said about it. */
    data class View(val loaded: StoreRepository.Loaded?, val status: Status)

    sealed interface Status {
        data object Idle : Status
        data object Checking : Status
        data object NewerSchema : Status
        data object NotAvailable : Status
        data object Busy : Status
        data object Unreachable : Status
        data object Unverified : Status
    }

    sealed interface Download {
        data class Running(val received: Long, val size: Long) : Download
        /** Waits here until the screen has said what happened. */
        data class Finished(val theme: StoreTheme, val result: StoreInstall.Result) : Download
    }

    // Process-wide, so leaving the screen and coming back finds the list and the downloads where they were.
    val view = mutableStateOf(View(null, Status.Idle))
    val downloads = mutableStateMapOf<String, Download>()

    private val jobs = ConcurrentHashMap<String, StoreJob>()
    private val shas = ConcurrentHashMap<String, String>()
    private val paused = ConcurrentHashMap<String, StoreTheme>()
    private val main = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean(false)
    private val loading = AtomicBoolean(false)
    private val watching = AtomicInteger(0)
    private val tidied = AtomicBoolean(false)
    private val worker = Executors.newCachedThreadPool { r -> Thread(r, "wathemer-store") }
    private val http by lazy { StoreConfig.http() }

    /** The screen came up: the cached list at once, then the site when that list is stale or missing. */
    fun open(context: Context) {
        val app = context.applicationContext
        val shown = view.value.loaded
        if (shown != null) {
            if (!StoreRepository.isFresh(shown, System.currentTimeMillis())) refresh(app)
            return
        }
        if (!loading.compareAndSet(false, true)) return
        worker.execute {
            val cached = runCatching { StoreRepository.cached(app, StoreConfig.TARGET) }
                .onFailure { Log.w(TAG, "cached catalogue unreadable: $it") }.getOrNull()
            main.post {
                loading.set(false)
                if (view.value.loaded == null && cached != null) view.value = view.value.copy(loaded = cached)
                if (cached == null || !StoreRepository.isFresh(cached, System.currentTimeMillis())) refresh(app) else tidyOnce(app)
            }
        }
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        if (!checking.compareAndSet(false, true)) return
        view.value = view.value.copy(status = Status.Checking)
        worker.execute {
            val result = runCatching { StoreRepository.refresh(app, StoreConfig.TARGET, http, System.currentTimeMillis()) }
                .getOrElse { StoreRepository.Refreshed.Unreachable(it.javaClass.simpleName) }
            main.post {
                checking.set(false)
                val shown = view.value.loaded
                view.value = when (result) {
                    is StoreRepository.Refreshed.Ok -> View(result.loaded, Status.Idle)
                    StoreRepository.Refreshed.NewerSchema -> View(shown, Status.NewerSchema)
                    StoreRepository.Refreshed.NotAvailable -> View(shown, Status.NotAvailable)
                    is StoreRepository.Refreshed.Busy -> View(shown, Status.Busy)
                    is StoreRepository.Refreshed.Unreachable -> View(shown, Status.Unreachable)
                    is StoreRepository.Refreshed.Unverified -> View(shown, Status.Unverified)
                }
                if (result !is StoreRepository.Refreshed.Ok) Log.w(TAG, "catalogue check: $result")
                tidyOnce(app)
            }
        }
    }

    /** Get or Update; a second tap while one runs does nothing. */
    fun get(context: Context, theme: StoreTheme) {
        val app = context.applicationContext
        val job = StoreJob()
        if (jobs.putIfAbsent(theme.id, job) != null) return
        // Two entries with the very same file would share one part on disk.
        if (shas.putIfAbsent(theme.fileSha256, theme.id) != null) {
            jobs.remove(theme.id)
            return
        }
        paused.remove(theme.id)
        downloads[theme.id] = Download.Running(0L, theme.fileSize)
        worker.execute {
            var shownAt = 0L
            var shownPercent = -1
            val result = runCatching {
                StoreInstall.get(app, theme, http, job) { received ->
                    val percent = (received * 100 / theme.fileSize).toInt()
                    val now = SystemClock.uptimeMillis()
                    // Each percent at most, and no more than four times a second: a tick redraws one tile, not the grid.
                    if (percent != shownPercent && now - shownAt >= 250) {
                        shownPercent = percent
                        shownAt = now
                        main.post { if (jobs[theme.id] === job) downloads[theme.id] = Download.Running(received, theme.fileSize) }
                    }
                }
            }.getOrElse { StoreInstall.Result.Failed(it.javaClass.simpleName) }
            main.post { finish(app, theme, job, result) }
        }
    }

    // On the worker, never the main thread: closing a connection is socket work too.
    fun cancel(id: String) {
        val job = jobs[id] ?: return
        worker.execute { job.cancel() }
    }

    /** The screen has shown what happened to [id]. */
    fun consume(id: String) {
        if (downloads[id] is Download.Finished) downloads.remove(id)
    }

    /** Counts the store screens on display; a result nobody is there to see becomes a toast. */
    fun watch(on: Boolean) {
        if (on) watching.incrementAndGet() else watching.decrementAndGet()
    }

    /** Back in the app: downloads the system cut off while it was away pick up where they stopped. */
    fun resumePaused(context: Context) {
        for (theme in paused.values) get(context, theme)
    }

    /** What to tell the user about [result], on the store screen or, when [onScreen] is false, in a toast; null for nothing. */
    fun messageFor(theme: StoreTheme, result: StoreInstall.Result, onScreen: Boolean): String? = when (result) {
        is StoreInstall.Result.Added -> "${theme.name} added to your themes."
        StoreInstall.Result.AlreadySaved -> "${theme.name} is already in your themes."
        StoreInstall.Result.NeedsNewerApp -> "${theme.name} needs a newer WaThemer."
        StoreInstall.Result.NoSpace -> "Not enough free space for ${theme.name}."
        StoreInstall.Result.Missing -> "${theme.name} is no longer in the store."
        is StoreInstall.Result.Busy -> "Store busy. Try again in a few minutes."
        StoreInstall.Result.Interrupted ->
            if (onScreen) "Download stopped. Tap Get to resume."
            else "${theme.name}: download paused, resumes when you're back."
        StoreInstall.Result.Cancelled -> null
        is StoreInstall.Result.Refused -> result.reason
        is StoreInstall.Result.Failed -> "Couldn't download ${theme.name}."
    }

    private fun finish(app: Context, theme: StoreTheme, job: StoreJob, result: StoreInstall.Result) {
        if (jobs[theme.id] !== job) return
        jobs.remove(theme.id)
        shas.remove(theme.fileSha256)
        if (result is StoreInstall.Result.Interrupted) paused[theme.id] = theme
        if (result is StoreInstall.Result.Failed) Log.w(TAG, "get ${theme.id} v${theme.version}: ${result.reason}")
        if (watching.get() > 0) {
            downloads[theme.id] = Download.Finished(theme, result)
        } else {
            downloads.remove(theme.id)
            messageFor(theme, result, onScreen = false)?.let { Toast.makeText(app, it, Toast.LENGTH_SHORT).show() }
        }
    }

    /** Once per process, after the first list: leftovers no catalogue names, and the library's own. */
    private fun tidyOnce(app: Context) {
        val index = view.value.loaded?.index ?: return
        if (!tidied.compareAndSet(false, true)) return
        val busy = index.themes.filter { it.id in jobs.keys }.mapTo(HashSet()) { it.fileSha256 }
        worker.execute {
            runCatching { StoreFiles.tidy(app, index, busy, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "tidy failed: $it") }
        }
    }
}
