// A static image behind every WhatsApp Activity, injected at index 0 of content's parent at the first resume; a
// chat with its own entry gets that image, everything else the global one. Package check stays equality: com.whatsapp.w4b would match startsWith.
package com.wathemer.app.hooks.wallpaper

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.wathemer.app.glass.WallpaperLook
import com.wathemer.app.hooks.ActivityLifecycle
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.ModulePrefs
import com.wathemer.app.hooks.WaIds
import com.wathemer.app.hooks.glass.GlassHook
import com.wathemer.app.hooks.glass.wallpaperImageTag
import com.wathemer.app.hooks.glass.wallpaperLookTag
import com.wathemer.app.hooks.waId
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class WallpaperImage private constructor(
    private val xprefs: ModulePrefs.WtPrefs,
) {

    /** Sticky true only after a fully successful inject; a failed inject must leave it false or siblings go transparent over nothing. */
    @Volatile
    private var wallpaperActive = false

    /** Log keys: every Activity create lands in the inject path, so per-screen printing is pure spam. */
    private var lastInjectLogKey = ""
    private var lastLoggedBlur = Int.MIN_VALUE

    fun isWallpaperActive(): Boolean = wallpaperActive

    private fun installInternal() {
        // Never hook Activity.onPostCreate: it is reached only by invoke-super, which ART can inline away.
        // Enabled check lives in the callback so a toggle applies on the next Activity create.
        ActivityLifecycle.onCreated("wallpaper") { activity ->
            // Equality, not startsWith: WhatsApp Business is com.whatsapp.w4b and would match startsWith.
            if (activity.packageName == WHATSAPP_PKG) {
                runCatching { injectFor(activity) }
                    .onFailure {
                        XposedBridge.log(
                            "$TAG: injectFor(${activity.javaClass.simpleName}) failed: ${it.message}"
                        )
                    }
            }
        }
        // A chat's entry can change while the chat stays open, in the settings app; re-checked per resume, before the frame.
        ActivityLifecycle.onResumed("wallpaperChat") { activity ->
            if (activity.packageName == WHATSAPP_PKG && activity.javaClass.name == ChatWallpapers.CONVERSATION) {
                runCatching { recheck(activity) }
                    .onFailure { XposedBridge.log("$TAG: recheck(${activity.javaClass.simpleName}) failed: ${it.message}") }
            }
        }
        // Register the catch-all literal-color predicate once; it survives every inject.
        WallpaperShellClearer.installLiteralColorCatchall()
        // Core transparency mechanism: intercept setBackground at the assignment chokepoint; see WallpaperShellClearer.
        val userBg = xprefs.getInt(Prefs.KEY_BACKGROUND, Prefs.DEFAULT_BACKGROUND)
        WallpaperShellClearer.installBackgroundInterceptor(userBg)
        XposedBridge.log("$TAG: armed (activity lifecycle) + catchall + bg-interceptor")
    }

    private fun injectFor(activity: Activity) {
        // A status composer's canvas colour is the post itself, so nothing here may clear or cover it.
        val cls = activity.javaClass.name
        if (NO_WALLPAPER_PREFIXES.any { cls.startsWith(it) }) return
        xprefs.reload()
        if (!xprefs.getBoolean(Prefs.KEY_WALLPAPER_ENABLED, false)) {
            return
        }
        // The chat's own entry first; the global image is the fallback here as it is everywhere else.
        if (cls == ChatWallpapers.CONVERSATION) {
            val entry = ChatWallpapers.entryFor(activity, null)
            if (entry != null) {
                val prepared = ChatWallpapers.take(activity, entry)
                if (prepared != null) {
                    injectWallpaper(activity, prepared.bitmap, WallpaperLook(entry.stamp, entry.dim, entry.blur))
                    HookLog.hit("chat/wallpaperInject", "stamp ${entry.stamp}")
                    return
                }
            }
        }
        val bitmap = globalBitmap(activity) ?: return
        injectWallpaper(activity, bitmap, globalLook())
    }

    /** Screens whose own background is content, not chrome. Prefix so every composer mode is covered. */
    private val NO_WALLPAPER_PREFIXES = listOf("com.whatsapp.status.composer.")

    private fun globalLook(): WallpaperLook = WallpaperLook(
        0,
        xprefs.getInt(Prefs.KEY_WALLPAPER_DIM, 0).coerceIn(0, 100),
        // Ceiling 150: glass frost needs heavy blur and RenderEffect is free per frame.
        xprefs.getInt(Prefs.KEY_WALLPAPER_BLUR, 0).coerceIn(0, 150),
    )

    /** The global bitmap, from the cache the warm-up filled; decoded here only when it has not landed yet. */
    private fun globalBitmap(activity: Activity): Bitmap? {
        val file = WallpaperResolver.resolve(activity, xprefs)
        if (file == null) {
            XposedBridge.log(
                "$TAG: resolve() returned null for ${activity.javaClass.simpleName}; " +
                    "path=${xprefs.getString(Prefs.KEY_WALLPAPER_PATH, "(null)")}"
            )
            return null
        }
        val injectKey = "${file.absolutePath}:${file.length()}"
        if (injectKey != lastInjectLogKey) {
            lastInjectLogKey = injectKey
            XposedBridge.log(
                "$TAG: injecting ${file.absolutePath} (${file.length()} bytes) -> ${activity.javaClass.simpleName}"
            )
        }
        val blur = xprefs.getInt(Prefs.KEY_WALLPAPER_BLUR, 0).coerceIn(0, 150)
        if (blur != lastLoggedBlur) {
            lastLoggedBlur = blur
            XposedBridge.log("$TAG: blur pref reads $blur")
        }
        val dm = activity.resources.displayMetrics
        return WallpaperCache.get(file.absolutePath, blur)
            ?: BitmapDecoder.decodeScaled(file, dm.widthPixels, dm.heightPixels)?.also {
                WallpaperCache.put(file.absolutePath, blur, it, pinned = true)
            }
            ?: run {
                // Readable but undecodable file: wallpaperActive stays false, so log it or the fallback is silent.
                XposedBridge.log(
                    "$TAG: could not decode ${file.absolutePath} (${file.length()} bytes); " +
                        "wallpaper NOT injected for this Activity"
                )
                null
            }
    }

    private fun injectWallpaper(activity: Activity, bitmap: Bitmap, look: WallpaperLook) {
        val content = activity.window.decorView.findViewById<View>(android.R.id.content) as? ViewGroup
            ?: return
        // If content.parent is not a ViewGroup the wallpaper lands inside the content frame and gets cut off at the top.
        val parentRaw = content.parent
        val parent = (parentRaw as? ViewGroup) ?: run {
            XposedBridge.log(
                "$TAG: WARN decor.content has no ViewGroup parent; wallpaper attaching INSIDE content frame"
            )
            content
        }
        // Idempotent: a second create from a config-change replay is a no-op.
        if (parent.findViewWithTag<View?>(WALLPAPER_TAG) != null) {
            wallpaperActive = true
            return
        }

        activity.window.statusBarColor = 0
        // Force the system nav bar transparent too, so the wallpaper runs edge-to-edge.
        activity.window.navigationBarColor = 0

        val imageView = ImageView(activity).apply {
            tag = WALLPAPER_TAG
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        parent.addView(imageView, 0)
        val dimView = applyLook(activity, parent, imageView, bitmap, look)

        // Glass samples the wallpaper's brightness; resolving here beats the first pane's construction.
        runCatching { GlassHook.noteWallpaperInjected(activity.window.decorView, imageView, dimView, look) }

        // No full-bleed helper on purpose: the padding is already zero and the top strip is AppCompat's status guard (GlassToolbars clears it).

        // Transparency triple: window bg, parent bg, native wallpaper view; parent bg 0 lets null-bg children show through.
        activity.window.setBackgroundDrawable(ColorDrawable(0))
        parent.setBackgroundColor(0)

        // One-shot clear of known structural shells; later-attaching containers are covered per-id in HomeActivityHook.
        WallpaperShellClearer.clearKnownShells(activity, content)

        // Posted so it runs after WA's own onPostCreate attaches its background view.
        content.post { hideNativeWallpaper(activity) }

        // Keep wallpaper and dim at indices 0/1 when WA inserts decor children later; tag-guarded against double registration.
        installParentReenforcement(parent, imageView)

        wallpaperActive = true
    }

    /** Writes what a look changes and nothing else: the image, its blur, and the dim view at index 1. Safe to repeat on a swap. */
    private fun applyLook(activity: Activity, parent: ViewGroup, imageView: ImageView, bitmap: Bitmap, look: WallpaperLook): View? {
        if ((imageView.drawable as? BitmapDrawable)?.bitmap !== bitmap) {
            imageView.setImageDrawable(BitmapDrawable(activity.resources, bitmap))
        }
        val blur = look.blur.coerceIn(0, 150)
        imageView.setRenderEffect(
            if (blur > 0) RenderEffect.createBlurEffect(blur.toFloat(), blur.toFloat(), Shader.TileMode.CLAMP) else null
        )
        val dim = look.dim.coerceIn(0, 100)
        var dimView = dimChildOf(parent)
        if (dim > 0) {
            if (dimView == null) {
                dimView = View(activity).apply {
                    tag = DIM_TAG
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    )
                    // Keep DIM_COLOR exactly 0xFF010101; the 1/255 offset off pure black may matter to the blend.
                    setBackgroundColor(DIM_COLOR)
                }
                parent.addView(dimView, 1)
            }
            // The dim rides in the colour's alpha, never the view's: a translucent view is a full-screen layer every frame.
            dimView.alpha = 1f
            dimView.setBackgroundColor((Math.round(dim * 255f / 100f) shl 24) or (DIM_COLOR and 0x00FFFFFF))
        } else if (dimView != null) {
            parent.removeView(dimView)
            dimView = null
        }
        return dimView
    }

    /** The dim view among the parent's direct children; a handful of views, never a tree walk. */
    private fun dimChildOf(parent: ViewGroup): View? {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c.tag == DIM_TAG) return c
        }
        return null
    }

    /** Every resume of a chat: if its entry changed while it was open, swap the image in place before the frame. */
    private fun recheck(activity: Activity) {
        val decor = activity.window.decorView
        val imageView = decor.getTag(wallpaperImageTag) as? ImageView ?: return
        val look = imageView.getTag(wallpaperLookTag) as? WallpaperLook ?: return
        xprefs.reload()
        if (!xprefs.getBoolean(Prefs.KEY_WALLPAPER_ENABLED, false)) return
        val entry = ChatWallpapers.entryFor(activity, null)
        val want = entry?.let { WallpaperLook(it.stamp, it.dim, it.blur) } ?: globalLook()
        // A chat entry appearing, changing or going is the case; a changed global dim or blur still waits for a restart.
        if (want.stamp == look.stamp && (want.stamp == 0 || want == look)) return
        val bitmap = if (entry != null) ChatWallpapers.prepareNow(activity, entry)?.bitmap else globalBitmap(activity)
        if (bitmap == null) return
        val parent = imageView.parent as? ViewGroup ?: return
        val dimView = applyLook(activity, parent, imageView, bitmap, want)
        runCatching { GlassHook.noteWallpaperSwapped(decor, imageView, dimView, want) }
        HookLog.hit("chat/wallpaperSwap", "stamp ${look.stamp} to ${want.stamp}")
        XposedBridge.log("$TAG: ${activity.javaClass.simpleName} swapped from ($look) to ($want)")
    }

    /** Hides WA's native wallpaper view by id, then by class walk. INVISIBLE not GONE to keep the layout slot; wt_* tags are skipped. */
    private fun hideNativeWallpaper(activity: Activity) {
        try {
            val decor = activity.window.decorView as? ViewGroup ?: return
            val id = activity.resources.waId("conversation_background", activity.packageName)
            val direct = if (id != 0) decor.findViewById<View?>(id) else null
            if (direct != null && direct.visibility != View.GONE) {
                direct.visibility = View.INVISIBLE
                direct.alpha = 0f
                return
            }
            val foundClassMatch = findAndHideWallpaperView(decor)
            if (id == 0 && !foundClassMatch) {
                // Signal a possible WA rename without spamming logcat.
                logRenameWarnOnce(activity.javaClass.simpleName)
            }
        } catch (_: Throwable) {
            // Swallow: failing to hide just stacks both layers; never crash WA over it.
        }
    }

    private fun findAndHideWallpaperView(view: View): Boolean {
        val name = view.javaClass.name
        if (name.contains("WDSWallpaper") || name.contains("WallpaperView")) {
            val tag = view.tag
            if (tag is String && tag.startsWith(TAG_PREFIX)) return false
            if (view.visibility == View.GONE) return false
            view.visibility = View.INVISIBLE
            view.alpha = 0f
            return true
        }
        if (view is ViewGroup) {
            var any = false
            for (i in 0 until view.childCount) {
                if (findAndHideWallpaperView(view.getChildAt(i))) any = true
            }
            return any
        }
        return false
    }

    /** Hierarchy listener keeps wallpaper at 0 and dim at 1 as WA adds children; tag-guarded so listeners never stack. */
    private fun installParentReenforcement(parent: ViewGroup, imageView: ImageView) {
        if (parent.getTag(REENFORCEMENT_TAG_KEY) != null) return
        parent.setTag(REENFORCEMENT_TAG_KEY, true)
        parent.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(p: View?, child: View?) {
                // Cheap re-check per added child; most calls are no-ops.
                if (parent.indexOfChild(imageView) != 0) {
                    parent.removeView(imageView)
                    parent.addView(imageView, 0)
                }
                // Resolved per event, never captured: a swap can add the dim view or take it away.
                val dimView = dimChildOf(parent)
                if (dimView != null && parent.indexOfChild(dimView) != 1) {
                    parent.removeView(dimView)
                    parent.addView(dimView, 1)
                }
            }
            override fun onChildViewRemoved(p: View?, child: View?) { /* no-op */ }
        })
    }

    /** Sets InfoCard.A00 to 0 under wallpaper; the cards draw their fill in onDraw with bg=null, so the bg interceptor cannot reach them. */
    private fun installInfoCardTransparency(classLoader: ClassLoader) {
        // Through WaIds so a miss reaches the install summary; A00 is InfoCard's only int field, so shape recovery is unambiguous.
        val cls = WaIds.clazz(
            classLoader, "com.whatsapp.ui.coreui.InfoCard", "InfoCard transparency",
        ) ?: return
        val a00 = WaIds.field(
            cls, "A00", Int::class.javaPrimitiveType, "InfoCard fill colour",
        ) ?: return
        runCatching {
            XposedHelpers.findAndHookMethod(
                cls, "onDraw", Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (!wallpaperActive) return
                        val v = p.thisObject as? View ?: return
                        val tag = v.tag
                        if (tag is String && tag.startsWith(TAG_PREFIX)) return
                        runCatching { a00.setInt(v, 0) } // alpha 0 -> WA's own guard skips the fill
                    }
                },
            )
            XposedBridge.log("$TAG: InfoCard.onDraw transparency hook installed")
        }.onFailure { XposedBridge.log("$TAG: InfoCard hook failed: ${it.message}") }
    }

    private fun logRenameWarnOnce(activityName: String) {
        if (renameWarnLogged) return
        renameWarnLogged = true
        XposedBridge.log(
            "$TAG: WARN; hideNativeWallpaper found NEITHER id/conversation_background NOR " +
                "any class matching WDSWallpaper/WallpaperView under $activityName. " +
                "WA may have renamed its native wallpaper view; user may see stacked wallpapers."
        )
    }

    @Volatile
    private var renameWarnLogged = false

    companion object {
        private const val TAG = "WaThemer.Wallpaper"
        private const val WHATSAPP_PKG = "com.whatsapp"
        private const val REENFORCEMENT_TAG_KEY = -1167196165

        /** Tag prefix on every injected view; paint features and findAndHideWallpaperView skip anything carrying it. */
        const val TAG_PREFIX = "wt_"
        const val WALLPAPER_TAG = "wt_wallpaper"
        const val DIM_TAG = "wt_wallpaper_dim"

        /** 0xFF010101. Near-black, one step off pure black on every channel, so it stays neutral. */
        const val DIM_COLOR: Int = -16711423

        /** Set during [install]; siblings read wallpaper state through it. Null when install was skipped or threw. */
        @JvmStatic
        @Volatile
        var INSTANCE: WallpaperImage? = null
            private set

        /** Sets [INSTANCE]; siblings read wallpaper state through it, so this client stays in ORDER_MAIN. */
        fun install(classLoader: ClassLoader) {
            val xprefs = ModulePrefs.open()
            val instance = WallpaperImage(xprefs)
            INSTANCE = instance
            instance.installInternal()
            instance.installInfoCardTransparency(classLoader)
        }
    }
}
