// Per-element overrides for the WhatsApp homescreen, driven by ViewThemeDispatcher and TextColorDispatcher.
// WDS reflection, decompile-confirmed: WDSFab A03 bg + A04 icon, WDSBadge getBgPaint()/getTextPaint(), WDSIcon A02 filter.
package com.wathemer.app.hooks

import android.app.Application
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.dispatch.TextColorDispatcher
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.hooks.glass.GlassHook
import com.wathemer.app.hooks.wallpaper.WallpaperImage
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.util.findActivity
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Field

object HomeActivityHook {

    private const val TAG = "WaThemer.Home"
    private const val WA_PKG = "com.whatsapp"
    private const val BADGE_TAG_KEY = -1167196159
    private const val TOOLBAR_HOOKED_TAG_KEY = -1167196160
    // Tags the toolbar View itself, not a parent, so the watcher registration is idempotent across re-attaches.
    private const val TRANSPARENT_TOOLBAR_HOOKED_TAG_KEY = -1167196163

    // Tags follow WallpaperImage.TAG_PREFIX so other paint features and findAndHideWallpaperView skip these views.
    private const val WP_TOOLBAR_TAG = "wt_toolbar"
    private const val WP_APPBAR_TAG = "wt_appbar"

    private val xprefs: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE).apply {
            makeWorldReadable()
            reload()
        }
    }

    fun install(classLoader: ClassLoader) {
        try {
            // Defer install until Application.onCreate so we have a context for resource lookup.
            XposedHelpers.findAndHookMethod(
                Application::class.java, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        // Manifest scope is com.whatsapp only, but filter anyway.
                        if (app.packageName != WA_PKG) return
                        runCatching { installInternal(app, classLoader) }
                            .onFailure { XposedBridge.log("[$TAG] installInternal threw: $it"); XposedBridge.log(it) }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] install FAILED: $t"); XposedBridge.log(t)
        }
    }

    private fun installInternal(app: Application, classLoader: ClassLoader) {
        // Resolves ~40 WA ids, the most anywhere; startup ordering matters, see WaIds.logSummary in XposedEntry.
        XposedBridge.log("[$TAG] installing")
        xprefs.reload()
        val pkg = app.packageName
        val res = app.resources

        // Read all overrides + globals once at install.
        val chatlistBg      = xprefs.getInt(Prefs.OVR_CHATLIST_BG, 0)
        val rowName         = xprefs.getInt(Prefs.OVR_ROW_NAME, 0)
        val rowPreview      = xprefs.getInt(Prefs.OVR_ROW_PREVIEW, 0)
        val rowTimestamp    = xprefs.getInt(Prefs.OVR_ROW_TIMESTAMP, 0)

        val searchBarBg     = xprefs.getInt(Prefs.OVR_SEARCH_BAR_BG, 0)
        val searchInnerBg   = xprefs.getInt(Prefs.OVR_SEARCH_INNER_BG, 0)
        val searchIcon      = xprefs.getInt(Prefs.OVR_SEARCH_ICON, 0)
        val searchText      = xprefs.getInt(Prefs.OVR_SEARCH_TEXT, 0)

        val navbarBg        = xprefs.getInt(Prefs.OVR_NAVBAR_BG, 0)
        val navbarDivider   = xprefs.getInt(Prefs.OVR_NAVBAR_DIVIDER, 0)
        val tabActivePill   = xprefs.getInt(Prefs.OVR_TAB_ACTIVE_PILL, 0)
        val tabIcon         = xprefs.getInt(Prefs.OVR_TAB_ICON, 0)
        val tabActiveLbl    = xprefs.getInt(Prefs.OVR_TAB_ACTIVE_LABEL, 0)
        val tabInactiveLbl  = xprefs.getInt(Prefs.OVR_TAB_INACTIVE_LABEL, 0)

        val toolbarBg       = xprefs.getInt(Prefs.OVR_TOOLBAR_BG, 0)
        val toolbarIcons    = xprefs.getInt(Prefs.OVR_TOOLBAR_ICONS, 0)
        val whatsappLogo    = xprefs.getInt(Prefs.OVR_WHATSAPP_LOGO, 0)
        // The toolbar id fires for both HomeActivity and Conversation, so the lambda branches on the activity.
        val chatToolbarBg    = xprefs.getInt(Prefs.CHAT_TOOLBAR_BG, 0)
        val chatToolbarIcons = xprefs.getInt(Prefs.CHAT_TOOLBAR_ICONS, 0)

        val fabBg           = xprefs.getInt(Prefs.OVR_FAB_BG, 0)
        val fabIcon         = xprefs.getInt(Prefs.OVR_FAB_ICON, 0)
        val miniFabBg       = xprefs.getInt(Prefs.OVR_MINI_FAB_BG, 0)
        val miniFabLabel    = xprefs.getInt(Prefs.OVR_MINI_FAB_LABEL, 0)

        val unreadAccent    = xprefs.getInt(Prefs.KEY_UNREAD_ACCENT, 0)
        val unreadCountText = xprefs.getInt(Prefs.KEY_UNREAD_COUNT_TEXT, 0)

        // Wallpaper fallback gate. Precedence: user colour > wallpaper-transparent > WA default.
        val wallpaperEnabled = xprefs.getBoolean(Prefs.KEY_WALLPAPER_ENABLED, false)

        // ─── Chat list rows: specialized handler ───────────────────────
        // recolorBg's layer walk cannot reliably mutate RippleDrawable's content, so replace the whole ripple.
        if (chatlistBg != 0) installChatRowBgHook(pkg, res, chatlistBg)

        // ─── Search bar (separate from chatlist) ───────────────────────
        // search_bar_bg outer pill, search_inner_bg inner pill, search_icon WDSIcon magnifier, search_text text and hint.
        hookBg("my_search_bar", searchBarBg, pkg, res)
        hookBg("search_bar_inner_layout", searchInnerBg, pkg, res)
        if (searchIcon != 0) installWdsIconHook(classLoader, pkg, res, "search_icon", searchIcon)
        hookSearchText("search_text", searchText, pkg, res)

        // ─── Chatlist row text colours (per-id via TextColorDispatcher) ─
        hookText("conversations_row_contact_name", rowName, pkg, res)
        hookText("single_msg_tv", rowPreview, pkg, res)
        hookText("msg_from_tv", rowPreview, pkg, res)
        hookText("conversations_row_date", rowTimestamp, pkg, res)
        // Stock paints unread timestamps accent green; beside frosted badges it reads orphaned, so glass flattens all of them to WA's muted.
        if (rowTimestamp == 0 && xprefs.getBoolean(Prefs.KEY_GLASS_ENABLED, false)) {
            hookText("conversations_row_date", 0xFF8696A0.toInt(), pkg, res)
        }

        // ─── Bottom navigation ─────────────────────────────────────────
        // bg and divider are wallpaper-aware; pill, icon and label overrides paint over the transparent bg.
        hookBgWallpaperAware("bottom_nav", navbarBg, wallpaperEnabled, pkg, res)
        hookBgWallpaperAware("bottom_nav_container", navbarBg, wallpaperEnabled, pkg, res)
        hookBgWallpaperAware("bottom_nav_divider", navbarDivider, wallpaperEnabled, pkg, res)
        hookBg("navigation_bar_item_active_indicator_view", tabActivePill, pkg, res)
        hookIcon("navigation_bar_item_icon_view", tabIcon, pkg, res)
        hookText("navigation_bar_item_large_label_view", tabActiveLbl, pkg, res)
        hookText("navigation_bar_item_small_label_view", tabInactiveLbl, pkg, res)

        // iOS icon pack swaps tab glyphs; itemIconTint still colours them, so colour theming composes.
        if (xprefs.getBoolean(Prefs.KEY_IOS_ICON_PACK, false)) {
            installNavbarCustomIcons(app)
        }

        // ─── Wallpaper-aware tab/shell containers ─────────────────────
        // These shells attach after the one-shot shell-clear (lazy fragments), so per-id hooks clear them on every attach.
        hookBgWallpaperAwareById(android.R.id.content, 0, wallpaperEnabled)
        hookBgWallpaperAwareById(android.R.id.list, 0, wallpaperEnabled)
        hookBgWallpaperAware("community_fragment", 0, wallpaperEnabled, pkg, res)
        hookBgWallpaperAware("calls_recyclerView", 0, wallpaperEnabled, pkg, res)

        // MB's chat top-divider port: a hairline foreground per row, home window only; a foreground survives recycling.
        if (xprefs.getBoolean(Prefs.KEY_CHATLIST_DIVIDER, false)) {
            val dividerRowId = res.waId("contact_row_container", pkg)
            val dividerHeaderId = res.waId("header", pkg)
            if (dividerRowId != 0) {
                ViewThemeDispatcher.onId(dividerRowId) { v ->
                    if (dividerHeaderId != 0 && v.rootView?.findViewById<View>(dividerHeaderId) == null) return@onId
                    if (v.foreground !is TopDividerDrawable) {
                        val d = v.resources.displayMetrics.density
                        v.foreground = TopDividerDrawable(76f * d, maxOf(1f, 0.75f * d))
                    }
                }
            }
        }

        // ─── Toolbar (header): activity-scoped bg + parent walk + icon walker ──
        // One id covers Home, Conversation and ContactInfo; wallpaperEnabled is in the gate so the watcher arms without overrides.
        if (toolbarBg != 0 || toolbarIcons != 0 || chatToolbarBg != 0 || chatToolbarIcons != 0 || wallpaperEnabled) {
            val overflowId = res.waId("menuitem_overflow", pkg)
            val toolbarId = res.waId("toolbar", pkg)
            if (toolbarId != 0) {
                ViewThemeDispatcher.onId(toolbarId) { v ->
                    val cls = v.findActivity()?.javaClass?.name ?: return@onId
                    val (bg, icons) = when {
                        cls.contains("Conversation") && !cls.contains("HomeActivity")
                            -> chatToolbarBg to chatToolbarIcons       // chat scope
                        cls.contains("HomeActivity")
                            -> toolbarBg to toolbarIcons               // home scope
                        cls.contains("ContactInfoActivity")
                            -> toolbarBg to toolbarIcons               // legacy share with home
                        isDrilledInToolbar(cls)
                            -> 0 to toolbarIcons                       // folder and settings pages, icons only
                        else -> return@onId                            // unknown activity, leave alone
                    }
                    // Path A: user bg/icons set, so the original treatment wins.
                    if (bg != 0 || icons != 0) {
                        applyToolbarTreatment(v, bg, icons, overflowId)
                    }
                    // Path B: bg unset and wallpaper active, force transparent and walk the AppBar ancestors; icons still apply.
                    if (bg == 0 && wallpaperEnabled) {
                        installWallpaperToolbarWatcher(v, icons, overflowId)
                    }
                }
            }
            // toolbar_container is the outer wrapper (Home only). Wallpaper-aware variant.
            hookBgWallpaperAware("toolbar_container", toolbarBg, wallpaperEnabled, pkg, res)
        }
        hookIcon("toolbar_logo", whatsappLogo, pkg, res)

        // ─── FAB + Meta AI mini-fab ────────────────────────────────────
        if (fabBg != 0 || fabIcon != 0) {
            // FAB glyph uses the same brand green as the bg substitution; with only fabBg set it would vanish, so force white.
            val effectiveIcon = if (fabIcon != 0) fabIcon
                                else if (fabBg != 0) 0xFFFFFFFF.toInt()
                                else 0
            installWdsFabHook(classLoader, pkg, res, fabBg, effectiveIcon)
        }
        // iOS icon pack: FAB glyph, toolbar and action icons; existing tints still colour them.
        val iosPack = xprefs.getBoolean(Prefs.KEY_IOS_ICON_PACK, false)
        if (iosPack) installFabCustomIcon(app)
        if (iosPack) installToolbarCustomIcons(app)
        if (iosPack) installActionIcons(app)
        if (miniFabBg != 0) installExtendedMiniFabHook(pkg, res, miniFabBg)
        // No mini_fab_icon token: the sparkle tint never worked and is not wanted.
        hookText("extended_mini_fab_text", miniFabLabel, pkg, res)

        // ─── WDSBadge (chatlist + navbar unread badge) ─────────────────
        // Also armed for glass with no token set: the badges take the frost fill then.
        val glassOn = xprefs.getBoolean(Prefs.KEY_GLASS_ENABLED, false)
        if (unreadAccent != 0 || unreadCountText != 0 || glassOn) {
            installWdsBadgeHook(classLoader, pkg, res, unreadAccent, unreadCountText)
        }
    }

    /* ─────────────────────────────────────────────────────────────────── */
    /* hookBg / hookIcon / hookText: wrappers around the dispatchers       */
    /* ─────────────────────────────────────────────────────────────────── */

    private fun hookBg(name: String, color: Int, pkg: String, res: Resources) {
        if (color == 0) return
        val id = res.waId(name, pkg)
        if (id == 0) return
        ViewThemeDispatcher.onId(id) { v -> recolorBg(v, color) }
    }

    /** User colour > wallpaper-transparent > WA default; the runtime gate matters because an enabled wallpaper can fail to load. */
    private fun hookBgWallpaperAware(
        name: String,
        userColor: Int,
        wallpaperEnabled: Boolean,
        pkg: String,
        res: Resources,
    ) {
        if (userColor == 0 && !wallpaperEnabled) return
        val id = res.waId(name, pkg)
        if (id == 0) return
        hookBgWallpaperAwareById(id, userColor, wallpaperEnabled)
    }

    /** Raw-id variant of [hookBgWallpaperAware]; framework ids do not resolve via getIdentifier in WA's namespace. */
    private fun hookBgWallpaperAwareById(
        id: Int,
        userColor: Int,
        wallpaperEnabled: Boolean,
    ) {
        if (userColor == 0 && !wallpaperEnabled) return
        if (id == 0 || id == -1) return
        ViewThemeDispatcher.onId(id) { v ->
            val effective = when {
                userColor != 0 -> userColor
                WallpaperImage.INSTANCE?.isWallpaperActive() == true -> 0
                else -> return@onId
            }
            recolorBg(v, effective)
        }
    }

    private const val ICON_LISTENER_TAG = -1167196174

    /** Tints on every attach, WA repaints over one-shot tints; tag-guard only the listener registration. */
    private fun hookIcon(name: String, color: Int, pkg: String, res: Resources) {
        if (color == 0) return
        val id = res.waId(name, pkg)
        if (id == 0) return
        ViewThemeDispatcher.onId(id) { v ->
            recolorIcon(v, color)
            v.post { recolorIcon(v, color) }
            if (v.getTag(ICON_LISTENER_TAG) == null) {
                v.setTag(ICON_LISTENER_TAG, true)
                v.viewTreeObserver.addOnGlobalLayoutListener { recolorIcon(v, color) }
            }
        }
        XposedBridge.log("[$TAG] icon tint armed -> $name (id=0x${id.toString(16)}) colour=#%08X".format(color))
    }

    private fun hookText(name: String, color: Int, pkg: String, res: Resources) {
        if (color == 0) return
        val id = res.waId(name, pkg)
        if (id == 0) return
        TextColorDispatcher.mapColor(id, color)
        ViewThemeDispatcher.onId(id) { v -> (v as? TextView)?.setTextColor(color) }
    }

    /** Same as hookText but also sets the hint colour at 60% alpha, for the search field. */
    private fun hookSearchText(name: String, color: Int, pkg: String, res: Resources) {
        if (color == 0) return
        val id = res.waId(name, pkg)
        if (id == 0) return
        TextColorDispatcher.mapColor(id, color)
        val hintColor = (color and 0x00FFFFFF) or (0x99 shl 24)
        ViewThemeDispatcher.onId(id) { v ->
            (v as? TextView)?.let {
                it.setTextColor(color)
                it.setHintTextColor(hintColor)
            }
        }
    }

    /** Covers every bg drawable kind WA uses; the layer walk exists because SRC_IN over a transparent ColorDrawable stays transparent. */
    private fun recolorBg(v: View, color: Int) {
        v.backgroundTintList = ColorStateList.valueOf(color)
        val bg = v.background
        if (bg == null) { v.setBackgroundColor(color); return }
        // Mutated: rounded_corner_bg is shared with WA's search screen and the bank picker, and a write into the template recolours all three.
        if (bg is GradientDrawable) { runCatching { (bg.mutate() as? GradientDrawable)?.setColor(color) }; return }
        if (recolorRippleOrLayer(bg, color)) return
        val name = bg.javaClass.name
        // Dead on WA builds, R8 drops Material shapes; the colorFilter below does the real work. Do not read this as coverage.
        if (name.contains("MaterialShapeDrawable", ignoreCase = true) ||
            name.contains("ShapeDrawable", ignoreCase = true)) {
            runCatching {
                bg.javaClass.getMethod("setFillColor", ColorStateList::class.java)
                    .invoke(bg, ColorStateList.valueOf(color))
            }
        }
        runCatching { bg.mutate().setColorFilter(PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)) }
    }

    /** Walks LayerDrawable/RippleDrawable layers, recolouring inner Color/GradientDrawables; true if any changed. */
    private fun recolorRippleOrLayer(d: Drawable, color: Int): Boolean {
        if (d !is LayerDrawable) return false
        // LayerDrawable's mutate walks its children, so one call un-shares every layer written below.
        d.mutate()
        var recolored = false
        for (i in 0 until d.numberOfLayers) {
            val layer = d.getDrawable(i) ?: continue
            when (layer) {
                is ColorDrawable -> { layer.color = color; recolored = true }
                is GradientDrawable -> { runCatching { layer.setColor(color) }; recolored = true }
                is LayerDrawable -> if (recolorRippleOrLayer(layer, color)) recolored = true
            }
        }
        return recolored
    }

    /** Sets imageTintList too: AppCompat's tint helper clobbers colorFilter on every setImageDrawable. */
    private fun recolorIcon(v: View, color: Int) {
        val iv = v as? ImageView ?: run {
            v.backgroundTintList = ColorStateList.valueOf(color)
            v.background?.mutate()?.setColorFilter(PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN))
            return
        }
        iv.imageTintList = ColorStateList.valueOf(color)
        iv.imageTintMode = PorterDuff.Mode.SRC_IN
        iv.drawable?.mutate()?.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    /* ─────────────────────────────────────────────────────────────────── */
    /* Toolbar treatment: paint re-runs each attach, tag-guard listeners.  */
    /* ─────────────────────────────────────────────────────────────────── */

    /** Keeps the toolbar wallpaper-transparent; it attaches before the one-shot shell-clear, so a layout listener re-applies. */
    private fun installWallpaperToolbarWatcher(view: View, iconColor: Int, overflowId: Int) {
        val backBtnId = view.resources.waId("whatsapp_toolbar_home", view.context.packageName)
        val apply: () -> Unit = {
            if (WallpaperImage.INSTANCE?.isWallpaperActive() == true) {
                view.background = ColorDrawable(0)
                view.tag = WP_TOOLBAR_TAG
                var p: ViewParent? = view.parent
                var depth = 0
                while (p is View && depth < 12) {
                    val name = p.javaClass.name
                    if ("AppBar" in name || "CollapsingToolbar" in name || "ActionBar" in name) {
                        p.background = ColorDrawable(0)
                        p.tag = WP_APPBAR_TAG
                    }
                    p = p.parent
                    depth++
                }
                if (iconColor != 0) walkAndTint(view, iconColor, overflowId, extraIconId = backBtnId)
            }
        }
        apply()
        view.post { apply() }
        if (view.getTag(TRANSPARENT_TOOLBAR_HOOKED_TAG_KEY) == null) {
            view.setTag(TRANSPARENT_TOOLBAR_HOOKED_TAG_KEY, true)
            view.viewTreeObserver.addOnGlobalLayoutListener { apply() }
        }
    }

    // Icons only on these: their toolbar fill belongs to the wallpaper and the glass header. Archived stays on the chat branch by its name.
    private fun isDrilledInToolbar(cls: String): Boolean =
        cls.startsWith("com.whatsapp.settings.") ||
            cls.contains("StarredMessagesActivity") ||
            cls.contains("LinkedDevicesActivity") ||
            cls.contains("BroadcastListHomeActivity")

    /** Hairline at a row's top edge, the iOS list separator, inset past the avatar column. */
    private class TopDividerDrawable(private val leftMarginPx: Float, private val strokePx: Float) :
        Drawable() {
        private val paint = Paint().apply { color = 0x24FFFFFF }
        override fun draw(canvas: Canvas) {
            val b = bounds
            canvas.drawRect(b.left + leftMarginPx, b.top.toFloat(), b.right.toFloat(), b.top + strokePx, paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun applyToolbarTreatment(view: View, bg: Int, iconColor: Int, overflowId: Int) {
        val backBtnId = view.resources.waId("whatsapp_toolbar_home", view.context.packageName)
        // ── Always re-run (idempotent paint work) ──
        if (bg != 0) {
            view.background = ColorDrawable(bg)
            var p: ViewParent? = view.parent
            var depth = 0
            while (p is View && depth < 12) {
                val name = p.javaClass.name
                if ("AppBar" in name || "CollapsingToolbar" in name || "ActionBar" in name) {
                    p.background = ColorDrawable(bg)
                }
                p = p.parent
                depth++
            }
        }
        if (iconColor != 0) {
            walkAndTint(view, iconColor, overflowId, extraIconId = backBtnId)
            view.post { walkAndTint(view, iconColor, overflowId, extraIconId = backBtnId) }
        }
        // ── Tag-guard only the listener registration ──
        if (view.getTag(TOOLBAR_HOOKED_TAG_KEY) == null) {
            view.setTag(TOOLBAR_HOOKED_TAG_KEY, true)
            view.viewTreeObserver.addOnGlobalLayoutListener {
                if (bg != 0) view.background = ColorDrawable(bg)
                if (iconColor != 0) walkAndTint(view, iconColor, overflowId, extraIconId = backBtnId)
            }
        }
    }

    private fun walkAndTint(view: View, color: Int, overflowId: Int, extraIconId: Int = 0) {
        val tintList = ColorStateList.valueOf(color)
        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        val name = view.javaClass.simpleName
        val isActionMenuItem = name == "ActionMenuItemView" ||
            name.endsWith("ActionMenuItemView") || name.endsWith("MenuItemView")
        when {
            view is ImageButton -> {
                view.imageTintList = tintList
                view.imageTintMode = PorterDuff.Mode.SRC_IN
                view.drawable?.mutate()?.colorFilter = filter
            }
            view is ImageView && view.id != 0 && view.id != -1 &&
                (view.id == overflowId || view.id == extraIconId) -> {
                view.imageTintList = tintList
                view.imageTintMode = PorterDuff.Mode.SRC_IN
                view.drawable?.mutate()?.colorFilter = filter
            }
            view is Button || isActionMenuItem -> {
                (view as? TextView)?.let { tv ->
                    tv.setTextColor(color)
                    tv.compoundDrawableTintList = tintList
                    tv.compoundDrawableTintMode = PorterDuff.Mode.SRC_IN
                    tv.compoundDrawables.forEach { it?.mutate()?.colorFilter = filter }
                }
                view.backgroundTintList = tintList
                view.background?.mutate()?.colorFilter = filter
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                walkAndTint(view.getChildAt(i), color, overflowId, extraIconId)
            }
        }
    }

    /* ─────────────────────────────────────────────────────────────────── */
    /* WDSFab: setters override their arg with A03/A04, write field first. */
    /* ─────────────────────────────────────────────────────────────────── */

    private fun installWdsFabHook(
        classLoader: ClassLoader, pkg: String, res: Resources,
        bgColor: Int, iconColor: Int,
    ) {
        val cls = WaIds.clazz(classLoader, "com.whatsapp.ui.wds.components.fab.WDSFab", "FAB tint")
            ?: return
        // No shape fallback on purpose: A03 and A04 are both ColorStateList, so shape cannot tell them apart.
        val a03 = if (bgColor != 0) {
            WaIds.field(cls, "A03", null, "FAB background tint", expect = ColorStateList::class.java)
        } else null
        val a04 = if (iconColor != 0) {
            WaIds.field(cls, "A04", null, "FAB icon tint", expect = ColorStateList::class.java)
        } else null
        if (a03 == null && a04 == null) return
        val fabId = res.waId("fab", pkg)
        if (fabId == 0) return
        val bgTint = if (bgColor != 0) ColorStateList.valueOf(bgColor) else null
        val iconTint = if (iconColor != 0) ColorStateList.valueOf(iconColor) else null
        onFab(fabId) { v ->
            if (bgTint != null && a03 != null) {
                runCatching { a03.set(v, bgTint) }
                v.backgroundTintList = bgTint
                verifyFabTint(v.backgroundTintList, bgTint, "background")
            }
            if (iconTint != null && a04 != null && v is ImageView) {
                runCatching { a04.set(v, iconTint) }
                v.imageTintList = iconTint
                verifyFabTint(v.imageTintList, iconTint, "icon")
            }
        }
    }

    private var loggedFabTintIgnored = false

    /** Logs once if WDSFab's server-delivered A0D flag made our tint a no-op; the identity compare is deliberate. */
    private fun verifyFabTint(actual: ColorStateList?, wanted: ColorStateList, what: String) {
        if (loggedFabTintIgnored || actual === wanted) return
        loggedFabTintIgnored = true
        XposedBridge.log(
            "[$TAG] FAB $what tint was NOT kept; WhatsApp resolved its own instead. The widget's " +
                "A0D flag is probably off on this build/account, which makes both the reflective " +
                "field write and the setter no-ops. The FAB colour setting will appear to do nothing."
        )
    }

    /* ─────────────────────────────────────────────────────────────────── */
    /* id/fab never fires attach again after an activity recreation.       */
    /* ─────────────────────────────────────────────────────────────────── */
    // Drive FAB work from id/content, which re-fires after recreation; a set global primary masks regressions here.

    private val fabTreatments = ArrayList<(View) -> Unit>()
    private var fabDriversArmed = false
    private var lastFabRef: WeakReference<View>? = null
    private const val CONTENT_FAB_TAG = -1167196172

    /** Register a FAB treatment on both drivers: the fast attach, and the durable one. */
    private fun onFab(fabId: Int, treatment: (View) -> Unit) {
        fabTreatments.add(treatment)
        armFabDrivers(fabId)
    }

    private fun applyFabTreatments(v: View) {
        lastFabRef = WeakReference(v)
        for (t in fabTreatments) runCatching { t(v) }
    }

    private fun armFabDrivers(fabId: Int) {
        if (fabDriversArmed) return
        fabDriversArmed = true
        // The list is read at call time, so a treatment registered after this still runs.
        ViewThemeDispatcher.onId(fabId) { v -> applyFabTreatments(v) }
        ViewThemeDispatcher.onId(android.R.id.content) { content ->
            if (content.getTag(CONTENT_FAB_TAG) != null) return@onId
            content.setTag(CONTENT_FAB_TAG, true)
            content.viewTreeObserver.addOnGlobalLayoutListener {
                // Common case must stay one weak deref; when the FAB is gone, resolve through content, the stored view is dead.
                val known = lastFabRef?.get()
                if (known != null && known.isAttachedToWindow) return@addOnGlobalLayoutListener
                val fab = runCatching { content.findViewById<View>(fabId) }.getOrNull()
                    ?: return@addOnGlobalLayoutListener
                applyFabTreatments(fab)
                // Logged only when the durable driver steps in (a recreation), so it cannot spam and stays observable.
                XposedBridge.log("[$TAG] FAB re-treated from id/content; attach did not re-fire")
            }
        }
    }

    /* ─────────────────────────────────────────────────────────────────── */
    /* Chat row bg: direct RippleDrawable replacement.                     */
    /* ─────────────────────────────────────────────────────────────────── */

    private const val ROW_BG_LISTENER_TAG = -1167196162

    private fun installChatRowBgHook(pkg: String, res: Resources, color: Int) {
        val id = res.waId("contact_row_container", pkg)
        if (id == 0) return
        val rippleTint = ColorStateList.valueOf(0x33FFFFFF.toInt())
        ViewThemeDispatcher.onId(id) { v ->
            val apply = {
                runCatching {
                    v.background = RippleDrawable(rippleTint, ColorDrawable(color), null)
                }
            }
            apply()
            v.post { apply() }
            if (v.getTag(ROW_BG_LISTENER_TAG) == null) {
                v.setTag(ROW_BG_LISTENER_TAG, true)
                v.viewTreeObserver.addOnGlobalLayoutListener { apply() }
            }
        }
    }

    /* ─────────────────────────────────────────────────────────────────── */
    /* ExtendedMiniFab: setWdsFabStyle() repaints; re-apply every layout.  */
    /* ─────────────────────────────────────────────────────────────────── */

    private const val MINI_FAB_LISTENER_TAG = -1167196164

    private fun installExtendedMiniFabHook(pkg: String, res: Resources, color: Int) {
        val id = res.waId("extended_mini_fab", pkg)
        if (id == 0) return
        ViewThemeDispatcher.onId(id) { v ->
            val apply = {
                v.background = ColorDrawable(color)
                v.backgroundTintList = ColorStateList.valueOf(color)
            }
            // Tag-guard only the listener registration; without it every attach leaks another permanent layout listener.
            apply()
            v.post { apply() }
            if (v.getTag(MINI_FAB_LISTENER_TAG) == null) {
                v.setTag(MINI_FAB_LISTENER_TAG, true)
                v.viewTreeObserver.addOnGlobalLayoutListener { apply() }
            }
        }
    }

    /* ─────────────────────────────────────────────────────────────────── */
    /* WDSBadge: A05 is outline only; use getBgPaint()/getTextPaint().     */
    /* ─────────────────────────────────────────────────────────────────── */

    private fun installWdsBadgeHook(
        classLoader: ClassLoader, pkg: String, res: Resources,
        unreadBg: Int, unreadText: Int,
    ) {
        val cls = tryLoadWdsBadgeClass(classLoader) ?: return
        val getBgPaint = WaIds.method(cls, "getBgPaint", "unread badge background")
        val getTextPaint = WaIds.method(cls, "getTextPaint", "unread badge text")
        if (getBgPaint == null && getTextPaint == null) return

        // Tag any matching badge view with [bg, text]; onDraw hook reads it.
        val unreadId = res.waId("conversations_row_message_count", pkg)
        if (unreadId != 0) {
            ViewThemeDispatcher.onId(unreadId) { v ->
                v.setTag(BADGE_TAG_KEY, intArrayOf(unreadBg, unreadText))
                v.invalidate()
            }
        }
        // The navbar badge may be a Material BadgeDrawable, not a WDSBadge; if a WDSBadge attaches there, the same tag applies.

        // Hoisted: the untagged fallback runs on every badge draw and the tokens are fixed at install.
        val defaultBadgeTag = intArrayOf(unreadBg, unreadText)
        runCatching {
            val onDraw = cls.getDeclaredMethod("onDraw", Canvas::class.java)
            XposedBridge.hookMethod(onDraw, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val view = param.thisObject as? View ?: return
                    // Untagged badges get the global tokens; the navbar badge is not reachable by findViewById, so tint by class.
                    val tag = view.getTag(BADGE_TAG_KEY) as? IntArray ?: defaultBadgeTag
                    // A set token wins; unset badges take the glass fill, which answers 0 when glass is off.
                    val bg = tag[0].takeIf { it != 0 }
                        ?: GlassHook.badgeGlassFill()
                    val text = tag[1].takeIf { it != 0 }
                        ?: GlassHook.badgeGlassText()
                    if (bg != 0 && getBgPaint != null) {
                        runCatching { (getBgPaint.invoke(view) as? Paint)?.color = bg }
                    }
                    if (text != 0 && getTextPaint != null) {
                        runCatching { (getTextPaint.invoke(view) as? Paint)?.color = text }
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$TAG: WDSBadge.onDraw hook FAILED; unread badge tinting is OFF: $it")
        }
    }

    /* ─────────────────────────────────────────────────────────────────── */
    /* WDSIcon: A02 drives the tint, imageTintList no-ops; per-view tag.   */
    /* ─────────────────────────────────────────────────────────────────── */

    private const val ICON_FILTER_TAG_KEY = -1167196161
    private var wdsIconHookInstalled = false
    private var wdsIconFilterField: Field? = null

    private fun installWdsIconHook(
        classLoader: ClassLoader, pkg: String, res: Resources,
        idName: String, color: Int,
    ) {
        val cls = WaIds.clazz(classLoader, "com.whatsapp.ui.wds.components.icon.WDSIcon", "WDS icon tint")
            ?: return
        if (!wdsIconHookInstalled) {
            // Resolved by shape: the only PorterDuffColorFilter field; a miss is logged, not silent.
            val candidates = cls.declaredFields.filter {
                PorterDuffColorFilter::class.java.isAssignableFrom(it.type)
            }
            // Refuse rather than guess when shape stops discriminating; a wrong pick tints nothing and reports nothing.
            if (candidates.size > 1) {
                XposedBridge.log(
                    "$TAG: WDS icon tint UNRESOLVED: ${candidates.size} PorterDuffColorFilter fields " +
                        "on ${cls.name} (${candidates.map { it.name }}), so shape cannot pick one. " +
                        "Refusing to guess. Icon tinting is OFF."
                )
                return
            }
            val filterField = candidates.firstOrNull()?.apply { isAccessible = true } ?: run {
                XposedBridge.log(
                    "$TAG: WDS icon tint UNRESOLVED; no PorterDuffColorFilter field on ${cls.name}. " +
                        "Icon tinting is OFF."
                )
                return
            }
            wdsIconFilterField = filterField
            runCatching {
                val onDraw = cls.getDeclaredMethod("onDraw", Canvas::class.java)
                XposedBridge.hookMethod(onDraw, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val tinted = view.getTag(ICON_FILTER_TAG_KEY) as? PorterDuffColorFilter ?: return
                        runCatching { wdsIconFilterField?.set(view, tinted) }
                    }
                })
                wdsIconHookInstalled = true
            }.onFailure {
                XposedBridge.log("$TAG: WDSIcon.onDraw hook FAILED; WDS icon tinting is OFF: $it")
            }
        }
        val id = res.waId(idName, pkg)
        if (id == 0) return
        val filter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        ViewThemeDispatcher.onId(id) { v ->
            v.setTag(ICON_FILTER_TAG_KEY, filter)
            v.invalidate()
        }
    }

    private fun tryLoadWdsBadgeClass(classLoader: ClassLoader): Class<*>? {
        val candidates = listOf(
            "com.whatsapp.ui.wds.components.badge.WDSBadge",
            "com.whatsapp.wds.components.badge.WDSBadge",
            "com.whatsapp.wds.components.WDSBadge",
        )
        for (name in candidates) {
            val c = runCatching { classLoader.loadClass(name) }.getOrNull()
            if (c != null) return c
        }
        // Silence per candidate is fine; silence once the ladder is exhausted is the feature switching itself off.
        XposedBridge.log(
            "$TAG: WDSBadge UNRESOLVED; none of $candidates exists. Unread badge tinting is OFF."
        )
        return null
    }

    /* ─────────────────────────────────────────────────────────────────── */
    /* Nav icons: MBWA's iOS glyphs, used with permission; tint composes.  */
    /* ─────────────────────────────────────────────────────────────────── */

    private var navModuleRes: Resources? = null
    private val navIconCache = HashMap<String, Drawable?>()

    // tab key -> (unselected/outline name, selected/filled name)
    private val NAV_ICON_NAMES = mapOf(
        "chats"  to ("mb_nav_chats_ios"   to "mb_nav_chats_ios_fill"),
        "status" to ("mb_nav_updates_ios" to "mb_nav_updates_ios_fill"),
        "comm"   to ("mb_nav_comm_ios"    to "mb_nav_comm_ios_fill"),
        "calls"  to ("mb_nav_calls_ios"   to "mb_nav_calls_ios_fill"),
    )
    // Fallback ordering when a menu item's resource-entry name can't be read.
    private val NAV_INDEX_FALLBACK = listOf("chats", "status", "comm", "calls")

    private fun installNavbarCustomIcons(app: Application) {
        val modRes = navModuleRes ?: runCatching {
            app.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources
        }.getOrNull()?.also { navModuleRes = it }
        if (modRes == null) { XposedBridge.log("[$TAG] nav custom icons: module resources unavailable"); return }
        val waRes = app.resources
        val navId = waRes.waId("bottom_nav", app.packageName)
        if (navId == 0) { XposedBridge.log("[$TAG] nav custom icons: bottom_nav id not found"); return }
        ViewThemeDispatcher.onId(navId) { v ->
            val apply = { applyNavIcons(v, modRes, waRes) }
            apply()
            v.post { apply() }
        }
    }

    private fun applyNavIcons(navView: View, modRes: Resources, waRes: Resources) {
        val menu = runCatching { XposedHelpers.callMethod(navView, "getMenu") as? Menu }
            .getOrNull() ?: return
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i) ?: continue
            val entry = runCatching { waRes.getResourceEntryName(item.itemId) }.getOrNull()
            val key = navTabKey(entry, i) ?: continue
            val (normalName, selectedName) = NAV_ICON_NAMES[key] ?: continue
            val normal = navDrawable(modRes, normalName) ?: continue
            val selected = navDrawable(modRes, selectedName) ?: normal
            val sld = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_checked),
                    selected.constantState?.newDrawable()?.mutate() ?: selected)
                addState(IntArray(0),
                    normal.constantState?.newDrawable()?.mutate() ?: normal)
            }
            runCatching { item.icon = sld }
        }
    }

    private fun navTabKey(entryName: String?, index: Int): String? {
        val n = entryName?.lowercase()
        return when {
            n == null -> NAV_INDEX_FALLBACK.getOrNull(index)
            "chat" in n -> "chats"
            "call" in n -> "calls"
            "communit" in n || "group" in n -> "comm"
            "status" in n || "update" in n -> "status"
            else -> NAV_INDEX_FALLBACK.getOrNull(index)
        }
    }

    private fun navDrawable(res: Resources, name: String): Drawable? =
        navIconCache.getOrPut(name) {
            val id = res.getIdentifier(name, "drawable", BuildConfig.APPLICATION_ID)
            if (id == 0) { XposedBridge.log("[$TAG] nav icon missing: $name"); null }
            else runCatching { res.getDrawable(id, null) }.getOrNull()
        }

    /* Custom home-FAB glyph picked by content-description; the FAB icon tint composes. */
    private const val FAB_ICON_TAG = -1167196170

    private fun installFabCustomIcon(app: Application) {
        val modRes = navModuleRes ?: runCatching {
            app.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources
        }.getOrNull()?.also { navModuleRes = it }
        if (modRes == null) { XposedBridge.log("[$TAG] fab custom icon: module resources unavailable"); return }
        val fabId = app.resources.waId("fab", app.packageName)
        if (fabId == 0) { XposedBridge.log("[$TAG] fab custom icon: fab id not found"); return }
        val compose = navDrawable(modRes, "mb_ic_compose")
        val camera = navDrawable(modRes, "mb_ic_camera")
        // Via [onFab], not bare onId: id/fab does not re-attach after recreation.
        onFab(fabId) { v ->
            val apply = {
                // Same `fab` id across Chats(new chat)/Status(new status=camera)/Calls, so pick by cd.
                val cd = v.contentDescription?.toString()?.lowercase() ?: ""
                val g = if ("status" in cd) camera else compose
                if (g != null) (v as? ImageView)?.setImageDrawable(g.constantState?.newDrawable()?.mutate() ?: g)
            }
            apply()
            v.post { apply() }
            if (v.getTag(FAB_ICON_TAG) == null) {
                v.setTag(FAB_ICON_TAG, true)
                v.viewTreeObserver.addOnGlobalLayoutListener { apply() }
            }
        }
    }

    /* Toolbar icon swaps: overflow, back and camera; template glyphs, so the toolbar icon tint composes. */
    private fun installToolbarCustomIcons(app: Application) {
        val modRes = navModuleRes ?: runCatching {
            app.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources
        }.getOrNull()?.also { navModuleRes = it }
        if (modRes == null) { XposedBridge.log("[$TAG] toolbar custom icons: module resources unavailable"); return }
        val waRes = app.resources; val pkg = app.packageName
        val overflow = navDrawable(modRes, "mb_menu_horizontal")
        val videocall = navDrawable(modRes, "mb_videocall")
        val voicecall = navDrawable(modRes, "mb_call")

        // Action views with stable resource ids -> swap directly by id.
        swapToolbarIconById(waRes, pkg, "menuitem_search", navDrawable(modRes, "mb_ic_search"))
        swapToolbarIconById(waRes, pkg, "menuitem_camera", navDrawable(modRes, "mb_ic_camera"))
        swapToolbarIconById(waRes, pkg, "menuitem_payment_rupee_icon", navDrawable(modRes, "mb_rupee"))
        swapToolbarIconById(waRes, pkg, "whatsapp_toolbar_home", navDrawable(modRes, "mb_ic_back"))

        // Toolbar: overflow (setOverflowIcon) + chat call buttons (no id -> match by content-description).
        val toolbarId = waRes.waId("toolbar", pkg)
        if (toolbarId != 0) {
            ViewThemeDispatcher.onId(toolbarId) { tb ->
                val apply = {
                    if (overflow != null) runCatching {
                        XposedHelpers.callMethod(tb, "setOverflowIcon",
                            overflow.constantState?.newDrawable()?.mutate() ?: overflow)
                    }
                    swapCallButtons(tb, videocall, voicecall)
                }
                apply()
                tb.post { apply() }
                if (tb.getTag(TOOLBAR_ICON_TAG) == null) {
                    tb.setTag(TOOLBAR_ICON_TAG, true)
                    tb.viewTreeObserver.addOnGlobalLayoutListener { apply() }
                }
            }
        }
    }

    private const val TOOLBAR_ICON_TAG = -1167196171

    private fun swapToolbarIconById(waRes: Resources, pkg: String, name: String, d: Drawable?) {
        if (d == null) return
        val id = waRes.waId(name, pkg)
        if (id == 0) return
        ViewThemeDispatcher.onId(id) { v ->
            (v as? ImageView)?.setImageDrawable(d.constantState?.newDrawable()?.mutate() ?: d)
        }
    }

    /** Chat video/voice call buttons carry no resource id -> match by content-description. */
    private fun swapCallButtons(root: View, video: Drawable?, voice: Drawable?) {
        if (video == null && voice == null) return
        fun walk(x: View) {
            if (x is ImageView) {
                val cd = x.contentDescription?.toString()?.lowercase()
                when {
                    cd == null -> {}
                    "video" in cd && video != null ->
                        x.setImageDrawable(video.constantState?.newDrawable()?.mutate() ?: video)
                    ("voice" in cd || "audio" in cd) && voice != null ->
                        x.setImageDrawable(voice.constantState?.newDrawable()?.mutate() ?: voice)
                }
            }
            if (x is ViewGroup) for (i in 0 until x.childCount) walk(x.getChildAt(i))
        }
        walk(root)
    }

    /* Action icon swaps by id, from the WA decompile inventory; ids that resolve to 0 skip silently. */
    private val ACTION_ICON_GLYPHS = mapOf(
        "search_view_clear_button" to "mb_ic_clear",
        "search_close_btn" to "mb_ic_clear",
        "mute_icon" to "mb_ic_mute",
        "unified_mute_icon" to "mb_ic_mute",
        "pin_indicator" to "mb_ic_pin",
        "selection_pin_button" to "mb_ic_pin",
        "reaction_picker_btn" to "mb_ic_emoji",
        "reply_icon" to "mb_ic_reply",
        "reply_icon_button" to "mb_ic_reply",
        "forward" to "mb_ic_forward",
        "selection_delete_button" to "mb_ic_delete",
        "qr_code" to "mb_ic_qr",
        "download_button" to "mb_ic_download",
        "message_btn" to "mb_ic_message",
        "fab_second" to "mb_ic_compose",
    )

    private fun installActionIcons(app: Application) {
        val modRes = navModuleRes ?: runCatching {
            app.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY).resources
        }.getOrNull()?.also { navModuleRes = it }
        if (modRes == null) return
        val waRes = app.resources; val pkg = app.packageName
        for ((idName, glyphName) in ACTION_ICON_GLYPHS) {
            val g = navDrawable(modRes, glyphName) ?: continue
            val id = waRes.waId(idName, pkg)
            if (id == 0) continue
            ViewThemeDispatcher.onId(id) { v ->
                (v as? ImageView)?.setImageDrawable(g.constantState?.newDrawable()?.mutate() ?: g)
            }
        }
    }
}
