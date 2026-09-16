// Receipt tick artwork: WhatsApp keeps choosing the glyph and the tint, the drawable is swapped at the view.
// Read arrives as a blue tint on the same drawable id, so the read art swaps in when that tint lands.
package com.wathemer.app.hooks

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.view.ViewGroup
import android.widget.ImageView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.hooks.glass.tagKey
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.prefs.TickStyles
import com.wathemer.app.settings.prefs.TickStyles.State
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

object TickShapes {

    private const val TAG = "WaThemer.TickShapes"

    private class Kind(val state: State, val media: Boolean)

    private val xprefs: ModulePrefs.WtPrefs by lazy { ModulePrefs.open() }
    private var style = 0
    private var moduleRes: Resources? = null
    private val pkg = BuildConfig.APPLICATION_ID

    // WhatsApp's receipt drawable ids, resolved by name at install, each mapped to the state it stands for.
    private val kinds = HashMap<Int, Kind>()
    private var statusId = 0
    private var serverReceiveId = 0
    private var clientId = 0
    private var scheduleId = 0

    private val kindTag = tagKey("wathemer-tick-kind")
    private val readTag = tagKey("wathemer-tick-read")
    private val unfixedTag = tagKey("wathemer-tick-unfixed")

    // Main thread only: set while this object clears a tint it put there, so the tint hook lets that call through.
    private var clearing = false

    // Bitmaps are shared read-only; every view gets its own BitmapDrawable around one.
    private val bitmaps = ConcurrentHashMap<String, Bitmap>()
    private val missing: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun install(app: Application, classLoader: ClassLoader) {
        xprefs.reload()
        style = xprefs.getInt(Prefs.TICK_STYLE, 0)
        if (TickStyles.assetPrefix(style) == null) {
            XposedBridge.log("$TAG: no tick style set; skipping")
            HookLog.skip("install/TickShapes", "no tick style set")
            return
        }
        moduleRes = runCatching {
            app.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY).resources
        }.getOrElse {
            XposedBridge.log("$TAG: createPackageContext($pkg) failed: $it")
            null
        } ?: return

        val res = app.resources
        val wa = app.packageName
        fun drawable(name: String): Int = runCatching { res.getIdentifier(name, "drawable", wa) }.getOrDefault(0)
        fun map(name: String, state: State, media: Boolean, optional: Boolean = false) {
            val id = drawable(name)
            if (id != 0) kinds[id] = Kind(state, media)
            else if (!optional) XposedBridge.log("$TAG: WA drawable '$name' not found; that state stays stock")
        }
        map("message_got_receipt_from_server", State.SENT, media = false)
        map("message_got_receipt_from_target", State.DELIVERED, media = false)
        map("message_got_read_receipt_from_target", State.READ, media = false, optional = true)
        map("message_unsent", State.PENDING, media = false)
        map("message_got_receipt_from_server_onmedia", State.SENT, media = true)
        map("message_got_receipt_from_target_onmedia", State.DELIVERED, media = true)
        map("message_got_read_receipt_from_target_onmedia", State.READ, media = true)
        map("message_unsent_onmedia", State.PENDING, media = true)
        if (kinds.isEmpty()) {
            XposedBridge.log("$TAG: none of WA's receipt drawables resolved; ticks stay stock")
            HookLog.skip("install/TickShapes", "no receipt drawable ids")
            return
        }
        statusId = res.waId("status", wa)
        serverReceiveId = drawable("msg_status_server_receive")
        clientId = drawable("msg_status_client")
        scheduleId = drawable("ic_schedule_small")

        hookRows(classLoader)
        hookChatList(app, classLoader)
        Deobfuscator.saveCache()
    }

    // ── Message rows ──

    /** AppCompatImageView overrides setImageResource without calling super, so both levels carry the hook. */
    private fun hookRows(classLoader: ClassLoader) {
        val onSet = object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val v = p.thisObject as? ImageView ?: return
                onResource(v, p.args.getOrNull(0) as? Int ?: return)
            }
        }
        runCatching {
            XposedHelpers.findAndHookMethod(ImageView::class.java, "setImageResource", Int::class.javaPrimitiveType, onSet)
        }.onFailure { XposedBridge.log("$TAG: ImageView.setImageResource hook FAILED: $it") }
        runCatching {
            val appCompat = Class.forName("androidx.appcompat.widget.AppCompatImageView", false, classLoader)
            XposedHelpers.findAndHookMethod(appCompat, "setImageResource", Int::class.javaPrimitiveType, onSet)
        }.onFailure { XposedBridge.log("$TAG: AppCompatImageView.setImageResource hook FAILED: $it") }
        runCatching {
            XposedHelpers.findAndHookMethod(
                ImageView::class.java, "setImageTintList", ColorStateList::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (clearing) return
                        val v = p.thisObject as? ImageView ?: return
                        onTint(v, p)
                    }
                },
            )
        }.onFailure { XposedBridge.log("$TAG: setImageTintList hook FAILED: $it") }
        HookLog.arm("TickShapes/rows", "style $style, ${kinds.size} glyph ids")
    }

    private fun onResource(v: ImageView, id: Int) {
        val kind = kinds[id] ?: return
        v.setTag(kindTag, kind)
        apply(v, kind, read = v.getTag(readTag) == true)
    }

    /** The art carries its own colours, so WhatsApp's tint is dropped; blue means read and picks the read art. */
    private fun onTint(v: ImageView, p: XC_MethodHook.MethodHookParam) {
        val kind = v.getTag(kindTag) as? Kind
        if (kind == null && (statusId == 0 || v.id != statusId)) return
        val csl = p.args.getOrNull(0) as? ColorStateList
        val read = csl != null && isTickReadColour(csl.defaultColor)
        v.setTag(readTag, read)
        // One surface tints before it sets the glyph; the flag waits there for the glyph to arrive.
        if (kind == null) return
        p.args[0] = null
        if (kind.state == State.DELIVERED) apply(v, kind, read)
    }

    private fun apply(v: ImageView, kind: Kind, read: Boolean) {
        val state = if (read && kind.state == State.DELIVERED) State.READ else kind.state
        val bmp = bitmapFor(state, kind.media) ?: return
        v.setImageDrawable(BitmapDrawable(v.resources, bmp))
        if (v.imageTintList != null) {
            clearing = true
            try { v.imageTintList = null } finally { clearing = false }
        }
        unfix(v)
        HookLog.hit("TickShapes/rows")
    }

    /** The media rows pin the status view to the stock glyph's box; a wrapping box lets larger art show whole. */
    private fun unfix(v: ImageView) {
        if (v.getTag(unfixedTag) == true) return
        v.setTag(unfixedTag, true)
        val lp = v.layoutParams ?: return
        if (lp.width <= 0 || lp.height <= 0) return
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        v.layoutParams = lp
    }

    // ── Chat list and message info ──

    /** Both surfaces build a tinted stock drawable through two static helpers; their results are replaced. */
    private fun hookChatList(app: Application, classLoader: ClassLoader) {
        if (serverReceiveId == 0 || clientId == 0) {
            HookLog.skip("TickShapes/chatList", "chat list receipt drawables unresolved")
            return
        }
        if (!Deobfuscator.ensureBridge(app)) {
            HookLog.skip("TickShapes/chatList", "DexKit init failed")
            return
        }
        val (tinted, cached) = Deobfuscator.loadChatListTickMethods(app, classLoader, serverReceiveId, clientId)
        if (tinted == null && cached == null) {
            HookLog.skip("TickShapes/chatList", "status drawable builders unresolved")
            return
        }
        tinted?.let { m ->
            hook(m) { p ->
                val ctx = p.args.getOrNull(0) as? Context ?: return@hook
                val drawableId = p.args.getOrNull(1) as? Int ?: return@hook
                val colourRes = p.args.getOrNull(2) as? Int ?: 0
                val state = when {
                    drawableId == serverReceiveId -> State.SENT
                    drawableId == clientId -> if (isReadColour(ctx, colourRes)) State.READ else State.DELIVERED
                    scheduleId != 0 && drawableId == scheduleId -> State.PENDING
                    else -> return@hook
                }
                replace(p, ctx, state)
            }
        }
        cached?.let { m ->
            hook(m) { p ->
                val ctx = p.args.getOrNull(0) as? Context ?: return@hook
                val colourRes = p.args.getOrNull(2) as? Int ?: 0
                replace(p, ctx, if (isReadColour(ctx, colourRes)) State.READ else State.DELIVERED)
            }
        }
        HookLog.arm("TickShapes/chatList", "tinted=${tinted?.name} cached=${cached?.name}")
    }

    private fun hook(m: Method, after: (XC_MethodHook.MethodHookParam) -> Unit) {
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) = after(p)
            })
        }.onFailure { XposedBridge.log("$TAG: ${m.declaringClass.name}.${m.name} hook FAILED: $it") }
    }

    private fun replace(p: XC_MethodHook.MethodHookParam, ctx: Context, state: State) {
        val bmp = bitmapFor(state, media = false) ?: return
        p.result = BitmapDrawable(ctx.resources, bmp)
        HookLog.hit("TickShapes/chatList")
    }

    /** The helpers take a colour resource, never a value; the read one resolves to WhatsApp's blue. */
    private fun isReadColour(ctx: Context, colourRes: Int): Boolean =
        colourRes != 0 && runCatching { isTickReadColour(ctx.getColor(colourRes)) }.getOrDefault(false)

    // ── Assets ──

    /** A pack without on-image art shows its plain art there; a state the pack lacks stays stock. */
    private fun bitmapFor(state: State, media: Boolean): Bitmap? {
        val name = TickStyles.assetName(style, state, media) ?: return null
        return load(name, if (media) TickStyles.MAX_MEDIA_HEIGHT_DP else TickStyles.MAX_HEIGHT_DP)
            ?: if (media) bitmapFor(state, media = false) else null
    }

    /** Decoded once at device density, scaled down to the cap when taller; a broken name logs once, never per row. */
    private fun load(name: String, maxDp: Float): Bitmap? {
        if (name in missing) return null
        bitmaps[name]?.let { return it }
        val res = moduleRes ?: return null
        val id = res.getIdentifier(name, "drawable", pkg)
        val src = if (id == 0) null else runCatching { BitmapFactory.decodeResource(res, id) }.getOrNull()
        if (src == null) {
            if (missing.add(name)) XposedBridge.log("$TAG: asset missing -> $name")
            return null
        }
        val dm = res.displayMetrics
        val cap = (maxDp * dm.density).roundToInt().coerceAtLeast(1)
        val out = if (src.height > cap) {
            val w = (src.width.toLong() * cap / src.height).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, cap, true).also { if (it !== src) src.recycle() }
        } else {
            src
        }
        out.density = dm.densityDpi
        return bitmaps.putIfAbsent(name, out) ?: out
    }
}
