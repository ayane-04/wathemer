// Per-chat wallpapers on the hook side: the map from the store, the provider road into WhatsApp's own files,
// and a prefetch that starts inside the chat's onCreate so the image is decoded before the first frame.
package com.wathemer.app.hooks.wallpaper

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.ActivityLifecycle
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.ModulePrefs
import com.wathemer.app.hooks.glass.GlassHook
import com.wathemer.app.settings.prefs.ChatWallpaperLibrary
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.WeakHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

object ChatWallpapers {

    private const val TAG = "WaThemer.ChatWP"
    const val CONVERSATION = "com.whatsapp.Conversation"
    private const val CACHE_PREFIX = "wt_chatwp_"

    /** How long the first resume waits for a decode still in flight; past it the chat opens on the global wallpaper. */
    private const val WAIT_MS = 400L

    /** A chat entry with its bitmap decoded, ready to inject. */
    class Prepared(val entry: ChatWallpaperLibrary.Entry, val bitmap: Bitmap)

    private val xprefs: ModulePrefs.WtPrefs by lazy { ModulePrefs.open() }

    /** One thread: a decode and a provider copy are both bulk work, and two at once would only fight for the disk. */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wathemer-wallpaper").apply { isDaemon = true }
    }

    /** Main thread only, like the lifecycle callbacks that fill and drain it. Weak: a finished Activity leaves no trace. */
    private val pending = WeakHashMap<Activity, Future<Prepared?>>()

    @Volatile private var mapJson: String? = null
    @Volatile private var map: List<ChatWallpaperLibrary.Entry> = emptyList()

    /** Arms the early prefetch, warms the global wallpaper off the main thread, and prunes stale copies. */
    fun install(app: Application) {
        ActivityLifecycle.onCreatedEarly("chatWallpaper") { a, saved -> prefetch(a, saved) }
        HookLog.arm("chat/wallpaperPrefetch")
        worker.execute {
            runCatching { warmGlobal(app) }.onFailure { HookLog.fail("chat/wallpaperWarm", it) }
            runCatching { prune(app) }.onFailure { HookLog.fail("chat/wallpaperPrune", it) }
        }
    }

    /** The store's list, parsed once per change; the caller decides whether a reload preceded this. */
    private fun entries(): List<ChatWallpaperLibrary.Entry> {
        val json = xprefs.getString(Prefs.KEY_CHAT_WALLPAPERS, "") ?: ""
        if (json != mapJson) {
            map = ChatWallpaperLibrary.parse(json)
            mapJson = json
        }
        return map
    }

    /** The chat this Conversation shows, from its Intent; WhatsApp writes the same key into the saved state. */
    fun jidOf(a: Activity, saved: Bundle?): String? =
        a.intent?.getStringExtra("jid")?.takeIf { it.isNotBlank() } ?: saved?.getString("jid")

    /** The entry for this Conversation's chat, or null for the global wallpaper. */
    fun entryFor(a: Activity, saved: Bundle?): ChatWallpaperLibrary.Entry? {
        if (a.javaClass.name != CONVERSATION) return null
        val jid = jidOf(a, saved) ?: return null
        return entries().firstOrNull { it.jid == jid }
    }

    /** Inside the chat's onCreate: read the Intent, hand the decode off, return. Nothing here may touch a view. */
    private fun prefetch(a: Activity, saved: Bundle?) {
        if (a.javaClass.name != CONVERSATION) return
        // Snapshot reads only: the reload is a binder call and belongs to the first resume, where it already happens.
        if (!xprefs.getBoolean(Prefs.KEY_WALLPAPER_ENABLED, false)) return
        val entry = entryFor(a, saved) ?: return
        val app = a.applicationContext
        val started = SystemClock.uptimeMillis()
        pending[a] = worker.submit(Callable { prepare(app, entry, started) })
        HookLog.hit("chat/wallpaperPrefetch", "stamp ${entry.stamp}")
    }

    /** At first resume: the prefetched bitmap, waited for briefly, else prepared here; null means the global wallpaper this time. */
    fun take(a: Activity, entry: ChatWallpaperLibrary.Entry): Prepared? {
        val future = pending.remove(a)
        if (future != null) {
            val t0 = SystemClock.uptimeMillis()
            val ready = runCatching { future.get(WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()
            val waited = SystemClock.uptimeMillis() - t0
            if (ready != null && ready.entry.stamp == entry.stamp) {
                XposedBridge.log("$TAG: stamp ${entry.stamp} ready at first resume after ${waited}ms wait")
                return ready
            }
            if (!future.isDone) {
                // Still decoding: the chat opens on the global wallpaper and the next resume picks this one up.
                XposedBridge.log("$TAG: stamp ${entry.stamp} not ready after ${waited}ms; global wallpaper this time")
                HookLog.hit("chat/wallpaperWaitTimeout", "stamp ${entry.stamp}")
                return null
            }
        }
        return prepare(a.applicationContext, entry, SystemClock.uptimeMillis())
    }

    /** Synchronous prepare for the resume re-check; the cache makes a repeat free. */
    fun prepareNow(context: Context, entry: ChatWallpaperLibrary.Entry): Prepared? =
        prepare(context.applicationContext, entry, SystemClock.uptimeMillis())

    private fun prepare(context: Context, entry: ChatWallpaperLibrary.Entry, started: Long): Prepared? {
        val file = resolve(context, entry) ?: return null
        val cached = WallpaperCache.get(file.absolutePath, entry.blur)
        if (cached != null) return Prepared(entry, cached)
        val dm = context.resources.displayMetrics
        val bitmap = BitmapDecoder.decodeScaled(file, dm.widthPixels, dm.heightPixels) ?: run {
            XposedBridge.log("$TAG: stamp ${entry.stamp} did not decode (${file.length()} bytes)")
            return null
        }
        WallpaperCache.put(file.absolutePath, entry.blur, bitmap)
        XposedBridge.log(
            "$TAG: stamp ${entry.stamp} decoded ${bitmap.width}x${bitmap.height} in " +
                "${SystemClock.uptimeMillis() - started}ms on ${Thread.currentThread().name}",
        )
        return Prepared(entry, bitmap)
    }

    /** WhatsApp's own copy, fetched once from the settings app's provider; a stamp never changes bytes, so a hit is right by construction. */
    private fun resolve(context: Context, entry: ChatWallpaperLibrary.Entry): File? {
        val cache = File(context.filesDir, CACHE_PREFIX + entry.stamp)
        if (cache.canRead() && cache.length() > 0) return cache
        val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.wallpapers/${entry.file}")
        // The pid in the name: every WhatsApp process runs this module, and two could race one target.
        val part = File(context.filesDir, "${cache.name}.part${Process.myPid()}")
        return runCatching {
            context.contentResolver.openInputStream(uri)!!.use { input ->
                part.outputStream().use { out -> input.copyTo(out) }
            }
            if (!part.renameTo(cache)) {
                cache.delete()
                check(part.renameTo(cache))
            }
            HookLog.hit("chat/wallpaperFetch", "stamp ${entry.stamp}")
            cache
        }.onFailure {
            part.delete()
            XposedBridge.log("$TAG: fetch of stamp ${entry.stamp} failed: $it")
            HookLog.hit("chat/wallpaperFetchFailed", "stamp ${entry.stamp}: $it")
        }.getOrNull()
    }

    /** Decodes the global wallpaper into the pinned slot, so no first resume pays for it, and hands the glass its brightness. */
    private fun warmGlobal(app: Application) {
        xprefs.reload()
        if (!xprefs.getBoolean(Prefs.KEY_WALLPAPER_ENABLED, false)) return
        val file = WallpaperResolver.resolve(app, xprefs) ?: return
        val blur = xprefs.getInt(Prefs.KEY_WALLPAPER_BLUR, 0)
        val bitmap = WallpaperCache.get(file.absolutePath, blur) ?: run {
            val dm = app.resources.displayMetrics
            val t0 = SystemClock.uptimeMillis()
            BitmapDecoder.decodeScaled(file, dm.widthPixels, dm.heightPixels)?.also {
                WallpaperCache.put(file.absolutePath, blur, it, pinned = true)
                XposedBridge.log("$TAG: global wallpaper warmed in ${SystemClock.uptimeMillis() - t0}ms")
            }
        } ?: return
        val dim = xprefs.getInt(Prefs.KEY_WALLPAPER_DIM, 0).coerceIn(0, 100) / 100f
        GlassHook.noteGlobalWallpaper(bitmap, dim)
        HookLog.hit("chat/wallpaperWarm")
    }

    /** Deletes cached copies no entry names any more. Only with the feature on: an absent store reads as an empty list. */
    private fun prune(app: Application) {
        if (!xprefs.getBoolean(Prefs.KEY_WALLPAPER_ENABLED, false)) return
        val live = entries().map { it.stamp.toString() }.toHashSet()
        var removed = 0
        app.filesDir.listFiles()?.forEach { f ->
            val name = f.name
            if (!name.startsWith(CACHE_PREFIX) || name.contains(".part")) return@forEach
            if (name.removePrefix(CACHE_PREFIX) !in live && f.delete()) removed++
        }
        if (removed > 0) XposedBridge.log("$TAG: pruned $removed stale chat wallpaper copies")
    }
}
