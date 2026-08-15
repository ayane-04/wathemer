// A single static image behind every WhatsApp Activity, injected at index 0 of content's parent on onPostCreate.
// The package check must stay equality, not startsWith, or WhatsApp Business (com.whatsapp.w4b) matches.
package com.wathemer.app.hooks.wallpaper

import android.app.Activity
import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.WaIds
import com.wathemer.app.hooks.glass.GlassHook
import com.wathemer.app.hooks.waId
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File

class WallpaperImage private constructor(
    private val xprefs: XSharedPreferences,
) {

    /** Sticky true only after a fully successful inject; a failed inject must leave it false or siblings go transparent over nothing. */
    @Volatile
    private var wallpaperActive = false

    /** Log keys: every Activity's onPostCreate lands in the inject path, so per-screen printing is pure spam. */
    private var lastInjectLogKey = ""
    private var lastLoggedBlur = Int.MIN_VALUE

    fun isWallpaperActive(): Boolean = wallpaperActive

    private fun installInternal() {
        // Enabled check lives in the callback so toggles apply without restart; depends on xposedsharedprefs=true in the manifest.
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onPostCreate", Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val activity = p.thisObject as? Activity ?: return
                    // Equality, not startsWith: WhatsApp Business is com.whatsapp.w4b and would match startsWith.
                    if (activity.packageName != WHATSAPP_PKG) return
                    runCatching { injectFor(activity) }
                        .onFailure {
                            XposedBridge.log(
                                "$TAG: injectFor(${activity.javaClass.simpleName}) failed: ${it.message}"
                            )
                        }
                }
            },
        )
        // Register the catch-all literal-color predicate once; it survives every inject.
        WallpaperShellClearer.installLiteralColorCatchall()
        // Core transparency mechanism: intercept setBackground at the assignment chokepoint; see WallpaperShellClearer.
        val userBg = xprefs.getInt(Prefs.KEY_BACKGROUND, Prefs.DEFAULT_BACKGROUND)
        WallpaperShellClearer.installBackgroundInterceptor(userBg)
        XposedBridge.log("$TAG: hook installed (Activity.onPostCreate) + catchall + bg-interceptor")
    }

    private fun injectFor(activity: Activity) {
        xprefs.reload()
        if (!xprefs.getBoolean(Prefs.KEY_WALLPAPER_ENABLED, false)) {
            return
        }
        val file = WallpaperResolver.resolve(activity, xprefs)
        if (file == null) {
            XposedBridge.log(
                "$TAG: resolve() returned null for ${activity.javaClass.simpleName}; " +
                    "path=${xprefs.getString(Prefs.KEY_WALLPAPER_PATH, "(null)")}"
            )
            return
        }
        val injectKey = "${file.absolutePath}:${file.length()}"
        if (injectKey != lastInjectLogKey) {
            lastInjectLogKey = injectKey
            XposedBridge.log(
                "$TAG: injecting ${file.absolutePath} (${file.length()} bytes) -> ${activity.javaClass.simpleName}"
            )
        }
        injectWallpaper(activity, file)
    }

    private fun injectWallpaper(activity: Activity, file: File) {
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
        // Idempotent: a second onPostCreate from a config-change replay is a no-op.
        if (parent.findViewWithTag<View?>(WALLPAPER_TAG) != null) {
            wallpaperActive = true
            return
        }

        activity.window.statusBarColor = 0
        // Force the system nav bar transparent too, so the wallpaper runs edge-to-edge.
        activity.window.navigationBarColor = 0

        val dm = activity.resources.displayMetrics
        // Ceiling 150: glass frost needs heavy blur and RenderEffect is free per frame.
        val blur = xprefs.getInt(Prefs.KEY_WALLPAPER_BLUR, 0).coerceIn(0, 150)

        if (blur != lastLoggedBlur) {
            lastLoggedBlur = blur
            XposedBridge.log("$TAG: blur pref reads $blur")
        }
        val cached = WallpaperCache.get(file.absolutePath, blur)
        val bitmap = cached
            ?: BitmapDecoder.decodeScaled(file, dm.widthPixels, dm.heightPixels)?.also {
                WallpaperCache.put(file.absolutePath, blur, it)
            }
            ?: run {
                // Readable but undecodable file: wallpaperActive stays false, so log it or the fallback is silent.
                XposedBridge.log(
                    "$TAG: could not decode ${file.absolutePath} (${file.length()} bytes); " +
                        "wallpaper NOT injected for this Activity"
                )
                return
            }

        val imageView = ImageView(activity).apply {
            tag = WALLPAPER_TAG
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageDrawable(BitmapDrawable(activity.resources, bitmap))
            if (blur > 0) {
                setRenderEffect(
                    RenderEffect.createBlurEffect(blur.toFloat(), blur.toFloat(), Shader.TileMode.CLAMP)
                )
            }
        }
        parent.addView(imageView, 0)

        val dim = xprefs.getInt(Prefs.KEY_WALLPAPER_DIM, 0).coerceIn(0, 100)
        val dimView = if (dim > 0) {
            View(activity).apply {
                tag = DIM_TAG
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                // Keep DIM_COLOR exactly 0xFF010101; the 1/255 offset off pure black may matter to the blend.
                setBackgroundColor(DIM_COLOR)
                alpha = dim / 100f
            }.also { parent.addView(it, 1) }
        } else null

        // Glass samples the wallpaper's brightness; resolving here beats the first pane's construction.
        runCatching { GlassHook.noteWallpaperInjected(imageView, dimView) }

        // No full-bleed helper on purpose: the padding is already zero and the top strip is AppCompat's status guard (GlassHook.clearStatusGuard).

        // Transparency triple: window bg, parent bg, native wallpaper view; parent bg 0 lets null-bg children show through.
        activity.window.setBackgroundDrawable(ColorDrawable(0))
        parent.setBackgroundColor(0)

        // One-shot clear of known structural shells; later-attaching containers are covered per-id in HomeActivityHook.
        WallpaperShellClearer.clearKnownShells(activity, content)

        // Posted so it runs after WA's own onPostCreate attaches its background view.
        content.post { hideNativeWallpaper(activity) }

        // Keep wallpaper and dim at indices 0/1 when WA inserts decor children later; tag-guarded against double registration.
        installParentReenforcement(parent, imageView, dimView)

        wallpaperActive = true
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
    private fun installParentReenforcement(
        parent: ViewGroup,
        imageView: ImageView,
        dimView: View?,
    ) {
        if (parent.getTag(REENFORCEMENT_TAG_KEY) != null) return
        parent.setTag(REENFORCEMENT_TAG_KEY, true)
        parent.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(p: View?, child: View?) {
                // Cheap re-check per added child; most calls are no-ops.
                if (parent.indexOfChild(imageView) != 0) {
                    parent.removeView(imageView)
                    parent.addView(imageView, 0)
                }
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

        /** Must run before any feature that branches on INSTANCE at install time, like HomeActivityHook's wallpaper gates. */
        fun install(classLoader: ClassLoader) {
            val xprefs = XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE).apply {
                makeWorldReadable()
                reload()
            }
            val instance = WallpaperImage(xprefs)
            INSTANCE = instance
            instance.installInternal()
            instance.installInfoCardTransparency(classLoader)
        }
    }
}
