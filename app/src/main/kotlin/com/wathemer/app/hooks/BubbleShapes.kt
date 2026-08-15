// Per-side nine-patch bubble substitution, hooked at DynamicBubbleProvider's leaf builders.
// Hooking the factory on direction alone collapses its 16 drawable slots, so grouped rows get tails.
package com.wathemer.app.hooks

import android.app.Application
import android.content.Context
import android.content.res.Resources
import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.settings.prefs.BubbleStyles
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

object BubbleShapes {

    private const val TAG = "WaThemer.BubbleShapes"

    // Fallback tint when no per-side colour is set: masks must be tinted to be visible. Shared via BubbleStyles.
    private val STOCK_DARK_INCOMING = BubbleStyles.STOCK_DARK_INCOMING
    private val STOCK_DARK_OUTGOING = BubbleStyles.STOCK_DARK_OUTGOING

    private val xprefs: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE).apply {
            makeWorldReadable(); reload()
        }
    }

    private var incomingStyle = 0   // 0 = off (stock); else nine-patch index = style - 1
    private var outgoingStyle = 0
    private var incomingTint = 0
    private var outgoingTint = 0

    private var moduleRes: Resources? = null
    private val pkg = BuildConfig.APPLICATION_ID

    // Concurrent, not HashMap: pool threads rasterise bubbles in parallel and a racing HashMap can corrupt.
    // CHM forbids null values, so missingAssets records failures separately (log once, never retry).
    private val baseCache = ConcurrentHashMap<String, Drawable>()
    private val missingAssets: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val colourArtCache = ConcurrentHashMap<String, Boolean>()
    private val padIncoming = Rect()
    private val padOutgoing = Rect()

    /** Finished colour artwork must not be tinted. Both tests are needed; keep in step with BubbleStyles' grouping. */
    private fun isColourArtwork(name: String): Boolean = colourArtCache.getOrPut(name) {
        val res = moduleRes ?: return@getOrPut false
        val id = res.getIdentifier(name, "drawable", pkg)
        if (id == 0) return@getOrPut false
        val bmp = runCatching {
            BitmapFactory.decodeResource(res, id, BitmapFactory.Options().apply { inSampleSize = 4 })
        }.getOrNull() ?: return@getOrPut false
        var opaque = 0
        var saturated = 0
        var sr = 0L; var sg = 0L; var sb = 0L
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val p = bmp.getPixel(x, y)
                if ((p ushr 24) < 128) continue
                opaque++
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                sr += r; sg += g; sb += b
                if (maxOf(r, g, b) - minOf(r, g, b) > 40) saturated++
            }
        }
        bmp.recycle()
        if (opaque == 0) return@getOrPut false
        val satPct = saturated * 100 / opaque
        val mr = (sr / opaque).toInt(); val mg = (sg / opaque).toInt(); val mb = (sb / opaque).toInt()
        val meanSpread = maxOf(mr, mg, mb) - minOf(mr, mg, mb)
        // Strong colour, or a deliberate flat hue. Both tests are needed; see the kdoc above.
        val verdict = satPct > 5 || meanSpread > 12
        XposedBridge.log("$TAG: $name -> ${if (verdict) "COLOUR ARTWORK (no tint)" else "mask (tintable)"}" +
            " [sat=$satPct% meanSpread=$meanSpread]")
        verdict
    }

    /** Apply the per-side tint, unless the asset is finished colour artwork. */
    private fun tintUnlessColourArt(shaped: Drawable, dir: Int, name: String) {
        if (isColourArtwork(name)) return
        shaped.colorFilter = PorterDuffColorFilter(tintFor(dir), PorterDuff.Mode.SRC_IN)
    }

    /** `<prefix>_balloon_<dir>_normal[_ext]`, prefix resolved via the shared registry. */
    private fun assetName(styleValue: Int, dir: Int, ext: Boolean): String? {
        val prefix = BubbleStyles.assetPrefix(styleValue) ?: return null
        return "${prefix}_balloon_${if (dir == 3) "outgoing" else "incoming"}_normal${if (ext) "_ext" else ""}"
    }

    fun install(app: Application, classLoader: ClassLoader) {
        xprefs.reload()
        // Stand down under glass: nothing arbitrates factory vs leaf hooks, and insets would pad a shape never drawn.
        if (xprefs.getBoolean(Prefs.KEY_GLASS_ENABLED, false)) {
            XposedBridge.log("$TAG: Liquid Glass owns the bubbles; shapes standing down")
            return
        }
        incomingStyle = xprefs.getInt(Prefs.BUBBLE_STYLE_INCOMING, 0)
        outgoingStyle = xprefs.getInt(Prefs.BUBBLE_STYLE_OUTGOING, 0)
        if (incomingStyle == 0 && outgoingStyle == 0) {
            XposedBridge.log("$TAG: no bubble shape set; skipping")
            return
        }
        val leftBg = xprefs.getInt(Prefs.BUBBLE_LEFT_BG, 0)
        val rightBg = xprefs.getInt(Prefs.BUBBLE_RIGHT_BG, 0)
        incomingTint = if (leftBg != 0) leftBg else STOCK_DARK_INCOMING
        outgoingTint = if (rightBg != 0) rightBg else STOCK_DARK_OUTGOING

        // Load our own module resources inside WhatsApp's process.
        moduleRes = runCatching {
            app.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY).resources
        }.getOrElse {
            XposedBridge.log("$TAG: createPackageContext($pkg) failed: $it")
            null
        }
        if (moduleRes == null) return

        if (!Deobfuscator.ensureBridge(app)) {
            XposedBridge.log("$TAG: DexKit init failed; shapes off")
            return
        }

        // Pre-warm so the inset hook has padding before the first bubble and pool threads never decode mid-build.
        if (incomingStyle != 0) prewarm(incomingStyle, dir = 2)
        if (outgoingStyle != 0) prewarm(outgoingStyle, dir = 3)

        val (tailed, extBuilder) = Deobfuscator.loadBubbleLeafBuilders(app, classLoader)
        if (tailed != null && extBuilder != null) {
            // dir is arg 2 on the tailed builder, arg 3 on ext (extra tag argument ahead of it).
            hookLeafBuilder(tailed, dirArgIndex = 2, ext = false)
            hookLeafBuilder(extBuilder, dirArgIndex = 3, ext = true)
            XposedBridge.log(
                "$TAG: leaf hooks armed; tailed=${tailed.name} ext=${extBuilder.name} " +
                    "(in=$incomingStyle out=$outgoingStyle)",
            )
        } else {
            installFactoryFallback(classLoader)
        }

        installInsetOverride(classLoader)
        Deobfuscator.saveCache()
    }

    /** Populate both caches for one side's tailed and tail-less variants. See the call site. */
    private fun prewarm(styleValue: Int, dir: Int) {
        for (ext in booleanArrayOf(false, true)) {
            loadBase(styleValue, dir, ext)
            assetName(styleValue, dir, ext)?.let { isColourArtwork(it) }
        }
    }

    /** Replace one leaf builder's nine-patch with ours; centered/system stays stock. */
    private fun hookLeafBuilder(method: Method, dirArgIndex: Int, ext: Boolean) {
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val dir = p.args.getOrNull(dirArgIndex) as? Int ?: return
                val style = styleFor(dir)
                if (style == 0) return
                val shaped = loadBase(style, dir, ext)
                    ?.constantState?.newDrawable()?.mutate() ?: return
                assetName(style, dir, ext)?.let { tintUnlessColourArt(shaped, dir, it) }
                p.result = shaped
            }
        })
    }

    /** Fallback when leaf builders fail to resolve: factory keyed on direction, so grouped messages keep a tail. */
    private fun installFactoryFallback(classLoader: ClassLoader) {
        val factory = Deobfuscator.loadBubbleDrawableMethod(classLoader)
        if (factory == null) {
            XposedBridge.log("$TAG: leaf builders AND factory both unresolved; shapes off")
            return
        }
        XposedBridge.hookMethod(factory, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val dir = p.args.getOrNull(0) as? Int ?: return
                val style = styleFor(dir)
                if (style == 0) return
                val shaped = loadBase(style, dir, ext = false)
                    ?.constantState?.newDrawable()?.mutate() ?: return
                assetName(style, dir, ext = false)?.let { tintUnlessColourArt(shaped, dir, it) }
                p.result = shaped
            }
        })
        XposedBridge.log(
            "$TAG: FALLBACK factory hook armed on ${factory.name}; grouped bubbles will keep a tail",
        )
    }

    /** Text-inset override. Known defect kept on purpose; any fix must derive from WA's Rect or ~20 working styles regress. */
    private fun installInsetOverride(classLoader: ClassLoader) {
        val inset = Deobfuscator.loadBalloonInsetMethod(classLoader)
        if (inset == null) {
            XposedBridge.log("$TAG: inset method unresolved; text padding stays stock")
            return
        }
        XposedBridge.hookMethod(inset, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val dir = p.args.getOrNull(0) as? Int ?: return
                if (styleFor(dir) == 0) return
                val pad = if (dir == 3) padOutgoing else padIncoming
                if (pad.left == 0 && pad.top == 0 && pad.right == 0 && pad.bottom == 0) return
                logInsetOnce(p, dir)
                p.result = Rect(pad)
            }
        })
        XposedBridge.log("$TAG: inset hook armed on ${inset.name}")
    }

    /** One slot per (direction, z) pair, so each combination logs once. See [logInsetOnce]. */
    private val insetLogged = BooleanArray(8)

    /** Diagnostic only. Runs per row, so the guard must stay allocation-free; a raced duplicate line is harmless. */
    private fun logInsetOnce(p: XC_MethodHook.MethodHookParam, dir: Int) {
        val z = p.args.getOrNull(1) as? Boolean ?: false
        val slot = (dir and 3) * 2 + if (z) 1 else 0
        if (insetLogged[slot]) return
        insetLogged[slot] = true
        val wa = (p.result as? Rect)?.toShortString() ?: "null"
        val ours = (if (dir == 3) padOutgoing else padIncoming).toShortString()
        val style = styleFor(dir)
        val asset = BubbleStyles.assetPrefix(style) ?: "?"
        XposedBridge.log(
            "$TAG: INSET dir=$dir z=$z style=$style($asset)  WA=$wa  ours=$ours"
        )
    }

    /** Decoding lives in [BubbleSide] so BubbleColors cannot drift from it. */
    private fun styleFor(dir: Int): Int = when (BubbleSide.of(dir)) {
        BubbleSide.OUTGOING -> outgoingStyle
        BubbleSide.INCOMING -> incomingStyle
        BubbleSide.CENTERED -> 0   // leave WA's stock bubble: a tail belongs to neither party
    }

    private fun tintFor(dir: Int): Int = if (dir == 3) outgoingTint else incomingTint

    /** Cached shared bases: callers must newDrawable().mutate() before a filter. Padding sampled from non-ext only. */
    private fun loadBase(styleValue: Int, dir: Int, ext: Boolean): Drawable? {
        val res = moduleRes ?: return null
        val name = assetName(styleValue, dir, ext) ?: run {
            // A removed style leaves a stale index; the once-only set keeps it from logging per row.
            val key = "style#$styleValue"
            if (missingAssets.add(key)) {
                XposedBridge.log(
                    "$TAG: stored bubble style $styleValue has no asset (registry holds " +
                        "${BubbleStyles.ALL.size}); that side stays stock"
                )
            }
            return null
        }
        if (name in missingAssets) return null
        val base = baseCache[name] ?: run {
            val id = res.getIdentifier(name, "drawable", pkg)
            val loaded = if (id == 0) {
                XposedBridge.log("$TAG: asset missing -> $name")
                null
            } else {
                runCatching { res.getDrawable(id, null) }.getOrElse {
                    XposedBridge.log("$TAG: getDrawable($name) failed: $it"); null
                }
            }
            if (loaded == null) {
                // Remember the failure so a broken name is logged once, not once per row.
                missingAssets.add(name)
                return null
            }
            baseCache.putIfAbsent(name, loaded) ?: loaded
        }
        if (!ext) base.getPadding(if (dir == 3) padOutgoing else padIncoming)
        return base
    }
}
