// What every glass surface reads: the tuning values the sliders write, the shared tint, and the
// small primitives that name, log and measure. Package-visible on purpose, private to the module.
package com.wathemer.app.hooks.glass

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.wathemer.app.glass.BackdropCapture
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.ModulePrefs
import com.wathemer.app.hooks.waId
import com.wathemer.app.settings.prefs.GlassDefaults
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

internal const val TAG = "WaThemerGlass"

// ── Tuning constants, all in dp ───────────────────────────────────────────────────
// dp not px so every density matches the tuning device; var because loadGlassPrefs() overwrites these at install.
internal var BLUR_DP = GlassDefaults.BLUR.toFloat()

internal const val DOWNSAMPLE = 4f

// One tint for every pane. Overwritten from KEY_GLASS_TINT at install; do not tune here, move the slider.
internal var TINT_ALPHA = GlassDefaults.TINT

// ── One band width for every surface, as a fraction ───────────────────────────────
// A fraction of each surface's smaller side: one fixed dp band cannot fit both a small pill and the big card.
internal var BEVEL_FRACTION = GlassDefaults.BEVEL / 100f

internal const val DEPTH_RATIO = 1f

// Must stay above BLUR_DP: the blur runs after refraction, and a wider blur smears the bend back to frost.
internal var DISPLACE_DP = GlassDefaults.DISPLACE.toFloat()

// Rim stroke, drawn on Canvas over the light pass. 0 alpha is off and costs nothing.
internal var RIM_ALPHA = GlassDefaults.RIM

internal var RIM_WIDTH_DP = GlassDefaults.RIM_WIDTH.toFloat()

// ── The chat-list card ────────────────────────────────────────────────────────────
// CARD_GAP_DP mirrors my_search_bar's own 8dp bottom margin, so the card sits symmetrically between the two.
/** Every card's corner radius; CardOutline's clip must get the same value or content corners sit proud. */
internal var CARD_RADIUS_DP = GlassDefaults.RADIUS.toFloat()

/** Corner radius for the popup menu's glass, when the popup's own shape gives none. */
internal const val PANEL_ROW_RADIUS_DP = 14f

/** The hairline between menu options. Low enough to read as a seam, not as a rule. */
internal const val POPUP_DIVIDER_ALPHA = 28

internal const val CARD_GAP_DP = 8f

// Every card, against the pill's 16dp. The one width knob.
internal const val CARD_INSET_DP = 6f

// Breathing room between a card's border and the content inside it, applied equally on all four sides. The one knob for "too cluttered".
internal const val CARD_CONTENT_PAD_DP = 8f

// ── Filter chips ──────────────────────────────────────────────────────────────────
// Drawn on the card, so tint-only: these alphas stack on the card's own tint. See FrostDrawable.small.
internal const val CHIP_ALPHA = 40           // resting

internal const val CHIP_ALPHA_SELECTED = 105 // the selected "All" chip

internal const val CHIP_RIM_ALPHA = 70

/** The reply quote's own surface inside the compose pill: lighter than the glass, not a slab. */
internal const val QUOTE_ALPHA = 30

internal const val QUOTE_RADIUS_DP = 16f

/** Selection reads as a clearer backdrop, not more colour; the hue stays WhatsApp's own selection colour. */
internal const val ROW_SELECT_ALPHA = 62

internal const val ROW_SELECT_LIFT = 26

internal const val ROW_SELECT_RIM_ALPHA = 70

internal const val ROW_SELECT_INSET_X_DP = 8f

internal const val ROW_SELECT_INSET_Y_DP = 5f

internal const val ROW_SELECT_RADIUS_DP = 18f

/** Downscale of the row's wallpaper copy; the downscale is the blur, 6 keeps shapes legible under text. */
internal const val ROW_SELECT_SHRINK = 6

/** 1.2% is ~14px across a 1164px row: inside the 28px it is already inset from the card. */
internal const val ROW_SELECT_SCALE = 1.012f

/** Measured off WhatsApp's own FAB squircle (43px corner = 15.2dp); 16 is the round number. */
internal const val FAB_RADIUS_DP = 16f

/** Brighter than the card so the buttons read; the ratio to TINT_ALPHA is what matters, not the absolute. */
internal const val FAB_ALPHA = 32

/** The large title; 41sp matches the iOS reference's letter height, 44 matches its width; the bigger figure is deliberate. */
internal const val TITLE_SP = 44f

/** Toolbar title size. */
internal const val TOOLBAR_TITLE_SP = 23f

/** Pane growth and toolbar padding work as a pair; horizontal only, vertical growth just clamps to the appbar. */
internal const val ALT_PANE_PAD_DP = 9f

/** How much smaller the round Back pill is than the trailing pane is tall. */
internal const val BACK_PILL_TRIM_PX = 4

/** A scale, not padding: padding shrinks an ImageView's drawable but not an ActionMenuItemView's compound one. */
internal const val ALT_ICON_SCALE = 0.78f

internal fun View.dp(v: Float) = v * resources.displayMetrics.density

// Guard only the listener registration, never the paint: a repaint must be idempotent and must always run.
// ── A raw hashCode() is not a legal tag key, and this cost a regression ────────────────
// Every view tag goes through tagKey: a raw hashCode key can make setTag throw. Never use a bare hashCode().
internal fun tagKey(name: String): Int = (name.hashCode() and 0x00FFFFFF) or 0x7F000000

/** Read once at install; values are baked into surfaces as built, so changes need a WhatsApp restart. */
internal fun loadGlassPrefs(): Boolean {
    var enabled = false
    runCatching {
        val p = ModulePrefs.open()
        p.reload()
        val k = Prefs
        enabled = p.getBoolean(k.KEY_GLASS_ENABLED, false)
        if (!enabled) return@runCatching
        BLUR_DP = p.getInt(k.KEY_GLASS_BLUR, GlassDefaults.BLUR).toFloat()
        TINT_ALPHA = p.getInt(k.KEY_GLASS_TINT, GlassDefaults.TINT)
        DISPLACE_DP = p.getInt(k.KEY_GLASS_DISPLACE, GlassDefaults.DISPLACE).toFloat()
        BEVEL_FRACTION = p.getInt(k.KEY_GLASS_BEVEL, GlassDefaults.BEVEL) / 100f
        CARD_RADIUS_DP = p.getInt(k.KEY_GLASS_RADIUS, GlassDefaults.RADIUS).toFloat()
        RIM_ALPHA = p.getInt(k.KEY_GLASS_RIM, GlassDefaults.RIM)
        RIM_WIDTH_DP = p.getInt(k.KEY_GLASS_RIM_WIDTH, GlassDefaults.RIM_WIDTH).toFloat()
        // Set once here; every construction site would otherwise repeat them.
        GlassParams.defaultTransGamma = p.getInt(k.KEY_GLASS_GAMMA, GlassDefaults.GAMMA) / 100f
        GlassParams.defaultRimStrokeAngle = p.getInt(k.KEY_GLASS_RIM_ANGLE, GlassDefaults.RIM_ANGLE).toFloat()
        // A set pill colour wins over the frost, the fabColored stand-down pattern.
        tokenTabPillSet = p.getInt(k.OVR_TAB_ACTIVE_PILL, 0) != 0
        navUnreadBg = p.getInt(k.KEY_UNREAD_ACCENT, 0)
        navUnreadText = p.getInt(k.KEY_UNREAD_COUNT_TEXT, 0)
    }.onFailure { XposedBridge.log("[$TAG] glass prefs unreadable: $it") }
    return enabled
}

/** Read at install: a set pill colour keeps the stock/user pill and the frost never registers. */
internal var tokenTabPillSet = false

/** The unread tokens win over the frost on the nav badge too; read at install like every glass value. */
internal var navUnreadBg = 0

internal var navUnreadText = 0

/** True once install completed; [badgeGlassFill] answers 0 before that. */
@Volatile internal var armed = false

private var selectorGuardArmed = false

/**
 * The list selector is touch feedback, not backdrop. Drawn into a pane's RenderNode its ripple arms
 * an animator against that node, and the frame's own draw then dies on "Target already set!".
 */
internal fun ensureSelectorGuard() {
    if (selectorGuardArmed) return
    selectorGuardArmed = true
    runCatching {
        XposedHelpers.findAndHookMethod(
            AbsListView::class.java, "drawSelector", Canvas::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!BackdropCapture.capturing) return
                    param.result = null
                    logOnce("list selector held back while a pane was capturing")
                }
            },
        )
        HookLog.arm("guard/listSelector")
    }.onFailure { HookLog.fail("guard/listSelector", it) }
}

internal fun logOnce(msg: String) {
    if (loggedOnce.add(msg)) XposedBridge.log("[$TAG] $msg")
}

/** One-time log lines: the re-apply sites fire repeatedly per launch and bury the geometry lines. */
private val loggedOnce = Collections.synchronizedSet(HashSet<String>())

/** The tint channel, from wallpaper luma: white over dark, black over bright; never store a finished colour. */
private var glassTintChannel = 255

private var glassTintResolved = false

/** The shared pane tint: the resolved channel at the user's alpha. */
internal val glassTintColor: Int get() = glassTint(TINT_ALPHA)

/** The same tint at a different alpha, for surfaces that must read against the glass. */
internal fun glassTint(alpha: Int): Int =
    Color.argb(alpha, glassTintChannel, glassTintChannel, glassTintChannel)

internal fun resolveGlassTint(views: List<View>) {
    if (glassTintResolved) return
    val image = views.firstOrNull() as? ImageView ?: return
    val bmp = (image.drawable as? BitmapDrawable)?.bitmap ?: return
    if (bmp.width <= 0 || bmp.height <= 0) return
    glassTintResolved = true

    var sum = 0.0
    var n = 0
    val step = 16
    for (iy in 0 until step) {
        for (ix in 0 until step) {
            val px = bmp.getPixel(
                (bmp.width - 1) * ix / (step - 1),
                (bmp.height - 1) * iy / (step - 1),
            )
            sum += 0.2126 * Color.red(px) + 0.7152 * Color.green(px) + 0.0722 * Color.blue(px)
            n++
        }
    }
    if (n == 0) return
    val dim = (views.getOrNull(1)?.alpha ?: 0f).coerceIn(0f, 1f)
    val luma = (sum / n) * (1f - dim)

    // Crossfade the channel, not the alpha: 110..150 luma slides through grey instead of snapping.
    val t = ((luma - 110.0) / 40.0).coerceIn(0.0, 1.0)
    val ch = ((1.0 - t) * 255.0).toInt().coerceIn(0, 255)
    glassTintChannel = ch
    XposedBridge.log(
        "[$TAG] wallpaper luma=${luma.toInt()} (dim=$dim) -> tint channel $ch @ $TINT_ALPHA",
    )
}

internal val doneTag = tagKey("wathemer-glass-done")

internal val titleTag = tagKey("wathemer-glass-title")

internal val innerGlassTag = tagKey("wathemer-glass-inner")

internal val frostTag = tagKey("wathemer-frost")

internal var contentRef: WeakReference<ViewGroup>? = null

internal var headerRef: WeakReference<View>? = null

/** The `header` id, kept so a live header can be resolved rather than remembered. */
internal var homeHeaderId = 0

/** header's id, kept so a coordinator can ask whether it is the HOME one. Archived reuses the same layout. */
internal var headerIdPin = 0

/** The `toolbar` id, kept so the live one can be resolved rather than remembered. */
internal var homeToolbarId = 0

/** action_bar_root's id, pinned because the per-layout header checks would otherwise call getIdentifier. */
internal var actionBarRootId = 0

internal var toolbarRef: WeakReference<View>? = null

internal var navBarRef: WeakReference<View>? = null

internal var pagerHolderRef: WeakReference<View>? = null

internal var listRef: WeakReference<View>? = null

/** bottom_nav_container: the nav pane's anchor, not its parent (a LinearLayout would displace the pane). */
internal var navHostRef: WeakReference<View>? = null

internal var navGlassRef: WeakReference<View>? = null

/** bottom_nav_container's id, kept so a card can ask whether the floating nav is in ITS window. */
internal var navContainerIdPin = 0

@Volatile internal var bottomInset = 0

/** [side, navHeight, lift], filled once the nav has been floated. */
@Volatile internal var navGeom: IntArray? = null

/** Backgrounds we insist on, keyed by INSTANCE: an id key fired mid-construction and crashed mutate(); the every-View hook must stay cheap. */
internal val forcedBg = WeakHashMap<View, Drawable>()

/** Human-readable names for [forcedBg]'s keys, for the log only. */
internal val forcedBgLabels = WeakHashMap<View, String>()

// forceBg keeps re-asserting its drawable; never pair this with a background you set yourself, it will null yours too.
/** A TRANSPARENT ColorDrawable, never null: Material reads its background back, and mutate() on null crashes WhatsApp. */
internal fun clearBg(v: View, what: String) = forceBg(v, ColorDrawable(Color.TRANSPARENT), what)

internal fun forceBg(v: View, d: Drawable, what: String) {
    synchronized(forcedBgLabels) { forcedBgLabels[v] = what }
    val existing = synchronized(forcedBg) { forcedBg[v] }
    if (existing === d && v.background === d) return
    synchronized(forcedBg) { forcedBg[v] = d }
    v.background = d
    logOnce("background forced on $what")
}

/** Round by outline clip, never by swapping the background, which discards the user's tint and fights recolorBg. */
internal fun roundView(v: View, what: String) {
    v.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
        }
    }
    v.clipToOutline = true
    v.invalidateOutline()
    XposedBridge.log("[$TAG] outline-clipped $what to a pill")
}

/** True for containers that stack children: FrameLayout, or CoordinatorLayout matched by name to avoid the androidx dependency. */
internal fun canStack(vg: ViewGroup): Boolean =
    vg is FrameLayout || vg.javaClass.name.contains("CoordinatorLayout")

/** The Activity a view ultimately belongs to, through however many ContextWrappers. */
internal fun activityOf(v: View): Activity? {
    var c: Context? = v.context
    var hops = 0
    while (c != null && hops < 8) {
        if (c is Activity) return c
        c = (c as? ContextWrapper)?.baseContext
        hops++
    }
    return null
}

/** True when [id] names an ancestor within [depth] hops; scopes generic ids to one host. */
internal fun insideId(v: View, id: Int, depth: Int): Boolean {
    var p: View? = v.parent as? View
    var hops = 0
    while (p != null && hops < depth) {
        if (p.id == id) return true
        p = p.parent as? View
        hops++
    }
    return false
}

internal fun isAncestorOf(maybeAncestor: View, v: View): Boolean {
    var p: View? = v
    var hops = 0
    while (p != null && hops < 24) {
        if (p === maybeAncestor) return true
        p = p.parent as? View
        hops++
    }
    return false
}

/** Is [maybeChild] anywhere beneath [parent]? Walks up, so it costs depth, not subtree size. */
internal fun containsView(parent: View, maybeChild: View): Boolean {
    var p = maybeChild.parent
    while (p is View) {
        if (p === parent) return true
        p = p.parent
    }
    return false
}

/** Corner radius of the background about to be replaced; must be called before that background is cleared. */
internal fun readCornerRadius(v: View): Float? {
    val d = v.background ?: return null
    (d as? GradientDrawable)?.let {
        val r = runCatching { it.cornerRadius }.getOrNull()
        if (r != null && r > 0f) return r
    }
    // Always null on current WhatsApp builds (R8 strips getCornerSize); do not start here when a radius is wrong.
    return runCatching {
        val model = XposedHelpers.callMethod(d, "getShapeAppearanceModel")
        val corner = XposedHelpers.callMethod(model, "getTopLeftCornerSize")
        val box = RectF(0f, 0f, v.width.toFloat(), v.height.toFloat())
        (XposedHelpers.callMethod(corner, "getCornerSize", box) as? Float)?.takeIf { it > 0f }
    }.getOrNull()
}

// ── Cross-fading the home content ──────────────────────────────────────────────────────
// pager_holder is hidden, never detached, so it can be faded; the ~2 janky frames are the compositing itself, not the buffer or duration.
internal const val SEARCH_FADE_MS = 200L

/** Material's "emphasized decelerate": fast departure, long settle. Reads as weight. */
internal val searchInterp: Interpolator by lazy {
    PathInterpolator(0.2f, 0f, 0f, 1f)
}

/** Our injected wallpaper views, found by tag and re-resolved per pane; a recreation replaces them. */
internal fun wallpaperUnderlay(anyView: View): List<View> = runCatching {
    val root = anyView.rootView
    // Image first, then the dim, the order WallpaperImage adds them; literal tags avoid loading its class.
    listOfNotNull(
        root.findViewWithTag<View>("wt_wallpaper"),
        root.findViewWithTag<View>("wt_wallpaper_dim"),
    ).also { resolveGlassTint(it) }
}.getOrDefault(emptyList())

/** Wallpaper views from the activity, not the caller's root: a sheet's root is its dialog DecorView. */
internal fun wallpaperUnderlayGlobal(): List<View> =
    contentRef?.get()?.let { wallpaperUnderlay(it) } ?: emptyList()

/** "Chats", localised, read off the bottom nav's first item. */
internal fun navLabel(): CharSequence? = navLabel(0)

/** Nav item [index]'s localised label; string resource names are stripped, and findViewById stops at the first match. */
internal fun navLabel(index: Int): CharSequence? = runCatching {
    val nav = navBarRef?.get() ?: return null
    val id = nav.resources.waId("navigation_bar_item_small_label_view", nav.context.packageName)
    if (id == 0) return null
    val found = ArrayList<CharSequence>()
    fun walk(v: View) {
        if (v.id == id && v is TextView && v.text.isNotBlank()) found.add(v.text)
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i) ?: continue)
    }
    walk(nav)
    found.getOrNull(index)
}.getOrNull()

internal fun primaryTextColor(ctx: Context): Int {
    val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
    val c = a.getColor(0, Color.WHITE)
    a.recycle()
    return c
}

/** The tints add where band and pill stack, so they split TINT_ALPHA: the sum must equal it exactly, including at 0. */
internal fun convBandAlpha(): Int = (TINT_ALPHA / 2).coerceAtLeast(0)

/** Inset of the conversation pills from the screen edges. */
internal const val CONV_PILL_INSET_DP = 6f

/** How far a header pane sits in from its bar; the action pane and the folder capsule both use it. */
internal const val BAR_PANE_TRIM_DP = 4f

/** A user colour on either floating button stands glassFab down: a themeable surface belongs to the theme. */
internal var fabColored = false

internal var miniFabColored = false

/** Same stand-down as the FAB colours: glass must not stack under a colour the theme owns. */
internal var sendColored = false

/** White icons are only the fallback for unthemed-on-glass; a user's own icon colour must always win. */
internal var toolbarIconsColored = false

/** Has the user given the quote its own colour? Then [QuoteAndLabelColors] owns it, not us. */
internal var quoteColored = false

internal var sendIconColored = false
