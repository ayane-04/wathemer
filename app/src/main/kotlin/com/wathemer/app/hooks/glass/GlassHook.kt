// Placement of the Liquid Glass surfaces inside WhatsApp: the engine draws glass, this file decides where.
// GlassView needs a stacking (FrameLayout) host; FrostDrawable works on any view. Draw order is child index.
package com.wathemer.app.hooks.glass

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextPaint
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewOverlay
import android.view.ViewStub
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.wathemer.app.BuildConfig
import com.wathemer.app.glass.FrostDrawable
import com.wathemer.app.glass.GlassBubbleDrawable
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.GlassView
import com.wathemer.app.glass.RectList
import com.wathemer.app.hooks.WaIds
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.hooks.dispatch.ForegroundKillDispatcher
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.hooks.waId
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

object GlassHook {

    private const val TAG = "WaThemerGlass"

    // ── Tuning constants, all in dp ───────────────────────────────────────────────────
    // dp not px so every density matches the tuning device; var because loadGlassPrefs() overwrites these at install.
    private var BLUR_DP = 12f
    private const val DOWNSAMPLE = 4f

    // One tint for every pane. Overwritten from KEY_GLASS_TINT at install; do not tune here, move the slider.
    private var TINT_ALPHA = 10
    // ── One band width for every surface, as a fraction ───────────────────────────────
    // A fraction of each surface's smaller side: one fixed dp band cannot fit both a small pill and the big card.
    private var BEVEL_FRACTION = 0.25f
    private const val DEPTH_RATIO = 1f

    // Must stay above BLUR_DP: the blur runs after refraction, and a wider blur smears the bend back to frost.
    private var DISPLACE_DP = 20f

    // Rim stroke, drawn on Canvas over the light pass. 0 alpha is off and costs nothing.
    private var RIM_ALPHA = 10
    private var RIM_WIDTH_DP = 2f

    // ── The chat-list card ────────────────────────────────────────────────────────────
    // CARD_GAP_DP mirrors my_search_bar's own 8dp bottom margin, so the card sits symmetrically between the two.
    /** Every card's corner radius; CardOutline's clip must get the same value or content corners sit proud. */
    private var CARD_RADIUS_DP = 20f

    /** Corner radius for the popup menu's glass, when the popup's own shape gives none. */
    private const val PANEL_ROW_RADIUS_DP = 14f

    /** The hairline between menu options. Low enough to read as a seam, not as a rule. */
    private const val POPUP_DIVIDER_ALPHA = 28

    private const val CARD_GAP_DP = 8f

    // Every card, against the pill's 16dp. The one width knob.
    private const val CARD_INSET_DP = 6f

    // Breathing room between a card's border and the content inside it, applied equally on all four sides. The one knob for "too cluttered".
    private const val CARD_CONTENT_PAD_DP = 8f

    // ── Filter chips ──────────────────────────────────────────────────────────────────
    // Drawn on the card, so tint-only: these alphas stack on the card's own tint. See FrostDrawable.small.
    private const val CHIP_ALPHA = 40           // resting
    private const val CHIP_ALPHA_SELECTED = 105 // the selected "All" chip
    private const val CHIP_RIM_ALPHA = 70

    /** The reply quote's own surface inside the compose pill: lighter than the glass, not a slab. */
    private const val QUOTE_ALPHA = 30
    private const val QUOTE_RADIUS_DP = 16f

    /** Selection reads as a clearer backdrop, not more colour; the hue stays WhatsApp's own selection colour. */
    private const val ROW_SELECT_ALPHA = 62
    private const val ROW_SELECT_LIFT = 26
    private const val ROW_SELECT_RIM_ALPHA = 70
    private const val ROW_SELECT_INSET_X_DP = 8f
    private const val ROW_SELECT_INSET_Y_DP = 5f
    private const val ROW_SELECT_RADIUS_DP = 18f

    /** Downscale of the row's wallpaper copy; the downscale is the blur, 6 keeps shapes legible under text. */
    private const val ROW_SELECT_SHRINK = 6

    /** 1.2% is ~14px across a 1164px row: inside the 28px it is already inset from the card. */
    private const val ROW_SELECT_SCALE = 1.012f

    /** Measured off WhatsApp's own FAB squircle (43px corner = 15.2dp); 16 is the round number. */
    private const val FAB_RADIUS_DP = 16f

    /** Brighter than the card so the buttons read; the ratio to TINT_ALPHA is what matters, not the absolute. */
    private const val FAB_ALPHA = 32

    /** The large title; 41sp matches the iOS reference's letter height, 44 matches its width; the bigger figure is deliberate. */
    private const val TITLE_SP = 44f

    /** Toolbar title size. */
    private const val TOOLBAR_TITLE_SP = 23f

    /** Pane growth and toolbar padding work as a pair; horizontal only, vertical growth just clamps to the appbar. */
    private const val ALT_PANE_PAD_DP = 9f

    /** How much smaller the round Back pill is than the trailing pane is tall. */
    private const val BACK_PILL_TRIM_PX = 4

    /** A scale, not padding: padding shrinks an ImageView's drawable but not an ActionMenuItemView's compound one. */
    private const val ALT_ICON_SCALE = 0.78f

    private fun View.dp(v: Float) = v * resources.displayMetrics.density

    private val doneTag = tagKey("wathemer-glass-done")

    private var headerRef: WeakReference<View>? = null

    /** The `header` id, kept so a live header can be resolved rather than remembered. */
    private var homeHeaderId = 0
    private var searchBarRef: WeakReference<View>? = null
    private var listRef: WeakReference<View>? = null
    private var contentRef: WeakReference<ViewGroup>? = null
    private var pagerHolderRef: WeakReference<View>? = null
    private var navGlassRef: WeakReference<View>? = null

    /** bottom_nav_container: the nav pane's anchor, not its parent (a LinearLayout would displace the pane). */
    private var navHostRef: WeakReference<View>? = null
    private var actionsGlassRef: WeakReference<View>? = null
    private var listPanelRef: WeakReference<View>? = null

    /** The card's host: conversations_coordinator_layout, inside the pager so the card pages; not id/content. */
    private var cardHostRef: WeakReference<ViewGroup>? = null

    // ── The updates page ──────────────────────────────────────────────────────────────
    // Its own refs: the page's shape differs from Chats, and the host measures as a FrameLayout in both tab states.
    private var updatesHostRef: WeakReference<ViewGroup>? = null
    private var updatesListRef: WeakReference<View>? = null
    private var updatesPanelRef: WeakReference<View>? = null
    private var updatesTitleRef: WeakReference<View>? = null
    private val updatesTitleTag = tagKey("wathemer-updates-title")
    private var navBarRef: WeakReference<View>? = null
    private var toolbarRef: WeakReference<View>? = null

    /** The `toolbar` id, kept so the live one can be resolved rather than remembered. */
    private var homeToolbarId = 0
    private val titleTag = tagKey("wathemer-glass-title")
    private val innerGlassTag = tagKey("wathemer-glass-inner")
    private val frostTag = tagKey("wathemer-frost")

    // Guard only the listener registration, never the paint: a repaint must be idempotent and must always run.
    // ── A raw hashCode() is not a legal tag key, and this cost a regression ────────────────
    // Every view tag goes through tagKey: a raw hashCode key can make setTag throw. Never use a bare hashCode().
    private fun tagKey(name: String): Int = (name.hashCode() and 0x00FFFFFF) or 0x7F000000

    private val frostHostListenerTag = tagKey("wathemer-frost-host-listener")
    private val navRoundListenerTag = tagKey("wathemer-nav-round-listener")
    private val tilePillTag = tagKey("wathemer-tile-pill")

    /** Which toolbar an action icon belongs to. See the registration and [syncActionsGlass]. */
    private val actionGroupTag = tagKey("wathemer-action-group")
    private const val GROUP_NORMAL = 0
    private const val GROUP_SELECTION = 1
    private const val GROUP_BOTH = 2

    private val chipAnchors = mutableListOf<WeakReference<View>>()

    // ── Pane lifetime: a pane must not outlive the thing it decorates ──────────────────────
    // Any pane that is not a child of the view it decorates must be bindPane'd to it, or it orphans on fragment swaps.
    // One writer only: nothing but syncPaneVisibility may set a bound pane's visibility; ask paneShouldShow instead.
    private class PaneBinding(
        val pane: WeakReference<View>,
        val anchor: WeakReference<View>,
        val what: String,
        /** Runs once as this pane is hidden because its anchor left; nothing else in the process reports that moment. */
        val onHidden: (() -> Unit)? = null,
    )

    private val paneBindings = mutableListOf<PaneBinding>()

    /** isShown, not visibility, deliberately: visibility still reads VISIBLE on a detached view. */
    private fun paneShouldShow(anchor: View?): Boolean =
        anchor != null && anchor.isShown && anchor.width > 0 && anchor.height > 0

    /** Tie [pane]'s visibility to [anchor]'s. See the contract above. */
    private fun bindPane(pane: View, anchor: View, what: String, onHidden: (() -> Unit)? = null) {
        paneBindings.removeAll { val p = it.pane.get(); p == null || p === pane }
        paneBindings.add(
            PaneBinding(
                WeakReference(pane), WeakReference(anchor),
                what, onHidden,
            )
        )
        syncPaneVisibility()
    }

    /** Hide bound panes whose anchor left, restore returners, in the layout phase so a stale pane is never drawn. */
    private fun syncPaneVisibility() {
        val bindings = paneBindings.iterator()
        while (bindings.hasNext()) {
            val b = bindings.next()
            val pane = b.pane.get()
            if (pane == null || pane.parent == null) { bindings.remove(); continue }
            val anchor = b.anchor.get()
            // A collected anchor must be pruned, not obeyed: left in place it re-hides its pane on every sweep, for ever.
            if (anchor == null) {
                if (pane.visibility != View.GONE) {
                    pane.visibility = View.GONE
                    XposedBridge.log("[$TAG] pane '${b.what}' unbound; anchor garbage-collected")
                }
                bindings.remove()
                continue
            }
            val want = if (paneShouldShow(anchor)) View.VISIBLE else View.GONE
            if (pane.visibility == want) continue
            // startHideFade left the material at 0 deliberately; a restore assembles it instead of fading a decal.
            if (want == View.VISIBLE) {
                val g = pane as? GlassView
                if (g != null) {
                    if (g.materialized < 1f || pane.alpha < 1f) {
                        pane.animate().cancel()
                        pane.alpha = 1f
                        g.materializeIn(SEARCH_FADE_MS)
                    }
                } else if (pane.alpha < 1f) {
                    pane.animate().cancel()
                    pane.alpha = 0f
                    pane.animate().alpha(1f).setDuration(SEARCH_FADE_MS)
                        .setInterpolator(searchInterp).start()
                }
            }
            pane.visibility = want
            XposedBridge.log(
                "[$TAG] pane '${b.what}' ${if (want == View.VISIBLE) "restored" else "hidden"}" +
                    "; anchor ${anchorState(anchor)}"
            )
            // After the write, and guarded: a throwing callback must never leave a pane stranded visible.
            if (want == View.GONE) runCatching { b.onHidden?.invoke() }
        }
    }

    /** Why [syncPaneVisibility] decided what it decided, for the log line. */
    private fun anchorState(a: View?): String = when {
        a == null -> "garbage-collected"
        a.parent == null -> "detached from its parent"
        !a.isShown -> "GONE or under a GONE ancestor"
        a.width <= 0 || a.height <= 0 -> "laid out at zero size"
        else -> "on screen"
    }

    @Volatile private var bottomInset = 0

    /** bottom_nav_container's id, kept so a card can ask whether the floating nav is in ITS window. */
    private var navContainerIdPin = 0

    /** header's id, kept so a coordinator can ask whether it is the HOME one. Archived reuses the same layout. */
    private var headerIdPin = 0
    @Volatile private var fabIds: IntArray = IntArray(0)

    /** A user colour on either floating button stands glassFab down: a themeable surface belongs to the theme. */
    private var fabColored = false
    private var miniFabColored = false

    /** Same stand-down as the FAB colours: glass must not stack under a colour the theme owns. */
    private var sendColored = false

    /** White icons are only the fallback for unthemed-on-glass; a user's own icon colour must always win. */
    private var toolbarIconsColored = false

    /** Has the user given the quote its own colour? Then [QuoteAndLabelColors] owns it, not us. */
    private var quoteColored = false
    private var sendIconColored = false

    /** [side, navHeight, lift], filled once the nav has been floated. */
    @Volatile private var navGeom: IntArray? = null

    /** Read once at install; values are baked into surfaces as built, so changes need a WhatsApp restart. */
    private fun loadGlassPrefs(): Boolean {
        var enabled = false
        runCatching {
            val p = XSharedPreferences(
                BuildConfig.APPLICATION_ID,
                Prefs.FILE,
            )
            p.reload()
            val k = Prefs
            enabled = p.getBoolean(k.KEY_GLASS_ENABLED, false)
            if (!enabled) return@runCatching
            BLUR_DP = p.getInt(k.KEY_GLASS_BLUR, 12).toFloat()
            TINT_ALPHA = p.getInt(k.KEY_GLASS_TINT, 10)
            DISPLACE_DP = p.getInt(k.KEY_GLASS_DISPLACE, 20).toFloat()
            BEVEL_FRACTION = p.getInt(k.KEY_GLASS_BEVEL, 25) / 100f
            CARD_RADIUS_DP = p.getInt(k.KEY_GLASS_RADIUS, 20).toFloat()
            RIM_ALPHA = p.getInt(k.KEY_GLASS_RIM, 10)
            RIM_WIDTH_DP = p.getInt(k.KEY_GLASS_RIM_WIDTH, 2).toFloat()
            // Set once here; every construction site would otherwise repeat them.
            GlassParams.defaultTransGamma = p.getInt(k.KEY_GLASS_GAMMA, 70) / 100f
            GlassParams.defaultRimStrokeAngle = p.getInt(k.KEY_GLASS_RIM_ANGLE, 85).toFloat()
            // A set pill colour wins over the frost, the fabColored stand-down pattern.
            tokenTabPillSet = p.getInt(k.OVR_TAB_ACTIVE_PILL, 0) != 0
            navUnreadBg = p.getInt(k.KEY_UNREAD_ACCENT, 0)
            navUnreadText = p.getInt(k.KEY_UNREAD_COUNT_TEXT, 0)
        }.onFailure { XposedBridge.log("[$TAG] glass prefs unreadable: $it") }
        return enabled
    }

    fun install(app: Application) {
        if (!loadGlassPrefs()) {
            XposedBridge.log("[$TAG] disabled by preference")
            return
        }
        val res = app.resources
        // Density is only available here, so the dp to px conversion cannot live in loadGlassPrefs.
        GlassParams.defaultRimStrokePx =
            if (RIM_ALPHA > 0) RIM_WIDTH_DP * res.displayMetrics.density else 0f
        GlassParams.defaultRimStrokeColor = ((RIM_ALPHA * 255 / 100) shl 24) or 0xFFFFFF
        XposedBridge.log(
            "[$TAG] blur=${BLUR_DP}dp tint=$TINT_ALPHA displace=${DISPLACE_DP}dp " +
                "bevel=$BEVEL_FRACTION radius=${CARD_RADIUS_DP}dp " +
                "gamma=${GlassParams.defaultTransGamma} rim=$RIM_ALPHA/${RIM_WIDTH_DP}dp " +
                "rimAngle=${GlassParams.defaultRimStrokeAngle}",
        )
        val pkg = app.packageName

        val headerId = res.waId("header", pkg)
        headerIdPin = headerId
        homeHeaderId = headerId
        val toolbarId = res.waId("toolbar", pkg)
        homeToolbarId = toolbarId
        val toolbarContainerId = res.waId("toolbar_container", pkg)
        val searchBarId = res.waId("my_search_bar", pkg)
        val pagerHolderId = res.waId("pager_holder", pkg)
        val navContainerId = res.waId("bottom_nav_container", pkg)
        navContainerIdPin = navContainerId

        if (headerId == 0 || pagerHolderId == 0) {
            XposedBridge.log("[$TAG] header/pager_holder id missing; glass not armed")
            return
        }

        // @android:id/list is a framework id getIdentifier cannot resolve; ViewThemeDispatcher matches raw ints.
        ViewThemeDispatcher.onId(android.R.id.list) { v -> runCatching { extendList(v) } }

        // ── The chat-list card's host ──────────────────────────────────────────────────
        // conversations_coordinator_layout stacks like a FrameLayout and pages with the content, unlike id/content.
        val coordId = res.waId("conversations_coordinator_layout", pkg)
        if (coordId != 0) ViewThemeDispatcher.onId(coordId) { v ->
            val vg = v as? ViewGroup ?: return@onId
            // Not home-only: Archived inherits this same layout, and home is the window that has header.
            // Anywhere else takes its own card, or cardHostRef follows the user off home onto a foreign host.
            if (headerIdPin != 0 && vg.rootView?.findViewById<View>(headerIdPin) == null) {
                runCatching { injectFolderCard(vg) }
                    .onFailure { XposedBridge.log("[$TAG] injectFolderCard threw: $it") }
                return@onId
            }
            cardHostRef = WeakReference(vg)
            runCatching { injectListPanel(vg) }
                .onFailure { XposedBridge.log("[$TAG] injectListPanel threw: $it") }
        }

        // ── List pages with no stackable root, hosted by android.R.id.content ──────────
        // Their ActionMenuView measures zero width, nothing for a trailing pane to wrap; the card is the whole treatment.
        for ((name, label) in listOf(
            "linked_device_recycler_view" to "linked devices",
            "broadcast_lists_recycler_view" to "broadcast lists",
        )) {
            val listId = res.waId(name, pkg)
            if (listId != 0) ViewThemeDispatcher.onId(listId) { v ->
                runCatching { injectContentCard(v, label) }
                    .onFailure { XposedBridge.log("[$TAG] $label card threw: $it") }
            }
        }
        // The stub declares no inflatedId, so the inflated stats view attaches carrying the stub's id.
        val counterId = res.waId("broadcast_counter_view_stub", pkg)
        if (counterId != 0) ViewThemeDispatcher.onId(counterId) { v ->
            if (v is ViewStub) return@onId
            runCatching { injectBlockCard(v, "broadcast stats") }
                .onFailure { XposedBridge.log("[$TAG] broadcast stats panel threw: $it") }
        }
        val broadcastFabId = res.waId("create_new_broadcast_button", pkg)
        if (broadcastFabId != 0) ViewThemeDispatcher.onId(broadcastFabId) { v ->
            // Attach precedes layout, so the treatment runs from the button's own layout passes.
            if (v.getTag(folderFabTag) == null) {
                v.setTag(folderFabTag, true)
                v.addOnLayoutChangeListener { b, _, _, _, _, _, _, _, _ ->
                    runCatching { folderFab(b, "broadcast") }
                        .onFailure { XposedBridge.log("[$TAG] broadcast fab threw: $it") }
                }
            }
        }
        // Settings is the same shape with a ScrollView; content taller than the viewport clamps the card to it.
        // An A/B flag swaps in a me-tab layout that scrolls a different id; hooking both covers either.
        for (n in listOf("settings_scroll_view", "settings_nested_scroll_view")) {
            val sid2 = res.waId(n, pkg)
            if (sid2 != 0) ViewThemeDispatcher.onId(sid2) { v ->
                runCatching { injectContentCard(v, "settings") }
                    .onFailure { XposedBridge.log("[$TAG] settings card threw: $it") }
            }
        }
        // Sub-pages have few stable list ids, but their Activity names are manifest names and never obfuscate.
        // Attach is per Activity; the card machinery is idempotent and an already treated scroller skips by tag.
        XposedHelpers.findAndHookMethod(
            Activity::class.java, "onPostCreate", Bundle::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val a = param.thisObject as? Activity ?: return
                    val name = a.javaClass.name
                    if (CARDED_ACTIVITY_PREFIXES.none { name.startsWith(it) }) return
                    // Sheet activities already carry the sheet pane; a card under a sheet is buried work.
                    if (a.javaClass.simpleName.endsWith("BottomSheetActivity")) return
                    if (a.javaClass.simpleName.endsWith("Sheet")) return
                    // Both draw a full-screen doodle SIBLING under the content; a sibling is invisible to the ancestor walk, so they are named.
                    if (a.javaClass.simpleName == "About" || a.javaClass.simpleName == "Licenses") return
                    val label = "${name.split('.').getOrElse(2) { "page" }}/${a.javaClass.simpleName}"
                    // The breadcrumb that separates "hook never fired" from a silent guard.
                    logOnce("card path armed: $label")
                    val content =
                        a.findViewById<ViewGroup>(android.R.id.content) ?: return
                    content.post {
                        runCatching {
                            findPageScroller(content)?.let {
                                injectContentCard(it, label)
                            } ?: watchForPageScroller(content, label)
                        }.onFailure { XposedBridge.log("[$TAG] $label card threw: $it") }
                    }
                }
            },
        )

        // ── The media gallery's per-tab grids ──────────────────────────────────────────
        // Each tab inflates its grid from a stub that keeps the generic id, so the Activity scopes it.
        val gridId = res.waId("grid", pkg)
        if (gridId != 0) ViewThemeDispatcher.onId(gridId) { v ->
            if (v is ViewStub) return@onId
            if (activityOf(v)?.javaClass?.name?.startsWith("com.whatsapp.gallery.") != true) return@onId
            runCatching { injectContentCard(v, "gallery", frameCard = true) }
                .onFailure { XposedBridge.log("[$TAG] gallery card threw: $it") }
        }

        // Starred's list is the framework android.R.id.list, shared with the chat list, so it is reached through its own container.
        val starredId = res.waId("starred_messages_content", pkg)
        if (starredId != 0) ViewThemeDispatcher.onId(starredId) { v ->
            val list = (v as? ViewGroup)?.findViewById<View>(android.R.id.list) ?: return@onId
            runCatching { injectContentCard(list, "starred") }
                .onFailure { XposedBridge.log("[$TAG] starred card threw: $it") }
        }

        // ── Snackbars ────────────────────────────────────────────────────────────────────
        // Material's slab cleared and frosted; found from the text view, the layout's one stable id.
        val snackTextId = res.waId("snackbar_text", pkg)
        val snackFrostTag = tagKey("wathemer-snackbar-frost")
        if (snackTextId != 0) ViewThemeDispatcher.onId(snackTextId) { v ->
            var frame: View? = v
            var depth = 0
            while (frame != null && depth < 4 && !frame.javaClass.name.endsWith("SnackbarLayout")) {
                frame = frame.parent as? View
                depth++
            }
            val f = frame ?: return@onId
            if (f.getTag(snackFrostTag) != null) return@onId
            f.setTag(snackFrostTag, true)
            // Posted: the bar measures on the frame after attach, and frost needs a real size.
            f.post {
                runCatching {
                    clearBg(f, "snackbar")
                    frost(f, allowSquare = true, ignorePadding = true, radiusOverride = f.dp(10f))
                    logOnce("snackbar frosted")
                }
            }
        }

        // ── The updates page: its own card and big title ───────────────────────────────
        val updatesListId = res.waId("updates_list", pkg)
        if (updatesListId != 0) ViewThemeDispatcher.onId(updatesListId) { v ->
            val host = v.parent as? ViewGroup
            if (host == null || !canStack(host)) {
                XposedBridge.log(
                    "[$TAG] updates page host is ${host?.javaClass?.simpleName}, which does " +
                        "not stack; no card there"
                )
                return@onId
            }
            updatesListRef = WeakReference(v)
            updatesHostRef = WeakReference(host)
            // Content scrolls up under the title and the header, as it does on Chats.
            (v as? ViewGroup)?.clipToPadding = false
            runCatching { injectUpdatesCard(host) }
                .onFailure { XposedBridge.log("[$TAG] injectUpdatesCard threw: $it") }
            syncUpdatesCard()
        }

        // The wordmark is an image, not the toolbar's text title, so hiding it leaves the other tabs untouched.
        val logoId = res.waId("toolbar_logo", pkg)
        if (logoId != 0) ViewThemeDispatcher.onId(logoId) { v ->
            if (v.visibility != View.GONE) {
                v.visibility = View.GONE
                XposedBridge.log("[$TAG] toolbar_logo hidden for the large title")
            }
        }
        if (searchBarId != 0) ViewThemeDispatcher.onId(searchBarId) { v ->
            // Lift above the rows that scroll underneath; translationZ, since with no background there is no shadow anyway.
            if (v.translationZ < 1f) v.translationZ = 1f
            searchBarRef = WeakReference(v)
            // The search pill starts where this bar rests; layout coords, since getLocationOnScreen samples it mid-flight.
            if (v.getTag(homeBarLayoutTag) == null) {
                v.setTag(homeBarLayoutTag, true)
                v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    val content = contentRef?.get() ?: return@addOnLayoutChangeListener
                    if (view.height <= 0) return@addOnLayoutChangeListener
                    val r = Rect(0, 0, view.width, view.height)
                    if (runCatching { content.offsetDescendantRectToMyCoords(view, r) }.isSuccess) {
                        homeBarTop = r.top
                        homeBarHeight = r.height()
                    }
                    // No return trigger here: layout fires too early or not at all; the pane's hide callback drives it.
                }
            }
            // Injected here: at the container's attach my_search_bar is not in it yet, so findViewById returns null.
            runCatching { injectBigTitle(v) }
                .onFailure { XposedBridge.log("[$TAG] injectBigTitle threw: $it") }
        }

        // One rounded pane for the action group; register every tab's items or it collapses onto the lone overflow.
        for (n in listOf(
            "menuitem_payment_rupee_icon", "menuitem_camera", "menuitem_search",
        )) {
            val cid = res.waId(n, pkg)
            if (cid != 0) ViewThemeDispatcher.onId(cid) { v ->
                val holder = menuHolder(v); tagGroup(holder, GROUP_NORMAL); registerAction(holder)
            }
        }
        // ── The toolbar bleeds through the selection bar ───────────────────────────────
        // action_mode_bar's own visibility is the signal; overlap is not occlusion, never reintroduce a geometric test.
        // Needs a repeating watcher (onId is one-shot), one restoring writer, and skipping a toolbar that holds the bar.
        val waToolbarId = res.waId("toolbar", pkg)
        val actionBarId = res.waId("action_mode_bar", pkg)
        if (waToolbarId != 0 && actionBarId != 0) {
            ViewThemeDispatcher.onId(actionBarId) { bar ->
                runCatching { watchActionBar(bar, waToolbarId) }
                    .onFailure { XposedBridge.log("[$TAG] watchActionBar threw: $it") }
            }
            XposedBridge.log("[$TAG] toolbar bleed guard armed (toolbar=0x${waToolbarId.toString(16)})")
        } else {
            XposedBridge.log("[$TAG] toolbar bleed guard NOT armed: toolbar=$waToolbarId bar=$actionBarId")
        }
        // ── Selection mode's own icons ─────────────────────────────────────────────────
        // The normal icons stay attached underneath, so the two sets cannot be pooled; syncActionsGlass picks the owner.
        for (n in listOf(
            "menuitem_conversations_pin", "menuitem_conversations_unpin",
            "menuitem_conversations_delete", "menuitem_conversations_archive",
            "menuitem_conversations_unarchive", "menuitem_conversations_mark_read",
            "menuitem_conversations_mark_unread", "menuitem_conversations_lock",
            "menuitem_conversations_unlock", "menuitem_conversations_select_all",
            "menuitem_mute", "menuitem_unmute", "menuitem_delete",
        )) {
            val cid = res.waId(n, pkg)
            if (cid != 0) ViewThemeDispatcher.onId(cid) { v ->
                val holder = menuHolder(v); tagGroup(holder, GROUP_SELECTION); registerAction(holder)
            }
        }
        // The overflow button is in both bars, so it belongs to neither group and is always counted.
        res.waId("menuitem_overflow", pkg).takeIf { it != 0 }?.let { cid ->
            ViewThemeDispatcher.onId(cid) { v ->
                val holder = menuHolder(v); tagGroup(holder, GROUP_BOTH); registerAction(holder)
            }
        }

        // Do not frost the voice overlay: the trashcan has no fill of its own and the lock container is full-screen.
        // The picker arms on contact_picker_layout; toolbar and list are names WhatsApp reuses across screens.
        val pickerId = res.waId("contact_picker_layout", pkg)
        if (pickerId != 0) ViewThemeDispatcher.onId(pickerId) { v ->
            runCatching { pickerGlass(v as? ViewGroup ?: return@onId) }
                .onFailure { XposedBridge.log("[$TAG] pickerGlass threw: $it") }
        }

        // The lock pill is styled via ensureLockPane; the trashcan's own disc has no known id.
        val lockId = res.waId("voice_note_lock_container", pkg)
        if (lockId != 0) ViewThemeDispatcher.onId(lockId) { v -> runCatching { ensureLockPane(v) } }

        // ── Filter chips + the filter button ───────────────────────────────────────────
        // No ids, no stacking host, and every capturable subtree is an ancestor, so a frost Drawable is the only option.
        val chipHostId = res.waId("conversations_swipe_to_reveal_filter_recycler_view", pkg)
        val pinnedId = res.waId("conversations_filter_pinned_button_container", pkg)
        // The region filters ride along: their chips have no ids, only content-descs, so the host is the handle there too.
        val filterListId = res.waId("filter_list", pkg)
        for (hid in listOf(chipHostId, pinnedId, filterListId)) {
            if (hid == 0) continue
            ViewThemeDispatcher.onId(hid) { host ->
                val hv = host as? ViewGroup ?: return@onId
                val frostKids = Runnable {
                    for (i in 0 until hv.childCount) {
                        runCatching { frost(hv.getChildAt(i) ?: return@runCatching) }
                    }
                }
                // Paint: every attach. Listener: once per instance.
                if (hv.getTag(frostHostListenerTag) == null) {
                    hv.setTag(frostHostListenerTag, true)
                    hv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> frostKids.run() }
                }
                hv.post(frostKids)
            }
        }

        // ── "See all", the region filters, and the appbar seam ─────────────────────────
        // addon_button is shared: See all on channels, the chevron on Updates; frost()'s shape guard skips the chevron.
        // A WDSButton paints its fill from its variant, so setBackground cannot outrank it; left as a plain frost.
        val addonId = res.waId("addon_button", pkg)
        if (addonId != 0) ViewThemeDispatcher.onId(addonId) { v -> frostOnLayout(v) }

        // ── The reply quote inside the compose pill ───────────────────────────────────
        // Tint-only: the compose pane supplies the blur; a bitmap would sample behind the pill, not the quote.
        val quoteId = res.waId("quoted_message_preview_container", pkg)
        if (quoteId != 0) ViewThemeDispatcher.onId(quoteId) { v ->
            if (!quoteColored) frostOnLayoutWith(v, glassTint(QUOTE_ALPHA), v.dp(QUOTE_RADIUS_DP))
        }

        // ── The same quote, inside a sent/received bubble ─────────────────────────────
        // Target the frame, not the holder (that paints over the accent bar); stands down when QUOTE_BG_COLOR is set.
        // ── And the four green triangles at its corners ───────────────────────────────
        // The frame's foreground nine-patch is a corner mask in stock green, so it is nulled; see installQuoteMaskKill.
        val quoteFrameId = res.waId("quoted_message_frame", pkg)
        if (quoteFrameId != 0) {
            installQuoteMaskKill(quoteFrameId)
            ViewThemeDispatcher.onId(quoteFrameId) { v ->
                if (!quoteColored) {
                    // Blurred fill, not a film: tint-only vanishes against the dark bubble glass.
                    liquidFrostOnLayout(v, QUOTE_RADIUS_DP, QUOTE_ALPHA)
                    if (v.foreground != null) v.foreground = null
                    // The killed foreground mask was what rounded the accent bar; the clip takes over.
                    if (!v.clipToOutline) {
                        v.outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setRoundRect(
                                    0, 0, view.width, view.height, view.dp(QUOTE_RADIUS_DP),
                                )
                            }
                        }
                        v.clipToOutline = true
                    }
                }
            }
        }

        // ── The voice-recording composer ─────────────────────────────────────────────
        // A cover, not a clear: stripped bare, the compose quote underneath shows and two quotes stack.
        val voiceDraftId = res.waId("voice_note_draft_layout_v2", pkg)
        if (voiceDraftId != 0) ViewThemeDispatcher.onId(voiceDraftId) { v ->
            liquidFrostOnLayout(v, 20f, TINT_ALPHA)
        }
        // Tint-only like the compose quote: a second bitmap pill doubles the panel's top edge.
        val quoteV2Id = res.waId("quoted_message_preview_container_v2", pkg)
        if (quoteV2Id != 0) ViewThemeDispatcher.onId(quoteV2Id) { v ->
            if (!quoteColored) frostOnLayoutWith(v, glassTint(QUOTE_ALPHA), v.dp(QUOTE_RADIUS_DP))
        }

        // Section headers go bold; weight only, the size is WhatsApp's and reads fine.
        val headerTvId = res.waId("header_textview", pkg)
        if (headerTvId != 0) ViewThemeDispatcher.onId(headerTvId) { v ->
            val tv = v as? TextView ?: return@onId
            if (tv.typeface?.isBold != true) {
                tv.setTypeface(tv.typeface, Typeface.BOLD)
            }
        }

        // Over glass the 3px rule reads as a seam. INVISIBLE keeps the appbar's height where GONE would collapse it.
        val filterDividerId = res.waId("filter_divider", pkg)
        if (filterDividerId != 0) ViewThemeDispatcher.onId(filterDividerId) { v ->
            if (v.visibility != View.INVISIBLE) {
                v.visibility = View.INVISIBLE
                XposedBridge.log("[$TAG] filter_divider hidden")
            }
        }

        // ── The communities tab ────────────────────────────────────────────────────────
        // The page root does not stack, so the card is a sibling whose margins cancel its height; see injectPageCard.
        val commListId = res.waId("community_recycler_view", pkg)
        if (commListId != 0) ViewThemeDispatcher.onId(commListId) { v ->
            armPageCard(communityCard, v, communityCardTag)
            // Only this tab has the painted ItemDecorations.
            runCatching { killDecorations(v, commListId) }
                .onFailure { XposedBridge.log("[$TAG] killDecorations threw: $it") }
        }

        // ── The calls tab ──────────────────────────────────────────────────────────────
        // Same shape as Communities, measured; its own registration only because both pages are alive at once.
        val callsListId = res.waId("calls_recyclerView", pkg)
        if (callsListId != 0) ViewThemeDispatcher.onId(callsListId) { v ->
            armPageCard(callsCard, v, callsCardTag)
        }

        // The four call-action discs sit in a RecyclerView whose id is the generic list, so the hook is scoped by class.
        val genericListId = res.waId("list", pkg)
        if (genericListId != 0) ViewThemeDispatcher.onId(genericListId) { v ->
            // Routed through WaIds so a class rename logs instead of the discs quietly losing their frost.
            if (!WaIds.classIs(v, "CallInitiationHScroll")) return@onId
            val row = v as? ViewGroup ?: return@onId
            val sweep = Runnable { runCatching { frostCallActions(row) } }
            if (row.getTag(callActionsTag) == null) {
                row.setTag(callActionsTag, true)
                row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sweep.run() }
            }
            sweep.run()
        }

        // ── Cards on the drilled-in channel screens ────────────────────────────────────
        // The card's top is list.top, not the host origin: the chrome differs and one screen's list starts at y321.
        // The call list's WDSDivider reads as a rule on the glass; the list recycles, so the hide re-runs on layout.
        val logsId = res.waId("logs", pkg)
        if (logsId != 0) ViewThemeDispatcher.onId(logsId) { v ->
            val vg = v as? ViewGroup ?: return@onId
            runCatching { hideWdsDividers(vg) }
            if (vg.getTag(callLogDividerTag) == null) {
                vg.setTag(callLogDividerTag, true)
                vg.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { hideWdsDividers(vg) }
                }
            }
        }

        for (n in listOf(
            "directory_category_list",
            "newsletter_list",
            "community_navigation_subgroup_recycler_view",
            "logs",   // the call info screen's entries
        )) {
            val lid = res.waId(n, pkg)
            if (lid == 0) continue
            ViewThemeDispatcher.onId(lid) { v ->
                val host = v.parent as? ViewGroup
                if (host == null || !canStack(host)) {
                    XposedBridge.log(
                        "[$TAG] $n host is ${host?.javaClass?.simpleName}, which does not " +
                            "stack; no card"
                    )
                    return@onId
                }
                (v as? ViewGroup)?.clipToPadding = false
                channelListRef = WeakReference(v)
                channelHostRef = WeakReference(host)
                runCatching { injectChannelCard(host) }
                    .onFailure { XposedBridge.log("[$TAG] injectChannelCard threw: $it") }
                syncChannelCard()
                if (host.getTag(channelCardTag) == null) {
                    host.setTag(channelCardTag, true)
                    host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        runCatching { syncChannelCard() }
                    }
                }
                // Pre-draw, not layout: the collapsing appbar moves the list by offsetTopAndBottom and no layout listener fires.
                if (v.getTag(channelListTag) == null) {
                    v.setTag(channelListTag, true)
                    v.viewTreeObserver.addOnPreDrawListener {
                        runCatching { syncChannelCard() }
                        true
                    }
                }
            }
        }

        // ── The drilled-in screens' toolbar (Channels, Explore channels) ───────────────
        // A different toolbar, in toolbar_holder rather than id/header, so none of the home action-pane work reaches it.
        val toolbarHolderId = res.waId("toolbar_holder", pkg)
        if (toolbarHolderId != 0) ViewThemeDispatcher.onId(toolbarHolderId) { v ->
            val holder = v as? ViewGroup ?: return@onId
            altToolbarRef = WeakReference(holder)
            altBarRef = null                       // this screen's toolbar is discoverable
            runCatching { syncAltToolbarGlass() }
                .onFailure { XposedBridge.log("[$TAG] syncAltToolbarGlass threw: $it") }
            if (holder.getTag(altToolbarTag) == null) {
                holder.setTag(altToolbarTag, true)
                holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncAltToolbarGlass() }
                }
            }
        }

        // ── The communities flow's opaque accent buttons ───────────────────────────────
        // Flat green fills, frosted from a layout listener because WhatsApp applies the fill after attach.
        for (n in listOf(
            "community_navigation_add_group_button",   // "ADD GROUP", community home
            "empty_community_row_button",              // "Start your community", empty state
            "community_nux_next_button",               // "GET STARTED", creation intro
            "add_members_icon",                        // the green circle on the info page
            "community_home_add_group_icon",           // "add group", the admin row under a community
            "owner_view",                              // the "Community Owner" chip
        )) {
            val bid = res.waId(n, pkg)
            if (bid != 0) ViewThemeDispatcher.onId(bid) { v -> frostAccent(v) }
        }

        // ── The community info page's slab dividers ────────────────────────────────────
        // INVISIBLE rather than GONE keeps the 23px of separation, and the gap shows wallpaper.
        for (n in listOf(
            "community_home_top_divider",
            "community_home_header_bottom_divider_admin",
            "community_home_header_bottom_divider_non_admin",
            "community_description_bottom_divider",
            // Slips hideSlabsByShape by construction (16dp side margins), so it is hidden by name like the pair above.
            "community_description_top_divider",
        )) {
            val did = res.waId(n, pkg)
            if (did != 0) ViewThemeDispatcher.onId(did) { v -> hideSlab(v, n) }
        }

        // ── Hairlines on the other glassed screens ────────────────────────────────────
        // status_separator, newsletter_status_divider and list_footer_gray_divider are excluded: their parent has its own fill.
        for (n in listOf(
            "list_section_divider_status",                     // Updates page, on its glass card
            "conversation_row_favorites_footer_divider",       // Chats list, favourites footer
            "conversations_row_lists_manage_footer_divider",   // Chats list, manage-lists footer
            "search_divider",                                  // search disclaimer block
            "reactions_bottom_sheet_divider",                  // reactions sheet
            "dialog_clear_messages_content_divider",           // clear-messages dialog
            "divider_under_nav_bar",                           // call/privacy sheet
        )) {
            val did = res.waId(n, pkg)
            if (did != 0) ViewThemeDispatcher.onId(did) { v -> hideSlab(v, n) }
        }

        // Two more bands carry no id at all, so they are matched by shape, scoped to this page's own container.
        val infoListId = res.waId("community_home_fragment_container", pkg)
        if (infoListId != 0) ViewThemeDispatcher.onId(infoListId) { v ->
            val root = v as? ViewGroup ?: return@onId
            val sweep = Runnable { runCatching { hideSlabsByShape(root) } }
            if (root.getTag(slabSweepTag) == null) {
                root.setTag(slabSweepTag, true)
                // The shape walk from layout. It is a subtree walk, so not every frame.
                root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sweep.run() }
                // The named dividers from pre-draw: one is re-shown with no bounds change, so no layout callback can catch it.
                root.viewTreeObserver.addOnPreDrawListener {
                    runCatching { reassertSlabs() }
                    true
                }
            }
            sweep.run()
        }

        // ── The call info screen ───────────────────────────────────────────────────────
        // Structurally the community home page again, measured; header_view and the appbar share bounds, so both clear.
        val collapsingId = res.waId("call_info_collapsing_toolbar", pkg)
        for (n in listOf("call_info_app_bar", "header_view")) {
            val hid = res.waId(n, pkg)
            if (hid != 0) ViewThemeDispatcher.onId(hid) { v ->
                // header_view is generic; scoped by parent or an unrelated header loses its background too.
                if (n == "header_view" && (v.parent as? View)?.id != collapsingId) return@onId
                clearBg(v, n)
            }
        }

        // The toolbar's parent cannot stack, so the nearest stacking ancestor takes the panes and the bar is passed in.
        val callInfoTitleId = res.waId("call_info_toolbar_content", pkg)
        if (callInfoTitleId != 0) ViewThemeDispatcher.onId(callInfoTitleId) { v ->
            val bar = v.parent as? ViewGroup ?: return@onId
            var holder: ViewGroup? = null
            var p = bar.parent
            while (p is ViewGroup) {
                if (canStack(p)) { holder = p; break }
                p = p.parent
            }
            val h = holder ?: return@onId
            altToolbarRef = WeakReference(h)
            altBarRef = WeakReference(bar)
            runCatching { syncAltToolbarGlass() }
                .onFailure { XposedBridge.log("[$TAG] call info toolbar threw: $it") }
            if (h.getTag(altToolbarTag) == null) {
                h.setTag(altToolbarTag, true)
                h.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncAltToolbarGlass() }
                }
            }
        }

        // The header panel goes in the CollapsingToolbarLayout, which stacks; the AppBarLayout would displace the header.
        if (collapsingId != 0) ViewThemeDispatcher.onId(collapsingId) { v ->
            val host = v as? ViewGroup ?: return@onId
            callHeaderHostRef = WeakReference(host)
            runCatching { injectCallHeaderPanel(host) }
                .onFailure { XposedBridge.log("[$TAG] injectCallHeaderPanel threw: $it") }
            syncCallHeaderPanel()
            if (host.getTag(callHeaderTag) == null) {
                host.setTag(callHeaderTag, true)
                host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncCallHeaderPanel() }
                }
                // Insurance only: the height can change without a layout pass; the collapse itself writes no geometry here.
                host.viewTreeObserver.addOnPreDrawListener {
                    runCatching { syncCallHeaderPanel() }
                    true
                }
            }
        }

        // action_tile_icon spans three screens; only tiles under a known group frost, or the community tiles get it too.
        val tileIconId = res.waId("action_tile_icon", pkg)
        val tileGroupIds = intArrayOf(
            res.waId("call_log_actions", pkg),          // the call log's row of three
            res.waId("business_details_actions", pkg),  // contact info's row under the name
            res.waId("group_details_actions", pkg),      // group info's row under the subject
        ).filter { it != 0 }.toIntArray()
        if (tileIconId != 0 && tileGroupIds.isNotEmpty()) ViewThemeDispatcher.onId(tileIconId) { v ->
            var p: Any? = v.parent
            var known = false
            while (p is View) {
                if (p.id in tileGroupIds) { known = true; break }
                p = p.parent
            }
            if (!known) return@onId
            frostSquareOnLayout(v)
        }

        // ── The community home page's appbar ───────────────────────────────────────────
        // No toolbar_holder here; the pane host is the toolbar's own parent, a FrameLayout subclass.
        val commAppBarId = res.waId("community_navigation_app_bar", pkg)
        if (commAppBarId != 0) ViewThemeDispatcher.onId(commAppBarId) { v ->
            // Measured fully opaque; cleared rather than replaced, so nothing here fights a colour theme.
            clearBg(v, "community_navigation_app_bar")
        }
        val commToolbarId = res.waId("community_navigation_toolbar", pkg)
        if (commToolbarId != 0) ViewThemeDispatcher.onId(commToolbarId) { v ->
            val holder = v.parent as? ViewGroup ?: return@onId
            if (!canStack(holder)) {
                XposedBridge.log(
                    "[$TAG] community toolbar parent is ${holder.javaClass.simpleName}, which does " +
                        "not stack; no panes"
                )
                return@onId
            }
            altToolbarRef = WeakReference(holder)
            altBarRef = null                       // this screen's toolbar is discoverable
            runCatching { syncAltToolbarGlass() }
                .onFailure { XposedBridge.log("[$TAG] syncAltToolbarGlass threw: $it") }
            if (holder.getTag(altToolbarTag) == null) {
                holder.setTag(altToolbarTag, true)
                holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncAltToolbarGlass() }
                }
            }
        }

        // ── The conversation screen ────────────────────────────────────────────────────
        // The toolbar draws before the content here, so the holder needs a translationZ lift for rows to pass behind it.
        // Its geometry changes when the menu inflates; every pane rect is re-derived on layout and nothing is snapshotted.
        val convHolderId = res.waId("search_fragment_and_toolbar_holder", pkg)
        val convCoordId = res.waId("coordinator", pkg)
        val convFooterId = res.waId("footer", pkg)
        if (convCoordId != 0) ViewThemeDispatcher.onId(convCoordId) { v ->
            // A sibling of the holder, so capturing it cannot recurse; re-armed each attach since recreation replaces it.
            (v as? ViewGroup)?.let { convCoordRef = WeakReference(it) }
            runCatching { syncConvToolbar() }
        }
        if (convHolderId != 0) ViewThemeDispatcher.onId(convHolderId) { v ->
            val holder = v as? ViewGroup ?: return@onId
            convHolderRef = WeakReference(holder)
            runCatching { floatConvToolbar(holder) }
                .onFailure { XposedBridge.log("[$TAG] floatConvToolbar threw: $it") }
            // Band before pill: convPillAlpha asks whether a band exists, so the pill tints right on its first frame.
            runCatching { syncConvBand(holder) }
                .onFailure { XposedBridge.log("[$TAG] syncConvBand threw: $it") }
            runCatching { syncConvToolbar() }
                .onFailure { XposedBridge.log("[$TAG] syncConvToolbar threw: $it") }
            if (holder.getTag(convToolbarTag) == null) {
                holder.setTag(convToolbarTag, true)
                holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncConvBand(holder) }
                    runCatching { syncConvToolbar() }
                }
            }
        }
        if (convFooterId != 0) ViewThemeDispatcher.onId(convFooterId) { v ->
            val footer = v as? ViewGroup ?: return@onId
            // footer is generic; scoped by the compose row so no unrelated footer picks this up.
            if (footer.findViewById<View>(res.waId("input_layout", pkg)) == null) {
                return@onId
            }
            convFooterRef = WeakReference(footer)
            runCatching { floatConvFooter(footer) }
                .onFailure { XposedBridge.log("[$TAG] floatConvFooter threw: $it") }
            runCatching { syncConvFooter() }
                .onFailure { XposedBridge.log("[$TAG] syncConvFooter threw: $it") }
            if (footer.getTag(convFooterTag) == null) {
                footer.setTag(convFooterTag, true)
                footer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncConvFooter() }
                }
            }
        }
        // ── The chat scroll and search discs ─────────────────────────────────────────────
        // All inflate from stubs that keep their ids, so the stub itself must be skipped.
        for (n in listOf(
            "scroll_bottom", "search_fab",
            "next_important_message", "ai_voice_entry_fab", "active_cart",
        )) {
            val fid = res.waId(n, pkg)
            if (fid == 0) continue
            ViewThemeDispatcher.onId(fid) { v ->
                if (v is ViewStub) return@onId
                runCatching { glassChatFab(v, n) }
                    .onFailure { XposedBridge.log("[$TAG] $n glass threw: $it") }
            }
        }
        // A bare ImageView, so the pane host has nowhere to go; the frost disc is the fallback.
        val aiRepliesId = res.waId("ai_replies", pkg)
        if (aiRepliesId != 0) ViewThemeDispatcher.onId(aiRepliesId) { v ->
            if (v is ViewStub) return@onId
            frostCircleOnLayout(v)
        }

        // ── The mention autocomplete panel ───────────────────────────────────────────────
        // A pane, not a film: the list under it must blur or the two text layers fight.
        val mentionAttachId = res.waId("mention_attach", pkg)
        if (mentionAttachId != 0) ViewThemeDispatcher.onId(mentionAttachId) { v ->
            val host = v as? FrameLayout ?: return@onId
            val apply = Runnable { runCatching { syncMentionPane(host) } }
            apply.run()
            if (host.getTag(frostListenerTag) == null) {
                host.setTag(frostListenerTag, true)
                host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
            }
        }

        // ── Row-side pills: swipe hint, channel replies, quick forward, reaction counts ──
        // Hand-built rather than frost(): a lone-emoji reaction pill is as tall as wide and the aspect gate drops it.
        for (n in listOf(
            "swipe_to_reply_hint",
            "message_hint",
            "replies_pill_container_key",
            "newsletter_quick_forwarding_pill_container_key",
            "reactions_bubble_layout",
        )) {
            val pid2 = res.waId(n, pkg)
            if (pid2 != 0) ViewThemeDispatcher.onId(pid2) { v ->
                watchFrostPosition(v)
                val apply = Runnable {
                    runCatching {
                        if (v.height <= 0) return@runCatching
                        if (v.getTag(pillPadTag) == null) {
                            v.setTag(pillPadTag, true)
                            val ex = v.dp(3f).toInt()
                            val ey = v.dp(1.5f).toInt()
                            v.setPadding(
                                v.paddingLeft + ex, v.paddingTop + ey,
                                v.paddingRight + ex, v.paddingBottom + ey,
                            )
                        }
                        val existing = v.getTag(frostTag) as? FrostDrawable
                        if (existing != null && v.background === existing) {
                            existing.setRadius(v.height / 2f)
                            return@runCatching
                        }
                        // Blurred wallpaper as the fill: a film lets the bubble edge bleed through these.
                        val d = FrostDrawable(
                            v, bubbleBackdrop(v), v.height / 2f, glassTint(CHIP_ALPHA),
                            strokeWidth = v.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
                            ignorePadding = true,
                        )
                        v.setTag(frostTag, d)
                        v.background = d
                    }
                }
                apply.run()
                if (v.getTag(frostListenerTag) == null) {
                    v.setTag(frostListenerTag, true)
                    v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
                }
            }
        }

        // ── Top chat banners ─────────────────────────────────────────────────────────────
        val chatBannerId = res.waId("banner_content", pkg)
        if (chatBannerId != 0) ViewThemeDispatcher.onId(chatBannerId) { v ->
            frostOnLayoutWith(v, glassTint(CHIP_ALPHA), v.dp(16f))
        }

        // The forward picker's send bar, an opaque strip under the recipients.
        val pickerFooterId = res.waId("footer_container", pkg)
        val pickerShadowId = res.waId("shadow_top", pkg)
        if (pickerFooterId != 0) ViewThemeDispatcher.onId(pickerFooterId) { v ->
            if (activityOf(v)?.javaClass?.name?.contains(".picker.") != true) return@onId
            liquidFrostOnLayout(v, 20f, TINT_ALPHA)
            if (pickerShadowId != 0) {
                (v as? ViewGroup)?.findViewById<View>(pickerShadowId)?.let { s ->
                    if (s.visibility != View.INVISIBLE) s.visibility = View.INVISIBLE
                }
            }
        }

        // ── Calls tab leftovers ──────────────────────────────────────────────────────────
        // The joinable-call row keeps a static ripple, which ensureBgHook's colour swap cannot reach.
        val joinableRootId = res.waId("joinable_call_log_root_view", pkg)
        val callRowId = res.waId("call_row_container", pkg)
        if (joinableRootId != 0 && callRowId != 0) ViewThemeDispatcher.onId(callRowId) { v ->
            if (insideId(v, joinableRootId, 3)) liquidFrostOnLayout(v, 16f)
        }
        // Cleared to a hole by the shell clearer while the call-action discs beside it are frosted.
        val addFavId = res.waId("add_favorite_icon", pkg)
        if (addFavId != 0) ViewThemeDispatcher.onId(addFavId) { v -> frostCircleOnLayout(v) }
        // The call-link sheet's inner box has no id of its own; its sibling names the row.
        val callLinkId = res.waId("call_link", pkg)
        if (callLinkId != 0) ViewThemeDispatcher.onId(callLinkId) { v ->
            val box = (v.parent as? ViewGroup)?.let { p ->
                (0 until p.childCount).map { p.getChildAt(it) }.firstOrNull { c ->
                    c !is GlassView && c.id == View.NO_ID && c.background != null && c !is ViewGroup
                }
            } ?: return@onId
            liquidFrostOnLayout(box, 16f)
        }
        val linkIconId = res.waId("link_icon", pkg)
        if (linkIconId != 0) ViewThemeDispatcher.onId(linkIconId) { v -> frostCircleOnLayout(v) }

        // ── The balloons WhatsApp paints in code ─────────────────────────────────────────
        // All take their fill from the bubble resolver at bind time, so the frost re-applies per layout.
        for (n in listOf("conversation_row_date_divider", "date_divider_header")) {
            val did = res.waId(n, pkg)
            if (did != 0) ViewThemeDispatcher.onId(did) { v ->
                if (v is ViewStub) return@onId
                liquidFrostOnLayout(v, keepPadding = true)
            }
        }
        val tiBubbleId = res.waId("ti_bubble", pkg)
        if (tiBubbleId != 0) ViewThemeDispatcher.onId(tiBubbleId) { v ->
            // Only the bubble; the dots are a sibling drawable and must keep animating.
            liquidFrostOnLayout(v, keepPadding = true)
        }
        val unreadTvId = res.waId("unread_divider_tv", pkg)
        if (unreadTvId != 0) ViewThemeDispatcher.onId(unreadTvId) { v ->
            // The band is the parent's flat colour; the pill shape belongs on the text.
            (v.parent as? View)?.let { p -> if (p.background != null) clearBg(p, "unread band") }
            liquidFrostOnLayout(v, keepPadding = true)
        }
        // info is one of the most reused ids in the app, hence the activity scope.
        val e2eInfoId = res.waId("info", pkg)
        if (e2eInfoId != 0) ViewThemeDispatcher.onId(e2eInfoId) { v ->
            if (activityOf(v)?.javaClass?.name?.endsWith(".Conversation") != true) return@onId
            liquidFrostOnLayout(v, 16f, keepPadding = true)
        }

        // With no bubble on the row this timestamp chip IS the surface; the 100-odd inline ones sit in a bubble already.
        bubblelessRootIds = intArrayOf(
            res.waId("sticker_root", pkg),
            res.waId("push_to_video_root", pkg),
        ).filter { it != 0 }.toIntArray()
        val dateWrapId = res.waId("date_wrapper", pkg)
        if (dateWrapId != 0 && bubblelessRootIds.isNotEmpty()) ViewThemeDispatcher.onId(dateWrapId) { v ->
            val pid = (v.parent as? View)?.id ?: return@onId
            if (!bubblelessRootIds.contains(pid)) return@onId
            // No clearBg first: the frost replaces the fill anyway, and the stock drawable still holds the inset.
            liquidFrostOnLayout(v, keepPadding = true)
        }

        // ── Search-in-chat ───────────────────────────────────────────────────────────────
        // Scoped to Conversation: the home search screen reuses this id with its own treatment.
        val convSearchRootId = res.waId("search_fragment", pkg)
        val convSearchBarId = res.waId("search_view_toolbar", pkg)
        if (convSearchRootId != 0) ViewThemeDispatcher.onId(convSearchRootId) { v ->
            if (activityOf(v)?.javaClass?.name?.endsWith(".Conversation") != true) return@onId
            clearBg(v, "conversation search root")
            // The bar itself gets the capsule pane from syncConvToolbar; only its fill goes here.
            if (convSearchBarId != 0) {
                (v as? ViewGroup)?.findViewById<View>(convSearchBarId)?.let { bar ->
                    clearBg(bar, "conversation search bar")
                }
            }
        }

        // Elevation zeroed: a translucent button casting a shadow reads as a smudge, not as raised.
        val sendContainerId = res.waId("send_container", pkg)
        if (sendContainerId != 0) ViewThemeDispatcher.onId(sendContainerId) { v ->
            if (v.elevation != 0f || v.translationZ != 0f) {
                v.stateListAnimator = null
                v.elevation = 0f
                v.translationZ = 0f
                logOnce("send button elevation zeroed")
            }
            // The fill is a RippleDrawable, so clearing it also costs the touch ripple; stands down for a user colour.
            if (!sendColored) clearBg(v, "send_container")
        }

        // On clear glass the stock glyph reads near-black, so it goes white; dictation_btn stays for the UNRESOLVED log.
        for (n in listOf("send", "voice_note_btn", "dictation_btn")) {
            val gid = res.waId(n, pkg)
            if (gid == 0) continue
            ViewThemeDispatcher.onId(gid) { v ->
                if (!sendIconColored) {
                    (v as? ImageView)?.imageTintList =
                        ColorStateList.valueOf(Color.WHITE)
                }
            }
        }

        // ── The follow button on the updates card ──────────────────────────────────────
        // A pane cannot go in (every capturable subtree is an ancestor), so it takes the chips' tint-only frost.
        // button_view is generic, so it is scoped to its container; Explore more and Create channel stay stock.
        val followContainerId = res.waId("quick_follow_button_container", pkg)
        val followBtnId = res.waId("button_view", pkg)
        if (followBtnId != 0 && followContainerId != 0) {
            ViewThemeDispatcher.onId(followBtnId) { v ->
                if ((v.parent as? View)?.id == followContainerId) frostOnLayout(v)
            }
        }

        // ── The add-status tile on the updates card ─────────────────────────────────────
        // Stock is an opaque slab and a pane cannot go in, so the chips' frost; 16dp, the tile is too tall for a pill.
        val statusTileId = res.waId("status_tile_layout", pkg)
        if (statusTileId != 0) ViewThemeDispatcher.onId(statusTileId) { v ->
            frostOnLayoutWith(v, glassTint(CHIP_ALPHA), v.dp(16f))
        }

        // ── The rest of the updates card's stock leftovers ──────────────────────────────
        // The share strip under the tiles; one id per Facebook/Instagram link state.
        for (n in listOf(
            "updates_contextual_migration_share_view",
            "updates_contextual_status_and_channel_share_view",
            "updates_contextual_status_and_channel_upsell",
        )) {
            val sid = res.waId(n, pkg)
            if (sid != 0) ViewThemeDispatcher.onId(sid) { v ->
                frostOnLayoutWith(v, glassTint(CHIP_ALPHA), v.dp(16f))
            }
        }
        // 12dp matches the slab it replaces.
        val adBannerId = res.waId("advertise_banner_container", pkg)
        if (adBannerId != 0) ViewThemeDispatcher.onId(adBannerId) { v ->
            frostOnLayoutWith(v, glassTint(CHIP_ALPHA), v.dp(12f))
        }
        // Muted tile and hidden disc: both ids are generic, so only the status tray's instances count.
        val statusTrayId = res.waId("status_list", pkg)
        val mutedTileId = res.waId("buttons_layout", pkg)
        val mutedSlabId = res.waId("status_preview", pkg)
        if (statusTrayId != 0 && mutedTileId != 0 && mutedSlabId != 0) {
            ViewThemeDispatcher.onId(mutedTileId) { v ->
                if (!insideId(v, statusTrayId, 4)) return@onId
                (v as? ViewGroup)?.findViewById<View>(mutedSlabId)
                    ?.let { frostOnLayoutWith(it, glassTint(CHIP_ALPHA), it.dp(16f)) }
            }
        }
        val hiddenDiscId = res.waId("contact_selector", pkg)
        if (statusTrayId != 0 && hiddenDiscId != 0) ViewThemeDispatcher.onId(hiddenDiscId) { v ->
            if (insideId(v, statusTrayId, 5)) frostCircleOnLayout(v)
        }
        // The megaphone card root carries the fill but no id; its action button names it.
        val megaBtnId = res.waId("megaphone_action_button", pkg)
        if (megaBtnId != 0) ViewThemeDispatcher.onId(megaBtnId) { v ->
            var anc: View? = v.parent as? View
            var hops = 0
            while (anc != null && hops < 4 && anc.background == null) { anc = anc.parent as? View; hops++ }
            anc?.takeIf { it.background != null }
                ?.let { frostOnLayoutWith(it, glassTint(CHIP_ALPHA), it.dp(16f)) }
        }
        // A transient full-width slab while a follow request runs; the card behind it is enough.
        val qfProgressId = res.waId("quick_follow_progressBar", pkg)
        if (qfProgressId != 0) ViewThemeDispatcher.onId(qfProgressId) { v ->
            (v.parent as? View)?.let { p -> if (p.background != null) clearBg(p, "quick follow strip") }
        }
        // WDSBanner paints its fill in code after attach, so the frost re-applies per layout.
        ViewThemeDispatcher.onView { v ->
            if (v.javaClass.name == "com.whatsapp.ui.wds.components.banners.WDSBanner") {
                frostOnLayoutWith(v, glassTint(CHIP_ALPHA), v.dp(16f))
            }
            false
        }
        // My Statuses ships the home FABs in a foreign window, out of the fab-pane machinery's reach; glassPageFab stands in.
        for (n in listOf("fab", "fab_second")) {
            val fid = res.waId(n, pkg)
            if (fid == 0) continue
            ViewThemeDispatcher.onId(fid) { v ->
                if (activityOf(v)?.javaClass?.name
                        ?.startsWith("com.whatsapp.status.playback.MyStatuses") != true
                ) return@onId
                val apply = Runnable { runCatching { glassPageFab(v) } }
                apply.run()
                if (v.getTag(frostListenerTag) == null) {
                    v.setTag(frostListenerTag, true)
                    v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
                }
            }
        }

        // Status privacy parks viewport slack inside its stretched column; wrap and pin it so the card hugs real content.
        val spHeaderId = res.waId("see_my_status_header", pkg)
        if (spHeaderId != 0) ViewThemeDispatcher.onId(spHeaderId) { v ->
            val col = v.parent as? View ?: return@onId
            val lp = col.layoutParams ?: return@onId
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                runCatching { lp.javaClass.getField("verticalBias").setFloat(lp, 0f) }
                col.layoutParams = lp
            }
        }

        // ── Bottom sheets ──────────────────────────────────────────────────────────────
        // Material's own container, so this covers every sheet; sheets live in their own window, hence screen coords.
        val sheetId = res.waId("design_bottom_sheet", pkg)
        if (sheetId != 0) ViewThemeDispatcher.onId(sheetId) { v ->
            // On layout, not attach: a sheet attaches at 0x0, so an attach hook alone sees nothing.
            runCatching { injectSheetGlass(v) }
                .onFailure { XposedBridge.log("[$TAG] injectSheetGlass threw: $it") }
        }

        // ── Self-declared sheets ─────────────────────────────────────────────────────────
        // These carry BottomSheetBehavior themselves, so the design_bottom_sheet hook never sees them.
        // Deliberately stock: expressions tray, the call popups, the two webview sheets; the status viewer's details sheet gets tint-only frost.
        for (n in listOf(
            "bottom_sheet",
            "audio_chat_bottom_sheet",
            "pre_call_sheet_content",
            "psa_bottom_sheet_root",
            "virality_bottom_sheet",
            "list_holder",
            "selected_list_holder",
        )) {
            val selfSheetId = res.waId(n, pkg)
            if (selfSheetId == 0) continue
            ViewThemeDispatcher.onId(selfSheetId) { v ->
                runCatching { glassSelfSheet(v, n) }
                    .onFailure { XposedBridge.log("[$TAG] self sheet $n threw: $it") }
            }
        }

        // ── Sheet contents that paint OVER the wrapper's glass ──────────────────────────
        // These roots paint their own fill above the cleared wrapper, and no shell clearing reaches a dialog window.
        // This id roots a full-screen page as well as the sheet, and only the sheet is a card.
        val galleryPickerId = res.waId("gallery_picker_layout", pkg)
        if (galleryPickerId != 0) ViewThemeDispatcher.onId(galleryPickerId) { v ->
            if (!isMediaPickerSheet(v)) return@onId
            liquidFrostOnLayout(v, CARD_RADIUS_DP, TINT_ALPHA)
            // The grid reaches the edges, so uncontained it draws across the sheet's top corners.
            if (!v.clipToOutline) {
                v.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val r = view.dp(CARD_RADIUS_DP)
                        // Bottom carried past the edge: a sheet rounds its top corners only.
                        outline.setRoundRect(0, 0, view.width, view.height + r.toInt(), r)
                    }
                }
                v.clipToOutline = true
            }
        }
        for (n in listOf(
            "sticker_pack_preview_bottom_sheet_layout",
            "view_replies_bottom_sheet",
            "answering_keyboard_popup",
            "history_drawer_content",
        )) {
            val sid3 = res.waId(n, pkg)
            if (sid3 != 0) ViewThemeDispatcher.onId(sid3) { v ->
                liquidFrostOnLayout(v, CARD_RADIUS_DP, TINT_ALPHA)
            }
        }

        // ── The small overlay windows ────────────────────────────────────────────────────
        // Toasts and tooltips live in their own windows, so only their own view can carry glass.
        for (n in listOf("toast_layout", "card_container", "skin_tone_selector")) {
            val tid = res.waId(n, pkg)
            if (tid != 0) ViewThemeDispatcher.onId(tid) { v ->
                liquidFrostOnLayout(v, 16f)
            }
        }
        for (n in listOf("tooltip_text", "ai_voice_tooltip_container")) {
            val tid2 = res.waId(n, pkg)
            if (tid2 != 0) ViewThemeDispatcher.onId(tid2) { v -> liquidFrostOnLayout(v) }
        }
        // Raw Dialogs: no parentPanel, so panelGlass never sees them.
        for (n in listOf("permission_request_dialog", "nag_text", "recipients_container")) {
            val did2 = res.waId(n, pkg)
            if (did2 != 0) ViewThemeDispatcher.onId(did2) { v ->
                liquidFrostOnLayout(v, CARD_RADIUS_DP, TINT_ALPHA)
            }
        }

        // ── Inline opaque panels ───────────────────────────────────────────────────────
        // parentPanel is AppCompat's dialog container, so this reaches every AlertDialog in the app.
        // The expressions tray stays stock, a legible grid needs its fill; its id is emoji_edit_text_with_expressions_tray_linear_layout.
        for (n in listOf("media_picker_popup_content", "parentPanel")) {
            val pid = res.waId(n, pkg)
            if (pid == 0) continue
            ViewThemeDispatcher.onId(pid) { v ->
                runCatching { panelGlass(v, n) }
                    .onFailure { XposedBridge.log("[$TAG] panelGlass($n) threw: $it") }
            }
        }

        // ── The reactions tray ─────────────────────────────────────────────────────────
        // It rides three transform AnimatorSets, so the glass is a background drawable, never a pane.
        val trayId = res.waId("reactions_tray_layout", pkg)
        if (trayId != 0) ViewThemeDispatcher.onId(trayId) { v ->
            runCatching { frostReactionsTray(v) }
                .onFailure { XposedBridge.log("[$TAG] reactions tray frost threw: $it") }
        }

        // ── The settings cover ─────────────────────────────────────────────────────────
        // me_tab_cover_photo is the flat band behind the profile header; clearing it lets the wallpaper run full height.
        val coverId = res.waId("me_tab_cover_photo", pkg)
        if (coverId != 0) ViewThemeDispatcher.onId(coverId) { v -> clearBg(v, "me_tab_cover_photo") }

        // ── The call screen ────────────────────────────────────────────────────────────
        // Audio calls only: video renders into a Surface no capture can see, so the glass stands down live.
        val callRootId = res.waId("call_screen_root", pkg)
        if (callRootId != 0) ViewThemeDispatcher.onId(callRootId) { v ->
            runCatching { callScreenGlass(v as? ViewGroup ?: return@onId) }
                .onFailure { XposedBridge.log("[$TAG] call screen glass threw: $it") }
        }
        // WA re-inflates and re-shows its own call backdrop after connect; the wallpaper wins only if this loses every time.
        val callBgId = res.waId("call_background", pkg)
        if (callBgId != 0) ViewThemeDispatcher.onId(callBgId) { v ->
            if (v.rootView?.findViewWithTag<View>("wt_wallpaper") != null) v.visibility = View.INVISIBLE
        }
        // The card can rebuild without the root re-attaching; its own registration keeps the refs live.
        val callCardId = res.waId("call_controls_card", pkg)
        if (callCardId != 0) ViewThemeDispatcher.onId(callCardId) { v ->
            runCatching { callCardGlass(v as? ViewGroup ?: return@onId) }
                .onFailure { XposedBridge.log("[$TAG] call card glass threw: $it") }
        }
        // The More rows carry their own opaque fills over the pane; the label's attach is the inflate signal.
        val noiseLabelId = res.waId("noise_cancellation_label", pkg)
        if (noiseLabelId != 0) ViewThemeDispatcher.onId(noiseLabelId) { v ->
            runCatching { callMoreMenuGlass(v) }
                .onFailure { XposedBridge.log("[$TAG] call more menu threw: $it") }
        }
        // The header circles are WaImageButtons, not WDS; their fill is their own background drawable.
        for (n in listOf("minimize_btn", "participant_btn", "network_health_btn", "security_btn", "send_message_btn")) {
            val bid = res.waId(n, pkg)
            if (bid == 0) continue
            ViewThemeDispatcher.onId(bid) { v ->
                if (v.rootView?.findViewWithTag<View>("wt_wallpaper") == null) return@onId
                runCatching { tintWdsShape(v.background, glassTint(CHIP_ALPHA)) }
            }
        }

        // ── Overflow / dropdown menus ──────────────────────────────────────────────────
        // content is each menu row's id and the only handle a popup exposes; its fill lives in another window.
        menuRowId = res.waId("content", pkg)
        menuTitleId = res.waId("menu_title", pkg)
        menuSelRowId = res.waId("message_selection_drop_down_row_text", pkg)
        ensurePopupGlass()

        // ── Attachment tile pills ──────────────────────────────────────────────────────
        // The pill is the icon's own background; scoped through the holders because icon is one of the most reused ids.
        val attachIconId = res.waId("icon", pkg)
        if (attachIconId != 0) for (n in listOf(
            "pickfiletype_gallery_holder", "pickfiletype_location_holder",
            "pickfiletype_contact_holder", "pickfiletype_document_holder",
            "pickfiletype_poll_holder", "pickfiletype_payment_holder",
            "pickfiletype_event_holder", "pickfiletype_imagine_sheet_holder",
            "pickfiletype_camera_holder", "pickfiletype_audio_holder",
            "pickfiletype_music_holder", "pickfiletype_quiz_holder",
            "pickfiletype_question_holder", "pickfiletype_group_status_holder",
            "pickfiletype_pix_holder", "pickfiletype_remittance_holder",
            "pickfiletype_call_link",
        )) {
            val hid = res.waId(n, pkg)
            if (hid == 0) continue
            ViewThemeDispatcher.onId(hid) { holder ->
                val icon = holder.findViewById<View>(attachIconId) ?: return@onId
                // On layout: at attach the icon is 0x0 and frost refuses it. Registration guarded, the frost is not.
                if (icon.getTag(tilePillTag) == null) {
                    icon.setTag(tilePillTag, true)
                    icon.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        runCatching { frost(v, allowSquare = true, ignorePadding = true) }
                    }
                }
                // ignorePadding is the pill-vs-lozenge difference: an icon button's padding is glyph space, not chip inset.
                icon.post { runCatching { frost(icon, allowSquare = true, ignorePadding = true) } }
            }
        }

        // One component, three ids; a stub's inflatedId is its own, so the usual id misses those pages.
        for (n in listOf("wds_search_bar", "search_bar", "persistent_search_bar")) {
            val barId = res.waId(n, pkg)
            if (barId == 0) continue
            ViewThemeDispatcher.onId(barId) { v ->
                // The stub fires this id too, and it is not the bar.
                val bar = v as? FrameLayout ?: return@onId
                if (bar.getTag(wdsBarTag) == null) {
                    bar.setTag(wdsBarTag, true)
                    bar.viewTreeObserver.addOnGlobalLayoutListener {
                        runCatching { syncWdsSearchBar(bar) }
                    }
                }
                runCatching { syncWdsSearchBar(bar) }
                    .onFailure { logOnce("wds search bar glass threw on $n: $it") }
            }
        }

        // The AppCompat SearchView is a different component and gets its own path; it declines the
        // pages whose host cannot carry a pane rather than clearing a fill it cannot replace.
        val searchViewId = res.waId("search_view", pkg)
        if (searchViewId != 0) ViewThemeDispatcher.onId(searchViewId) { v ->
            if (v.getTag(wdsBarTag) == null) {
                v.setTag(wdsBarTag, true)
                v.viewTreeObserver.addOnGlobalLayoutListener {
                    runCatching { syncSearchViewGlass(v) }
                }
            }
            runCatching { syncSearchViewGlass(v) }
                .onFailure { logOnce("search view glass threw: $it") }
        }

        // The band under the photo: its fill is a FOREGROUND, it has no id, so its parent reaches it.
        val photoSectionId = res.waId("me_tab_profile_info_photo_section", pkg)
        if (photoSectionId != 0) ViewThemeDispatcher.onId(photoSectionId) { v ->
            val section = v as? ViewGroup ?: return@onId
            val strip = Runnable {
                for (i in 0 until section.childCount) {
                    val c = section.getChildAt(i) ?: continue
                    if (c is ViewGroup || c is ViewStub) continue
                    if (c.foreground != null) {
                        c.foreground = null
                        logOnce("me tab photo band foreground cleared")
                    }
                }
            }
            strip.run()
            if (section.getTag(frostHostListenerTag) == null) {
                section.setTag(frostHostListenerTag, true)
                section.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> strip.run() }
            }
        }
        val meTabId = res.waId("me_tab_container", pkg)
        if (meTabId != 0) ViewThemeDispatcher.onId(meTabId) { v ->
            runCatching { ensureMeTabCard(v) }
                .onFailure { logOnce("me tab card threw: $it") }
        }

        val searchInnerId = res.waId("search_bar_inner_layout", pkg)
        if (searchInnerId != 0) ViewThemeDispatcher.onId(searchInnerId) { v ->
            clearBg(v, "search_bar_inner_layout")
            // clearBg always runs, so a failed pane means an invisible search bar; log it or there is nothing to go on.
            runCatching { injectSearchFieldGlass(v) }
                .onFailure { logOnce("search field glass FAILED, bar is now transparent with no pane: $it") }
            // Line its sides up with the two strips rather than WhatsApp's own inset.
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val side = (16 * v.resources.displayMetrics.density).toInt()
                if (lp.leftMargin != side || lp.rightMargin != side) {
                    lp.leftMargin = side
                    lp.rightMargin = side
                    lp.marginStart = side
                    lp.marginEnd = side
                    v.layoutParams = lp
                }
            }
        }

        if (toolbarId != 0) ViewThemeDispatcher.onId(toolbarId) { v ->
            toolbarRef = WeakReference(v)
            // Also driven from here: the channel screens are a different Activity, so id/content's listener never fires there.
            syncToolbarTitle()
            if (v.getTag(toolbarTitleTag) == null) {
                v.setTag(toolbarTitleTag, true)
                v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncToolbarTitle() }
            }
            // No pane behind the toolbar; cleared so the blurred wallpaper itself is the frost.
            clearBg(v, "toolbar")
            // Pad the toolbar's end or the pane clips at the screen edge; 8dp, tighter than the free-floating surfaces.
            val side = (8 * v.resources.displayMetrics.density).toInt()
            if (v.paddingRight != side) {
                v.setPadding(v.paddingLeft, v.paddingTop, side, v.paddingBottom)
            }
        }
        if (toolbarContainerId != 0) ViewThemeDispatcher.onId(toolbarContainerId) { v ->
            clearBg(v, "toolbar_container")
        }
        // Keeps the toolbar and nav see-through when WhatsApp re-applies fills; forcedBg decides which views, not id.
        ensureBgHook()

        // ── Bottom nav: float it as a pill with content passing behind ─────────────────
        // Floated by a negative top margin: the weighted id/content absorbs the space and grows underneath the pill.
        if (navContainerId != 0) ViewThemeDispatcher.onId(navContainerId) { v ->
            runCatching { floatNav(v) }.onFailure { XposedBridge.log("[$TAG] floatNav threw: $it") }
        }
        // No pane of ours: the nav is themeable. Round both views, they share bounds and the square one shows through.
        for (n in listOf("bottom_nav", "bottom_nav_container")) {
            val nid = res.waId(n, pkg)
            if (nid == 0) continue
            ViewThemeDispatcher.onId(nid) { v ->
                if (n == "bottom_nav") navBarRef = WeakReference(v)
                roundView(v, n)
                // The outline provider reads view.height, so invalidate on resize; registration guarded, roundView is not.
                if (v.getTag(navRoundListenerTag) == null) {
                    v.setTag(navRoundListenerTag, true)
                    v.addOnLayoutChangeListener { view, _, t, _, b, _, _, _, _ ->
                        if (b - t > 0) view.invalidateOutline()
                    }
                }
            }
        }
        // ── The active tab pill ──────────────────────────────────────────────────────────
        // The chips' treatment, never a pane: Material animates the pill by transform, the reaction bar's killer.
        val pillId = res.waId("navigation_bar_item_active_indicator_view", pkg)
        if (pillId != 0 && !tokenTabPillSet) {
            ViewThemeDispatcher.onId(pillId) { v -> frostOnLayout(v) }
        }

        // A seam across the pill; hide it INVISIBLE so the nav's height does not change under us.
        val dividerId = res.waId("bottom_nav_divider", pkg)
        if (dividerId != 0) ViewThemeDispatcher.onId(dividerId) { v ->
            if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
        }
        // Not per-view attach callbacks: fab's onId never fires again after a recreation, so id/content's layout drives it.
        fabIds = listOf("fab", "extended_mini_fab", "fab_second")
            .map { res.waId(it, pkg) }
            .filter { it != 0 }
            .toIntArray()
        runCatching {
            val p = XSharedPreferences(
                BuildConfig.APPLICATION_ID,
                Prefs.FILE,
            )
            p.reload()
            fabColored = p.getInt(Prefs.OVR_FAB_BG, 0) != 0
            miniFabColored = p.getInt(Prefs.OVR_MINI_FAB_BG, 0) != 0
            sendColored = p.getInt(Prefs.COMPOSE_SEND_BG, 0) != 0
            sendIconColored = p.getInt(Prefs.COMPOSE_SEND_ICON, 0) != 0
            quoteColored = p.getInt(Prefs.QUOTE_BG_COLOR, 0) != 0
            // Either toolbar token stands us down: the icon sweep cannot tell which toolbar it is looking at.
            toolbarIconsColored =
                p.getInt(Prefs.OVR_TOOLBAR_ICONS, 0) != 0 ||
                p.getInt(Prefs.CHAT_TOOLBAR_ICONS, 0) != 0
        }.onFailure { XposedBridge.log("[$TAG] fab colour prefs unreadable: $it") }

        ViewThemeDispatcher.onId(headerId) { header ->
            headerRef = WeakReference(header)
            // Re-bind the pane to each new header or it stays tied to a dead one and the pill vanishes at random.
            actionsGlassRef?.get()?.let { g -> bindPane(g, header, "toolbar actions") }
            registerFadeOnHide(header)
            runCatching { injectToolbarGlass(header, pagerHolderId) }
                .onFailure { XposedBridge.log("[$TAG] injectToolbarGlass threw: $it") }
            // Contact info reuses this id; infoHeaderGlass tells the two apart by the collapsing photo.
            runCatching { infoHeaderGlass(header) }
                .onFailure { XposedBridge.log("[$TAG] infoHeaderGlass threw: $it") }
        }

        // ── Home -> conversation: the open transition and the row ids ───────────────────
        rowContainerId = res.waId("contact_row_container", pkg)
        callRowContainerId = res.waId("call_row_container", pkg)
        ensureChatOpenTransition()
        // Row selection rides ensureBgHook (see rowSelectionPane); it must exist before the first row binds.
        if (rowContainerId != 0 || callRowContainerId != 0) ensureBgHook()

        // ── The search screen ──────────────────────────────────────────────────────────
        searchInputId = res.waId("search_input", pkg)
        searchResultListId = res.waId("result_list", pkg)
        // ── A pane per search result ──────────────────────────────────────────────────
        // One pane stamping a rect per row, so cost does not grow with results; an empty query yields no rects at all.
        if (searchResultListId != 0) ViewThemeDispatcher.onId(searchResultListId) { v ->
            runCatching { searchRowGlass(v) }
                .onFailure { XposedBridge.log("[$TAG] searchRowGlass threw: $it") }
        }
        // divider is generic: only ever resolved inside the search fragment's subtree, never against the window.
        searchDividerId = res.waId("divider", pkg)
        val searchFragmentId = res.waId("search_fragment", pkg)
        if (searchFragmentId != 0) ViewThemeDispatcher.onId(searchFragmentId) { v ->
            runCatching { layoutSearchScreen(v) }
                .onFailure { XposedBridge.log("[$TAG] layoutSearchScreen threw: $it") }
        }

        // ── Contact info: five groups that look like one wall of text ──────────────────
        // Gives back the grouping the transparency pass erased; keyed on the *_details_card ids, one per info screen.
        for (n in listOf(
            "contact_details_card",
            "group_details_card",
            "newsletter_details_card",
            "business_details_card",
        )) {
            val id = res.waId(n, pkg)
            if (id == 0) continue
            ViewThemeDispatcher.onId(id) { v ->
                runCatching { infoCardGlass(v) }
                    .onFailure { XposedBridge.log("[$TAG] infoCardGlass threw: $it") }
            }
        }
        infoHeaderPlaceholderId = res.waId("header_placeholder", pkg)
        infoParticipantsId = res.waId("participants_card", pkg)
        infoMemberSheetId = res.waId("group_participants_search", pkg)
        if (infoMemberSheetId != 0) ViewThemeDispatcher.onId(infoMemberSheetId) { v ->
            runCatching { memberSheetPane(v) }
                .onFailure { XposedBridge.log("[$TAG] memberSheetPane threw: $it") }
        }

        infoCollapsingPhotoId = res.waId("collapsing_profile_photo_view", pkg)
        infoPictureId = res.waId("picture", pkg)
        infoPhotoOverlayId = res.waId("photo_overlay", pkg)
        infoTitleId = res.waId("contact_title", pkg)
        infoSubtitleId = res.waId("contact_subtitle", pkg)
        infoGroupTitleId = res.waId("group_title", pkg)
        infoGroupSubtitleId = res.waId("group_details_card_subtitle", pkg)

        // The info tiles ride the action_tile_icon registration above; a control on glass must be brighter than the glass.

        // Rounded, not inset: the strip is deliberately edge-to-edge. Re-run on layout as the thumbs arrive.
        val thumbsId = res.waId("media_card_thumbs", pkg)
        if (thumbsId != 0) ViewThemeDispatcher.onId(thumbsId) { host ->
            val hv = host as? ViewGroup ?: return@onId
            val round = Runnable {
                for (i in 0 until hv.childCount) {
                    val thumb = hv.getChildAt(i) ?: continue
                    runCatching { roundInnerSurface(thumb, "media card thumb") }
                }
            }
            if (hv.getTag(frostHostListenerTag) == null) {
                hv.setTag(frostHostListenerTag, true)
                hv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> round.run() }
            }
            hv.post(round)
        }

        // ── Square corners inside a rounded bubble ─────────────────────────────────────
        // Clipped, not re-backgrounded: an outline clip touches no drawable, so it survives every recolour.
        for (n in listOf(
            "quoted_message_holder",       // the reply block inside a bubble
            "media_container_wrapper",     // single image / video
            "media_container",
            "picture_frame",
        )) {
            val qid = res.waId(n, pkg)
            if (qid == 0) continue
            ViewThemeDispatcher.onId(qid) { v -> roundInnerSurface(v, n) }
        }

        runCatching { ensureToolbarDividerHook(app) }
            .onFailure { XposedBridge.log("[$TAG] ensureToolbarDividerHook threw: $it") }
        runCatching { installBubbleGlass(app) }
            .onFailure { XposedBridge.log("[$TAG] installBubbleGlass threw: $it") }

        // WDSButtons rebuild their fill from the variant per style pass and swallow external backgrounds; retint inside their own path.
        runCatching {
            // The live layout is the _v2 family; the older variant's ids ride along in case a build flips back.
            draftBtnIds = listOf(
                "voice_note_draft_stop_btn_v2",
                "voice_note_cancel_btn_v2",
                "voice_note_draft_pause_resume_btn",
                "voice_note_draft_delete_btn",
                "draft_send_v2",
                "voice_note_draft_send_btn",
            ).map { app.resources.waId(it, app.packageName) }.filter { it != 0 }.toIntArray()
            // The send glyph goes white; stock's black arrow disappears into the glass fill.
            draftSendBtnIds = listOf("draft_send_v2", "voice_note_draft_send_btn")
                .map { app.resources.waId(it, app.packageName) }.filter { it != 0 }.toIntArray()
            // The call screen's circular WDSButtons take the same chip frost; End stays red on purpose.
            callBtnIds = listOf(
                "audio_route_button", "camera_button", "mute_button", "more_button",
                "screen_sharing_button", "minimize_btn", "participant_btn",
                "calling_camera_switch_wds_button", "calling_effects_wds_button",
                "network_health_btn", "security_btn", "send_message_btn",
            ).map { app.resources.waId(it, app.packageName) }.filter { it != 0 }.toIntArray()
            val cls = WaIds.clazz(
                app.classLoader, "com.whatsapp.ui.wds.components.button.WDSButton", "draft button frost",
            )
            if (cls != null && (draftBtnIds.isNotEmpty() || callBtnIds.isNotEmpty())) {
                XposedHelpers.findAndHookMethod(
                    cls, "setupBackgroundStyle",
                    ColorStateList::class.java,
                    ColorStateList::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!armed) return
                            val v = param.thisObject as? View ?: return
                            if (v.id == 0 || (v.id !in draftBtnIds && v.id !in callBtnIds)) return
                            if (runCatching { tintWdsShape(v.background, glassTint(CHIP_ALPHA)) }.getOrDefault(false)) {
                                logOnce("WDS buttons frosted through the style path")
                            }
                        }
                    },
                )
                XposedHelpers.findAndHookMethod(
                    cls, "setupContentStyle",
                    ColorStateList::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!armed) return
                            val v = param.thisObject as? View ?: return
                            if (v.id == 0 || v.id !in draftSendBtnIds) return
                            // The widget derives paint colour and icon filter from this list; white rides its own path.
                            param.args[0] = draftWhiteContent
                        }
                    },
                )
            }
        }

        // The nav count badge is Material's BadgeDrawable in the tab's OVERLAY, invisible to every dump; caught at attach.
        runCatching {
            XposedHelpers.findAndHookMethod(
                ViewOverlay::class.java, "add",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val d = param.args[0] as? Drawable ?: return
                        runCatching { themeNavBadge(d) }
                    }
                },
            )
        }

        armed = true
        XposedBridge.log("[$TAG] armed (header=$headerId pagerHolder=$pagerHolderId)")
    }

    /** True once install completed; [badgeGlassFill] answers 0 before that. */
    @Volatile private var armed = false

    /** The unread badges' glass fill for HomeActivityHook; 0 stands the glass down. */
    fun badgeGlassFill(): Int = if (armed) glassTint(CHIP_ALPHA) else 0

    /** The digit over the frost; white for legibility on the translucent fill. */
    fun badgeGlassText(): Int = if (armed) 0xFFFFFFFF.toInt() else 0

    /** Material's BadgeDrawable by TRAIT, never by its R8 name: one Drawable field and one helper holding a TextPaint. */
    private fun themeNavBadge(d: Drawable) {
        val cls = d.javaClass
        if (cls.superclass != Drawable::class.java) return
        val shapeField = cls.declaredFields.firstOrNull {
            Drawable::class.java.isAssignableFrom(it.type)
        } ?: return
        val helperField = cls.declaredFields.firstOrNull { f ->
            runCatching {
                f.type.declaredFields.any { it.type == TextPaint::class.java }
            }.getOrDefault(false)
        } ?: return
        shapeField.isAccessible = true
        helperField.isAccessible = true
        val shape = shapeField.get(d) as? Drawable ?: return
        // Tint, not a paint write: the shape re-derives its paints from state, and tint survives that.
        shape.setTintList(
            ColorStateList.valueOf(
                if (navUnreadBg != 0) navUnreadBg else glassTint(CHIP_ALPHA)
            )
        )
        val helper = helperField.get(d) ?: return
        val tp = helper.javaClass.declaredFields
            .first { it.type == TextPaint::class.java }
            .apply { isAccessible = true }
            .get(helper) as? TextPaint ?: return
        tp.color = if (navUnreadText != 0) navUnreadText else 0xFFFFFFFF.toInt()
        logOnce("nav count badge themed (${cls.name})")
    }

    /** The unread tokens win over the frost on the nav badge too; read at install like every glass value. */
    private var navUnreadBg = 0
    private var navUnreadText = 0

    /** Read at install: a set pill colour keeps the stock/user pill and the frost never registers. */
    private var tokenTabPillSet = false

    /** The tint channel, from wallpaper luma: white over dark, black over bright; never store a finished colour. */
    private var glassTintChannel = 255
    private var glassTintResolved = false

    /** The shared pane tint: the resolved channel at the user's alpha. */
    private val glassTintColor: Int get() = glassTint(TINT_ALPHA)

    /** The same tint at a different alpha, for surfaces that must read against the glass. */
    private fun glassTint(alpha: Int): Int =
        Color.argb(alpha, glassTintChannel, glassTintChannel, glassTintChannel)

    private fun resolveGlassTint(views: List<View>) {
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

    /** WallpaperImage calls this at inject, so the channel resolves before the first pane bakes it. */
    fun noteWallpaperInjected(image: View, dim: View?) {
        if (!armed) return
        resolveGlassTint(listOfNotNull(image, dim))
    }

    /** Our injected wallpaper views, found by tag and re-resolved per pane; a recreation replaces them. */
    private fun wallpaperUnderlay(anyView: View): List<View> = runCatching {
        val root = anyView.rootView
        // Image first, then the dim, the order WallpaperImage adds them; literal tags avoid loading its class.
        listOfNotNull(
            root.findViewWithTag<View>("wt_wallpaper"),
            root.findViewWithTag<View>("wt_wallpaper_dim"),
        ).also { resolveGlassTint(it) }
    }.getOrDefault(emptyList())

    /** Pane in my_search_bar sized to the field (the field cannot stack); backdrop is id/list, never an ancestor. */
    private fun injectSearchFieldGlass(inner: View) {
        val bar = inner.parent as? FrameLayout ?: return
        if (bar.getTag(innerGlassTag) != null) return
        if (!bar.isAttachedToWindow) return
        bar.setTag(innerGlassTag, true)

        val glass = GlassView(bar.context).apply {
            backdrop = listRef?.get()
            underlay = wallpaperUnderlay(bar)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = bar.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = bar.dp(DISPLACE_DP)
                fresnelStrength = 0.4f
                tintColor = glassTint(TINT_ALPHA)
            }
        }
        bar.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        val sync = Runnable {
            if (inner.width <= 0 || inner.height <= 0) return@Runnable
            // Resolve, don't remember: a backdrop bound once at construction can end up a detached list, frozen or blank.
            // Guarded: the setter throws on an ancestor, and a WA reshuffle would turn that into a per-layout crash.
            listRef?.get()?.let { l -> if (glass.backdrop !== l) runCatching { glass.backdrop = l }.onFailure { logOnce("search-field backdrop rejected: $it") } }
            val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
            if (lp.width != inner.width || lp.height != inner.height ||
                lp.leftMargin != inner.left || lp.topMargin != inner.top
            ) {
                lp.width = inner.width
                lp.height = inner.height
                lp.leftMargin = inner.left
                lp.topMargin = inner.top
                glass.layoutParams = lp
                glass.params.cornerRadius = inner.height / 2f
            }
        }
        inner.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync.run() }
        inner.post(sync)
        XposedBridge.log("[$TAG] search field pane added (backdrop=id/list)")
    }

    // status.playback and group.product are named per page, the rest of those packages must stay bare; the dialer has no scroller to card.
    /** Manifest names never obfuscate; every non-sheet page under these takes the content card and folder header. */
    private val CARDED_ACTIVITY_PREFIXES = listOf(
        "com.whatsapp.settings.",
        "com.whatsapp.payments.",
        "com.whatsapp.catalog.",
        "com.whatsapp.status.audienceselector.",
        "com.whatsapp.status.updates.",
        "com.whatsapp.status.playback.MyStatuses",
        "com.whatsapp.status.playback.MyStatusAudience",
        "com.whatsapp.status.playback.ArchivedStatuses",
        "com.whatsapp.status.playback.audience.",
        "com.whatsapp.status.playback.newsletterstatus.",
        "com.whatsapp.conversation.conversationrow.message.MessageDetails",
        "com.whatsapp.conversation.conversationrow.message.KeptMessages",
        "com.whatsapp.dmsetting.",
        "com.whatsapp.ephemeral.",
        "com.whatsapp.group.product.GroupPermissions",
        "com.whatsapp.group.product.GroupMembersSelector",
        "com.whatsapp.group.product.newgroup.",
        "com.whatsapp.contact.ui.picker.AddGroupParticipantsSelector",
        "com.whatsapp.contact.ui.picker.BroadcastListMembersSelector",
        "com.whatsapp.chatinfo.addtogroups.",
        "com.whatsapp.xfamily.groups.ui.GroupMembersSelector",
        "com.whatsapp.calling.ui.callhistory.group.GroupCallParticipantPicker",
        "com.whatsapp.calling.ui.calllink.",
        "com.whatsapp.contactshub.",
        // Settings-reachable pages living outside com.whatsapp.settings.
        "com.whatsapp.aura.",
        "com.whatsapp.lists.",
        "com.whatsapp.chatlock.",
        "com.whatsapp.twofactor.",
        "com.whatsapp.backup.",
        "com.whatsapp.storage.",
        "com.whatsapp.blocklist.",
        "com.whatsapp.profile.ui.",
        "com.whatsapp.authentication.",
        "com.whatsapp.lastseen.",
        "com.whatsapp.privacy.",
        "com.whatsapp.integrityai.",
        "com.whatsapp.privateai.",
        "com.whatsapp.migration.transfer.",
        "com.whatsapp.favorites.",
    )

    private val chatFabTag = tagKey("wathemer-chat-fab")

    /** The chevron and search discs float over the bubbles, so the pane captures the list live, inside the disc's own frame. */
    private fun glassChatFab(fab: View, label: String) {
        val host = fab as? FrameLayout ?: return
        if (host.getTag(chatFabTag) != null) return
        host.setTag(chatFabTag, true)
        // The circle is the image's own background; the glyph is a separate src and survives the clear.
        var disc: View? = null
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            if (c is ImageView && c.background != null) {
                disc = c
                break
            }
        }
        val d = disc ?: return
        clearBg(d, label)
        val glass = GlassView(host.context).apply {
            // Through the fab's own window, never a conv ref: stacked Conversations re-point those at the topmost.
            backdrop = host.rootView?.findViewById(android.R.id.list)
            underlay = wallpaperUnderlay(host)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = host.dp(BLUR_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = host.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
        }
        host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        val sync = Runnable {
            if (d.width <= 0 || d.height <= 0) return@Runnable
            host.rootView?.findViewById<View>(android.R.id.list)?.let { l ->
                // Guarded like the search-field twin: the setter throws on an ancestor.
                if (glass.backdrop !== l) runCatching { glass.backdrop = l }.onFailure { logOnce("chat-FAB backdrop rejected: $it") }
            }
            val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
            if (lp.width != d.width || lp.height != d.height ||
                lp.leftMargin != d.left || lp.topMargin != d.top
            ) {
                lp.width = d.width
                lp.height = d.height
                lp.leftMargin = d.left
                lp.topMargin = d.top
                glass.layoutParams = lp
                glass.params.cornerRadius = minOf(d.width, d.height) / 2f
            }
        }
        d.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync.run() }
        host.post(sync)
        glass.pressSource = d
        XposedBridge.log("[$TAG] $label disc glassed")
    }

    /** The radius is the optics ceiling (the lensing band clamps to it); a fraction so big surfaces are not starved. */
    private const val PANEL_RADIUS_FRACTION = 0.22f
    private const val PANEL_RADIUS_MAX_DP = 48f

    /** Panels stack over content that already carries a pane, so their own blur composes with it. */
    private const val PANEL_BLUR_SCALE = 0.7f

    private var menuRowId = 0
    private var menuTitleId = 0
    /** The message long-press menu's rows carry neither of the two above. */
    private var menuSelRowId = 0
    private val panelGlassTag = tagKey("wathemer-panel-glass")
    private val panelSweepTag = tagKey("wathemer-panel-sweep")

    /** Corner radius of the background about to be replaced; must be called before that background is cleared. */
    private fun readCornerRadius(v: View): Float? {
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

    private val stockPadById = HashMap<Int, Rect>()

    /**
     * Our fill reports no padding, so replacing a drawable that had some remeasures a wrap_content
     * host narrower. Learn the stock inset once per id, then hold the view at it.
     */
    private fun keepStockPadding(v: View) {
        val id = v.id
        if (id == View.NO_ID) return
        val bg = v.background
        if (bg != null && bg !is FrostDrawable) {
            val seen = Rect()
            if (bg.getPadding(seen) && seen.left + seen.right > 0) stockPadById[id] = seen
        }
        val want = stockPadById[id] ?: return
        // Only on a mismatch: setPadding requests layout, and an unguarded write from a layout callback loops.
        if (v.paddingLeft != want.left || v.paddingTop != want.top ||
            v.paddingRight != want.right || v.paddingBottom != want.bottom
        ) {
            v.setPadding(want.left, want.top, want.right, want.bottom)
            dropFrame(v)
        }
    }

    /**
     * The label was measured against the old inset, so this frame would show it clipped. Returning
     * false from pre-draw cancels the traversal instead of presenting it.
     */
    private fun dropFrame(v: View) {
        val observer = v.viewTreeObserver ?: return
        if (!observer.isAlive) return
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                runCatching {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    else v.viewTreeObserver.removeOnPreDrawListener(this)
                }
                return false
            }
        })
    }

    /** The picker's sheet variant, tested the way the app tests it. A server flag picks which ships. */
    private fun isMediaPickerSheet(v: View): Boolean {
        var c: Class<*>? = activityOf(v)?.javaClass
        while (c != null) {
            if (c.name == "com.whatsapp.gallerypicker.ui.MediaPickerBottomSheetActivity") return true
            c = c.superclass
        }
        return false
    }

    /** The Activity a view ultimately belongs to, through however many ContextWrappers. */
    private fun activityOf(v: View): Activity? {
        var c: Context? = v.context
        var hops = 0
        while (c != null && hops < 8) {
            if (c is Activity) return c
            c = (c as? ContextWrapper)?.baseContext
            hops++
        }
        return null
    }

    /** The live content the panel refracts; the source must never be an ancestor of the pane, which recurses. */
    private fun backdropFor(panel: View, host: ViewGroup, underlay: List<View>): View? {
        val act = activityOf(panel)
        val content = act?.findViewById<View>(android.R.id.content)
        if (act != null && panel.rootView !== act.window?.decorView) return content
        // The biggest eligible sibling, not the first (a zero-size ViewStub), and never a wallpaper view (double blur).
        var best: View? = null
        var bestArea = 0
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i) ?: continue
            if (c === panel || isAncestorOf(c, panel)) break   // our own branch; stop before it
            // By name too: the conversation's own WDSWallpaper is in neither list, and identity alone picks it as source.
            if (c.javaClass.simpleName.contains("Wallpaper", ignoreCase = true)) continue
            if (underlay.any { it === c || isAncestorOf(c, it) }) continue
            val area = c.width * c.height
            if (area > bestArea) { bestArea = area; best = c }
        }
        return best
    }

    private fun isAncestorOf(maybeAncestor: View, v: View): Boolean {
        var p: View? = v
        var hops = 0
        while (p != null && hops < 24) {
            if (p === maybeAncestor) return true
            p = p.parent as? View
            hops++
        }
        return false
    }

    /** Inline-panel glass: walks up to a stacking ancestor and inserts at the panel's branch, just behind it. */
    private fun panelGlass(panel: View, what: String) {
        if (panel.getTag(panelGlassTag) == null) {
            if (!panel.isAttachedToWindow) return
            // Before clearBg below strips it.
            val own = readCornerRadius(panel)
            var child: View = panel
            var host = panel.parent as? ViewGroup
            while (host != null && !canStack(host)) {
                child = host
                host = host.parent as? ViewGroup
            }
            if (host == null) {
                logOnce("panel glass: no stacking ancestor above $what")
                return
            }
            val under = wallpaperUnderlay(host).ifEmpty { wallpaperUnderlayGlobal() }
            val back = runCatching { backdropFor(panel, host, under) }.getOrNull()
            val glass = GlassView(host.context).apply {
                // The live content, not just the wallpaper; without it the pane reads as a frosted sheet laid over the app.
                if (back != null) backdrop = back
                // The underlay fills where WhatsApp draws nothing; dialogs need the global form, their window has no wallpaper.
                underlay = under
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = host.dp(BLUR_DP * PANEL_BLUR_SCALE)
                    cornerRadius = own ?: host.dp(CARD_RADIUS_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = host.dp(DISPLACE_DP)
                    // Halved; the content underneath already carries a lit pane.
                    fresnelStrength = 0.25f
                    // Off; the pane underneath already lifted this content.
                    transGamma = 1f
                    // Half a pane's tint; the content underneath already carries one.
                    tintColor = glassTint((TINT_ALPHA / 2).coerceAtLeast(0))
                }
            }
            // MarginLayoutParams: addView converts them, avoiding a compile-time coordinatorlayout dependency.
            host.addView(
                glass, host.indexOfChild(child).coerceAtLeast(0),
                ViewGroup.MarginLayoutParams(0, 0),
            )
            panel.setTag(panelGlassTag, glass)
            clearBg(panel, what)
            XposedBridge.log(
                "[$TAG] $what: backdrop=${back?.javaClass?.simpleName ?: "NONE"}" +
                    " ownRadius=${own?.toInt() ?: -1}px"
            )
            // Pre-draw, not layout: layout-phase reads flicker the pane to full screen; pre-draw sees settled positions.
            if (panel.getTag(panelSweepTag) == null) {
                panel.setTag(panelSweepTag, true)
                panel.viewTreeObserver.addOnPreDrawListener {
                    syncPanelGlass(panel)
                    true
                }
            }
            XposedBridge.log("[$TAG] panel glass for $what in ${host.javaClass.simpleName}")
        }
        syncPanelGlass(panel)
    }

    // UI-thread scratch, reused like popupRowGlass's paneRect: this runs per pre-draw while any panel shows.
    private val panelScratchRect = Rect()

    private fun syncPanelGlass(panel: View) {
        val glass = panel.getTag(panelGlassTag) as? GlassView ?: return
        val host = glass.parent as? ViewGroup ?: return
        val want = if (paneShouldShow(panel)) View.VISIBLE else View.GONE
        if (glass.visibility != want) glass.visibility = want
        if (want != View.VISIBLE) return
        val r = panelScratchRect
        r.set(0, 0, panel.width, panel.height)
        if (!runCatching { host.offsetDescendantRectToMyCoords(panel, r) }.isSuccess) return
        val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.width != r.width() || lp.height != r.height() ||
            lp.leftMargin != r.left || lp.topMargin != r.top
        ) {
            lp.width = r.width()
            lp.height = r.height()
            lp.leftMargin = r.left
            lp.topMargin = r.top
            glass.layoutParams = lp
        }
        // Radius scales with the surface: the lensing band clamps to it, and a fixed radius starves big dialogs to frost.
        // Deliberately overwrites a panel's own radius every frame; the own radius read at creation only seeds the first frame.
        val radius = (minOf(r.width(), r.height()) * PANEL_RADIUS_FRACTION)
            .coerceIn(glass.dp(16f), glass.dp(PANEL_RADIUS_MAX_DP))
        if (abs(glass.params.cornerRadius - radius) > 0.5f) {
            glass.params.cornerRadius = radius
        }
    }

    private var popupHooked = false
    private val popupGlassTag = tagKey("wathemer-popup-glass")

    /** Popups expose no id, so PopupWindow's show methods are hooked; a menu is identified by its row ids. */
    private fun ensurePopupGlass() {
        if (popupHooked) return
        popupHooked = true
        runCatching {
            var hooked = 0
            for (m in PopupWindow::class.java.declaredMethods) {
                if (m.name != "showAsDropDown" && m.name != "showAtLocation") continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val pw = param.thisObject as? PopupWindow ?: return
                        // Posted, not immediate: at show time the content view is 0x0 with zero rows; one frame later it is real.
                        pw.contentView?.post {
                            runCatching { glassPopup(pw) }
                                .onFailure { logOnce("glassPopup threw: $it") }
                        }
                    }
                })
                hooked++
            }
            XposedBridge.log("[$TAG] popup glass armed on $hooked PopupWindow show methods")
        }.onFailure { XposedBridge.log("[$TAG] popup glass FAILED: $it") }
    }

    private fun glassPopup(pw: PopupWindow) {
        val content = pw.contentView ?: return
        if (content.getTag(popupGlassTag) != null) return
        // A menu is rows carrying the menu-row id; one row is enough, the Calls Block menu has exactly one.
        // Both row shapes count: overflow rows carry content, call-menu buttons carry menu_title and no content.
        val rows = countDescendantsWithId(content, menuRowId, 0) +
            (if (menuTitleId != 0) countDescendantsWithId(content, menuTitleId, 0) else 0) +
            (if (menuSelRowId != 0) countDescendantsWithId(content, menuSelRowId, 0) else 0)
        if (rows < 1) return
        content.setTag(popupGlassTag, true)
        // The fill sits on wrapper views above the content: clear each one, reading the radius first or it is lost.
        var v: View? = content
        var hops = 0
        var radius: Float? = null
        while (v != null && hops < 4) {
            if (v.background != null) {
                if (radius == null) radius = readCornerRadius(v)
                clearBg(v, "popup menu")
            }
            v = v.parent as? View
            hops++
        }
        // The attachment panel keeps its fill and its shadow on a DESCENDANT, which the walk above cannot reach.
        val clipId = content.resources.waId("paper_clip_layout", content.context.packageName)
        val attachPanel = (if (clipId != 0) content.findViewById<View>(clipId) else null)?.also { clip ->
            if (radius == null) radius = readCornerRadius(clip)
            clearBg(clip, "paper_clip_layout")
            clip.elevation = 0f
        } != null
        popupRowGlass(content, radius)
        // Menu rows on one sheet want seams; a tile grid does not, and its "rows" include the spacers.
        if (!attachPanel) popupRowDividers(content)
        // Class and size in the log so a wrongly glassed popup names itself.
        XposedBridge.log(
            "[$TAG] popup menu glassed ($rows rows, ${content.javaClass.simpleName} " +
                "${content.width}x${content.height})"
        )
    }

    /** One pane behind the search results, stamping a rounded rect per result row. */
    private fun searchRowGlass(list: View) {
        if (list.getTag(searchRowGlassTag) != null) return
        // isAttachedToWindow is still false inside our own attach hook; tag first so a second attach cannot queue a pane.
        list.setTag(searchRowGlassTag, true)
        list.post { runCatching { insertSearchRowPane(list) }
            .onFailure { XposedBridge.log("[$TAG] insertSearchRowPane threw: $it") } }
    }

    private fun insertSearchRowPane(list: View) {
        // The list and its fragment holder are LinearLayouts, so neither hosts the pane; the same walk as panelGlass.
        var host = list.parent as? ViewGroup
        while (host != null && !canStack(host)) host = host.parent as? ViewGroup
        if (host == null) {
            logOnce("search rows: no stacking ancestor above result_list")
            return
        }
        // The host outlives the list, rebuilt per search open; re-point the surviving pane or panes accumulate, each pinning its dead list.
        (host.getTag(searchRowPaneTag) as? GlassBubblePane)?.let { pane ->
            if (pane.parent === host) {
                pane.collect = { out -> collectSearchRowRects(list, out) }
                XposedBridge.log("[$TAG] search row glass re-bound to new list")
                return
            }
        }
        val d = list.resources.displayMetrics.density
        val pane = GlassBubblePane(host.context).apply {
            params = GlassParams(d).apply {
                blurRadius = list.dp(BLUR_DP)
                cornerRadius = list.dp(SEARCH_ROW_RADIUS_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = list.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
            tint = { glassTintColor }
            // Via the activity's content view: the search fragment's own root holds no wallpaper, the same trap as the popup path.
            backdrop = { contentRef?.get()?.let { c -> bubbleBackdrop(c) } }
            placement = { bubbleWpPlacement }
            dim = { 0f }
            rimColor = glassTint(BUBBLE_RIM_ALPHA)
            rimWidth = d
            collect = { out -> collectSearchRowRects(list, out) }
            onGeometryChanged = { markWallpaperGeometryDirty() }
        }
        host.addView(
            pane, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        host.setTag(searchRowPaneTag, pane)
        XposedBridge.log("[$TAG] search row glass inserted into ${host.javaClass.simpleName}")
    }

    /** One rect per result row, in screen px; skip the chip row by type, never by index, it recycles away. */
    private fun collectSearchRowRects(list: View, out: RectList) {
        val group = list as? ViewGroup ?: return
        // One field read that ends the walk once the search closes and its list detaches.
        if (!group.isAttachedToWindow) return
        val inset = 1.5f * list.resources.displayMetrics.density
        for (i in 0 until group.childCount) {
            val row = group.getChildAt(i) as? ViewGroup ?: continue
            if (row is HorizontalScrollView) continue
            if (!row.isShown || row.height <= 0 || row.width <= 0) continue
            row.getLocationOnScreen(searchRowAt)
            out.add(
                searchRowAt[0].toFloat(),
                searchRowAt[1] + inset,
                (searchRowAt[0] + row.width).toFloat(),
                searchRowAt[1] + row.height - inset,
            )
        }
    }

    private val searchRowAt = IntArray(2)
    private val searchRowGlassTag = tagKey("wathemer-search-row-glass")
    private val searchRowPaneTag = tagKey("wathemer-search-row-pane")

    /* ── Contact info's collapsing header ───────────────────────────────────────────────── */

    private var infoListRef: WeakReference<ViewGroup>? = null
    private val infoHeaderTag = tagKey("wathemer-info-header")
    private var infoCollapsingPhotoId = 0
    private var infoPictureId = 0
    private var infoPhotoOverlayId = 0
    private var infoTitleId = 0
    private var infoSubtitleId = 0
    private var infoGroupTitleId = 0
    private var infoGroupSubtitleId = 0
    private val infoGhostAt = IntArray(2)
    private val infoGhostTag = tagKey("wathemer-info-ghost")

    /** Clear the header's black band (picture + photo_overlay); re-asserted every layout, one clear is undone by the first scroll. */
    private fun infoHeaderGlass(header: View) {
        val holder = header as? ViewGroup ?: return
        // The header id is shared with the home screen; the collapsing photo marks this one as contact info.
        if (infoCollapsingPhotoId == 0 || holder.findViewById<View>(infoCollapsingPhotoId) == null) return
        val hideBand = Runnable {
            for (id in intArrayOf(infoPictureId, infoPhotoOverlayId)) {
                if (id == 0) continue
                val v = holder.findViewById<View>(id) ?: continue
                if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
            }
        }
        hideBand.run()
        val sync = Runnable {
            hideBand.run()
            runCatching { syncInfoFade(holder) }
        }
        sync.run()
        if (holder.getTag(infoHeaderTag) == null) {
            holder.setTag(infoHeaderTag, true)
            holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync.run() }
            logOnce("info header band cleared")
        }
    }

    private var infoTopPaneRef: WeakReference<GlassView>? = null
    private val infoTopAt = IntArray(2)
    /** Expanded height per header, weakly keyed: the two info screens share the id at different heights, one shared max dims both. */
    private val infoHeaderMaxH = WeakHashMap<View, Int>()

    /** Stronger than a reading surface: this panel exists to bury what slides under it. */
    private const val INFO_FADE_BLUR_BOOST = 1.8f

    /** Collapse-driven blur band. One GlassView because abutting panes cannot seam; its expanded height is learned from layout. */
    private fun syncInfoFade(header: ViewGroup) {
        val h = header.height
        if (h <= 0) return
        val maxH = maxOf(infoHeaderMaxH[header] ?: 0, h)
        infoHeaderMaxH[header] = maxH
        val at = IntArray(2)
        header.getLocationOnScreen(at)

        // How far through the collapse we are: 0 at rest, 1 once the bar has finished shrinking.
        val progress = ((maxH - h).toFloat() /
            (maxH - header.dp(56f)).coerceAtLeast(1f)).coerceIn(0f, 1f)
        syncInfoTopPane(header, progress)

        // Fade the names out across the panel's bottom edge; alpha is only written while they cross it, so WhatsApp's crossfade survives.
        run {
            val lineY = (at[1] + h).toFloat()
            for (id in intArrayOf(infoTitleId, infoSubtitleId, infoGroupTitleId, infoGroupSubtitleId)) {
                if (id == 0) continue
                val v = header.rootView?.findViewById<View>(id) ?: continue
                if (v.height <= 0) continue
                v.getLocationOnScreen(infoGhostAt)
                val w = ((infoGhostAt[1] + v.height - lineY) / v.height).coerceIn(0f, 1f)
                if (w < 1f) {
                    v.setTag(infoGhostTag, true)
                    if (v.alpha != w) v.alpha = w
                } else if (v.getTag(infoGhostTag) != null) {
                    v.setTag(infoGhostTag, null)
                    v.alpha = 1f
                }
            }
        }
    }

    /** The covering pane at index 0 of header: over the list's content, under the bar's children, and header is the only host that stacks. */
    private fun syncInfoTopPane(header: ViewGroup, progress: Float) {
        if (header.width <= 0 || header.height <= 0) return
        // Runs up over the status bar so it is the only pane in the region; two panes cannot seam at a join.
        header.getLocationOnScreen(infoTopAt)
        val lift = infoTopAt[1]
        // Unclip all the way up: the clip that matters sits several ancestors above header, and stopping short reads as an ignored margin.
        var anc: ViewGroup? = header
        var hops = 0
        while (anc != null && hops < 12) {
            if (anc.clipChildren) {
                anc.clipChildren = false
                logOnce("info pane: unclipped ${anc.javaClass.simpleName}")
            }
            if (anc.clipToPadding) anc.clipToPadding = false
            anc = anc.parent as? ViewGroup
            hops++
        }
        var pane = infoTopPaneRef?.get()
        if (pane == null || pane.parent !== header) {
            pane = GlassView(header.context).apply {
                backdrop = infoListRef?.get()
                underlay = wallpaperUnderlay(header)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = header.dp(BLUR_DP * INFO_FADE_BLUR_BOOST)
                    refractionEnabled = true
                    bevelFraction = 0f
                    bevelThickness = 1f
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = header.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    cornerRadius = 0f
                    // Full-bleed header panel, same rule as the bands.
                    rimEnabled = false
                    tintColor = glassTintColor
                }
                setMaterialize(0f)
            }
            header.addView(
                pane, 0,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0),
            )
            infoTopPaneRef = WeakReference(pane)
            XposedBridge.log("[$TAG] info top pane inserted (lift=$lift)")
        }
        // Re-point the backdrop every sync, not only when null: after a revisit it is stale, not null, and a stale backdrop blurs nothing.
        infoListRef?.get()?.let { list -> if (pane.backdrop !== list) pane.backdrop = list }
        val want = header.height + lift
        val lp = pane.layoutParams as? FrameLayout.LayoutParams
        if (lp != null && (lp.height != want || lp.topMargin != -lift)) {
            lp.height = want
            lp.topMargin = -lift
            pane.layoutParams = lp
        }
        // The scroll scrubs the material itself, not an alpha over it: the glass thickens as the bar collapses.
        pane.setMaterialize(progress)
    }

    /* ── Contact info's section cards ───────────────────────────────────────────────────── */

    private val infoCardGlassTag = tagKey("wathemer-info-card-glass")
    private val infoCardStackTag = tagKey("wathemer-info-card-stack")
    private val infoCardClipTag = tagKey("wathemer-info-card-clip")
    private val infoCardAt = IntArray(2)
    private var infoHeaderPlaceholderId = 0
    private var infoParticipantsId = 0
    private var infoMemberSheetId = 0
    private var infoTailSeed = Float.NaN
    private var infoTailSeedBottom = 0f
    private var infoTailSeedL = 0f
    private var infoTailSeedR = 0f

    /** Half the gap between two stacked cards, applied at the top and bottom of each. */
    private const val INFO_CARD_GAP_DP = 5f

    private const val INFO_CARD_RADIUS_DP = 22f

    /** One pane draws every section card, a background per card goes stale under scroll; hosted via a canStack walk, trailing list items unioned into their own card by collectInfoCardRects. */
    private fun infoCardGlass(card: View) {
        val stack = card.parent as? ViewGroup ?: return
        // The list, not the stack: the trailing action rows are separate list items that only attach on scroll.
        val list = stack.parent as? ViewGroup ?: return
        // Held for the header pill, which blurs this list: the cards pass under that bar, not the wallpaper.
        infoListRef = WeakReference(list)
        // Tag the stack before any early return, on every attach: an untagged rebuilt stack unions into one page-wide slab.
        stack.setTag(infoCardStackTag, true)
        if (list.getTag(infoCardGlassTag) != null) return
        // Tag before the post: isAttachedToWindow is still false in our attach callback, and an untagged second attach queues a second pane.
        list.setTag(infoCardGlassTag, true)
        list.post {
            runCatching { insertInfoCardPane(list) }
                .onFailure { XposedBridge.log("[$TAG] insertInfoCardPane threw: $it") }
        }
    }

    private fun insertInfoCardPane(stack: ViewGroup) {
        var host = stack.parent as? ViewGroup
        while (host != null && !canStack(host)) host = host.parent as? ViewGroup
        if (host == null) {
            logOnce("info cards: no stacking ancestor above the card stack")
            return
        }
        val d = stack.resources.displayMetrics.density
        val pane = GlassBubblePane(host.context).apply {
            params = GlassParams(d).apply {
                blurRadius = stack.dp(BLUR_DP)
                cornerRadius = stack.dp(INFO_CARD_RADIUS_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = stack.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
            tint = { glassTintColor }
            // This window first, contentRef only as fallback: contentRef is home's decor and resolves the wrong screen here.
            backdrop = { bubbleBackdrop(stack) ?: contentRef?.get()?.let { c -> bubbleBackdrop(c) } }
            placement = { bubbleWpPlacement }
            dim = { 0f }
            rimColor = glassTint(BUBBLE_RIM_ALPHA)
            rimWidth = d
            collect = { out -> collectInfoCardRects(stack, out) }
            onGeometryChanged = { markWallpaperGeometryDirty() }
        }
        host.addView(
            pane, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        XposedBridge.log("[$TAG] info card glass inserted into ${host.javaClass.simpleName}")
    }

    private val memberPaneTag = tagKey("wathemer-member-pane")

    /** Inserted between the page and the sheet, the only layer that works; the sheet is resolved by id every frame so no reference goes stale. */
    private fun memberSheetPane(sheet: View) {
        val container = sheet.parent as? ViewGroup ?: return
        val host = container.parent as? ViewGroup ?: return
        if (!canStack(host)) {
            logOnce("member pane: ${host.javaClass.simpleName} does not stack")
            return
        }
        if (host.getTag(memberPaneTag) != null) return
        host.setTag(memberPaneTag, true)
        host.post {
            runCatching {
                val d = host.resources.displayMetrics.density
                val pane = GlassBubblePane(host.context).apply {
                    params = GlassParams(d).apply {
                        blurRadius = host.dp(BLUR_DP)
                        cornerRadius = host.dp(INFO_CARD_RADIUS_DP)
                        refractionEnabled = true
                        bevelFraction = BEVEL_FRACTION
                        depthRatio = DEPTH_RATIO
                        maxDisplacePx = host.dp(DISPLACE_DP)
                        fresnelStrength = 0.5f
                        tintColor = glassTintColor
                    }
                    tint = { glassTintColor }
                    // Local root first, `contentRef` second. See [insertInfoCardPane].
                    backdrop = { bubbleBackdrop(sheet) ?: contentRef?.get()?.let { c -> bubbleBackdrop(c) } }
                    placement = { bubbleWpPlacement }
                    dim = { 0f }
                    rimColor = glassTint(BUBBLE_RIM_ALPHA)
                    rimWidth = d
                    collect = { out -> collectMemberSheetRect(host, out) }
                    onGeometryChanged = { markWallpaperGeometryDirty() }
                }
                val at = host.indexOfChild(container).coerceAtLeast(0)
                host.addView(
                    pane, at,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                XposedBridge.log("[$TAG] member pane inserted at $at of ${host.javaClass.simpleName}")
            }.onFailure { XposedBridge.log("[$TAG] member pane insert threw: $it") }
        }
    }

    private fun collectMemberSheetRect(host: ViewGroup, out: RectList) {
        if (!host.hasWindowFocus()) return
        if (infoMemberSheetId == 0) return
        val sheet = host.rootView?.findViewById<View>(infoMemberSheetId) ?: return
        if (!sheet.isShown || sheet.height <= 0 || sheet.width <= 0) return
        val insetX = host.dp(CARD_INSET_DP)
        val insetY = host.dp(INFO_CARD_GAP_DP)
        sheet.getLocationOnScreen(infoCardAt)
        out.add(
            infoCardAt[0] + insetX,
            infoCardAt[1] + insetY,
            infoCardAt[0] + sheet.width - insetX,
            infoCardAt[1] + sheet.height - insetY,
        )
    }

    /** One rect per section card: header_placeholder merges into the card below, trailing action rows union into one; nothing is keyed on which cards exist. */
    private fun collectInfoCardRects(list: ViewGroup, out: RectList) {
        // Draw nothing without window focus: the contact popup is a second window, and lit glass burns straight through its scrim.
        if (!list.hasWindowFocus()) return
        val insetX = list.dp(CARD_INSET_DP)
        val insetY = list.dp(INFO_CARD_GAP_DP)
        var tailL = 0f
        var tailR = 0f
        var tailTop = infoTailSeed
        var tailBottom = infoTailSeedBottom
        infoTailSeed = Float.NaN
        if (!tailTop.isNaN()) {
            tailL = infoTailSeedL
            tailR = infoTailSeedR
        }
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i) ?: continue
            if (!child.isShown || child.height <= 0 || child.width <= 0) continue
            if (child.getTag(infoCardStackTag) != null) {
                collectStackCards(child as? ViewGroup ?: continue, out, insetX, insetY)
                continue
            }
            child.getLocationOnScreen(infoCardAt)
            if (tailTop.isNaN()) {
                tailTop = infoCardAt[1] + insetY
                tailL = infoCardAt[0] + insetX
                tailR = infoCardAt[0] + child.width - insetX
            }
            tailBottom = (infoCardAt[1] + child.height - insetY)
        }
        if (!tailTop.isNaN() && tailBottom > tailTop) out.add(tailL, tailTop, tailR, tailBottom)
    }

    private fun collectStackCards(stack: ViewGroup, out: RectList, insetX: Float, insetY: Float) {
        var mergedTop = Float.NaN
        for (i in 0 until stack.childCount) {
            val card = stack.getChildAt(i) ?: continue
            if (!card.isShown || card.height <= 0 || card.width <= 0) continue
            // An id-less container is a wrapper, not a card: group info nests cards a level deeper, so descend through it.
            if (card.id == View.NO_ID && card is ViewGroup && card.childCount > 0) {
                collectStackCards(card, out, insetX, insetY)
                continue
            }
            card.getLocationOnScreen(infoCardAt)
            // The participants block reads as one panel: from this card down, everything joins the tail union.
            if (infoParticipantsId != 0 && card.id == infoParticipantsId) {
                infoTailSeed = infoCardAt[1] + insetY
                infoTailSeedL = infoCardAt[0] + insetX
                infoTailSeedR = infoCardAt[0] + card.width - insetX
                // Bound the tail by its visible children, not the stack, which is the whole page; an oversize tail shows through the contact popup.
                var bottom = infoCardAt[1] + card.height
                for (j in i + 1 until stack.childCount) {
                    val rest = stack.getChildAt(j) ?: continue
                    if (!rest.isShown || rest.height <= 0 || rest.width <= 0) continue
                    rest.getLocationOnScreen(infoCardAt)
                    val b = infoCardAt[1] + rest.height
                    if (b > bottom) bottom = b
                }
                infoTailSeedBottom = bottom - insetY
                return
            }
            if (infoHeaderPlaceholderId != 0 && card.id == infoHeaderPlaceholderId) {
                // Hold its top for the next card, so the avatar and its name share one surface.
                mergedTop = infoCardAt[1] + insetY
                continue
            }
            val top = if (mergedTop.isNaN()) infoCardAt[1] + insetY else mergedTop
            mergedTop = Float.NaN
            roundToPane(card, insetX, insetY)
            out.add(
                infoCardAt[0] + insetX,
                top,
                infoCardAt[0] + card.width - insetX,
                infoCardAt[1] + card.height - insetY,
            )
        }
    }

    /** Clip the card to the pane's rect so edge-to-edge content like the media strip cannot overhang it. */
    private fun roundToPane(card: View, insetX: Float, insetY: Float) {
        val want = card.getTag(infoCardClipTag) as? PaneRound
        if (want == null) {
            val p = PaneRound(insetX, insetY, card.dp(INFO_CARD_RADIUS_DP))
            card.setTag(infoCardClipTag, p)
            card.outlineProvider = p
            card.clipToOutline = true
            card.invalidateOutline()
            return
        }
        // Runs every frame; the invalidate must stay gated on a size change or it self-sustains a redraw loop.
        if (want.needsRebuild(card.width, card.height)) card.invalidateOutline()
    }

    private class PaneRound(
        private val insetX: Float,
        private val insetY: Float,
        private val radius: Float,
    ) : ViewOutlineProvider() {
        private var builtW = -1
        private var builtH = -1

        /** True when the view's size has moved since the outline was last rebuilt for it. */
        fun needsRebuild(w: Int, h: Int): Boolean {
            if (w == builtW && h == builtH) return false
            builtW = w
            builtH = h
            return true
        }

        override fun getOutline(view: View, outline: Outline) {
            val l = insetX.toInt()
            val t = insetY.toInt()
            val r = view.width - l
            val b = view.height - t
            if (r <= l || b <= t) return
            outline.setRoundRect(l, t, r, b, radius)
        }
    }

    // Copy at 1/4, halve to 1/30, double back to 1/2. Deeper than the wallpaper's 1/20 for sharp text.
    // The shader samples NEAREST, so the rebuild size is the block size.
    /** Popups sample the composited screen. A View.draw copy would miss every RenderNode effect. */
    private const val POPUP_SNAP_COPY = 4
    private const val POPUP_SNAP_BLUR = 30
    private const val POPUP_SNAP_SMOOTH = 2

    /** Progressive halve then double, like FrostDrawable.shrinkOf but sized to the screen. */
    private fun smoothBlur(src: Bitmap, blurW: Int, outW: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= blurW && cur.height / 2 >= 1) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, (cur.height / 2).coerceAtLeast(1), true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        while (cur.width * 2 <= outW) {
            val next = Bitmap.createScaledBitmap(cur, cur.width * 2, cur.height * 2, true)
            if (cur !== src) cur.recycle()
            cur = next
        }
        return cur
    }

    @Volatile private var screenSnap: Bitmap? = null
    private val screenSnapPlace = Matrix()
    @Volatile private var screenSnapPending = false
    private val snapLoc = IntArray(2)

    /** Once per popup open. The listener is on the main thread, so no draw sees a half-swapped pair. */
    private fun requestScreenSnap(anchor: View) {
        if (screenSnapPending) return
        val win = activityOf(anchor)?.window ?: return
        val decor = win.decorView
        if (decor.width <= 0 || decor.height <= 0) return
        val w = (decor.width / POPUP_SNAP_COPY).coerceAtLeast(1)
        val h = (decor.height / POPUP_SNAP_COPY).coerceAtLeast(1)
        val dst = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return
        screenSnapPending = true
        runCatching {
            PixelCopy.request(
                win, dst,
                { res ->
                    screenSnapPending = false
                    if (res == PixelCopy.SUCCESS) {
                        decor.getLocationOnScreen(snapLoc)
                        val soft = runCatching {
                            smoothBlur(
                                dst,
                                (decor.width / POPUP_SNAP_BLUR).coerceAtLeast(1),
                                (decor.width / POPUP_SNAP_SMOOTH).coerceAtLeast(1),
                            )
                        }.getOrDefault(dst)
                        // Bitmap to screen, off the output bitmap; the blur changed its size.
                        screenSnapPlace.setScale(
                            decor.width.toFloat() / soft.width,
                            decor.height.toFloat() / soft.height,
                        )
                        screenSnapPlace.postTranslate(snapLoc[0].toFloat(), snapLoc[1].toFloat())
                        screenSnap = soft
                    } else {
                        logOnce("popup snapshot: PixelCopy returned $res, staying on the wallpaper")
                    }
                },
                Handler(Looper.getMainLooper()),
            )
        }.onFailure {
            screenSnapPending = false
            logOnce("popup snapshot threw, staying on the wallpaper: $it")
        }
    }

    /** GlassBubblePane, not panelGlass: cost stays flat as the menu grows, and one union rect avoids scalloped seams between items. */
    private fun popupRowGlass(content: View, radiusPx: Float?) {
        if (content.getTag(panelGlassTag) != null) return
        if (!content.isAttachedToWindow) return
        // The pane needs a stacking ancestor; a LinearLayout would sequence it in and consume a row.
        var host = content.parent as? ViewGroup
        while (host != null && !canStack(host)) host = host.parent as? ViewGroup
        if (host == null) {
            logOnce("popup row glass: no stacking ancestor")
            return
        }
        content.setTag(panelGlassTag, true)
        requestScreenSnap(content)
        val d = content.resources.displayMetrics.density
        val pane = GlassBubblePane(host.context).apply {
            params = GlassParams(d).apply {
                blurRadius = content.dp(BLUR_DP)
                cornerRadius = radiusPx ?: content.dp(PANEL_ROW_RADIUS_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = content.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
            tint = { glassTintColor }
            // The screen if PixelCopy gave us one, else the wallpaper. Both are screen-space.
            backdrop = { screenSnap ?: contentRef?.get()?.let { host -> bubbleBackdrop(host) } }
            placement = { if (screenSnap != null) screenSnapPlace else bubbleWpPlacement }
            dim = { 0f }
            rimColor = glassTint(BUBBLE_RIM_ALPHA)
            rimWidth = d
            collect = { out -> collectPopupRowRects(content, out) }
            onGeometryChanged = { markWallpaperGeometryDirty() }
        }
        // Sized to the content: a MATCH_PARENT child makes the WRAP_CONTENT window measure full screen and the WM shoves it to the top.
        host.addView(pane, 0, FrameLayout.LayoutParams(0, 0))
        val paneHost = host
        val paneRect = Rect()
        val syncPane = Runnable {
            if (content.width <= 0 || content.height <= 0) return@Runnable
            paneRect.set(0, 0, content.width, content.height)
            if (!runCatching { paneHost.offsetDescendantRectToMyCoords(content, paneRect) }.isSuccess) {
                return@Runnable
            }
            val lp = pane.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
            if (lp.width != paneRect.width() || lp.height != paneRect.height() ||
                lp.leftMargin != paneRect.left || lp.topMargin != paneRect.top
            ) {
                lp.width = paneRect.width()
                lp.height = paneRect.height()
                lp.leftMargin = paneRect.left
                lp.topMargin = paneRect.top
                pane.layoutParams = lp
            }
        }
        content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncPane.run() }
        syncPane.run()
        XposedBridge.log("[$TAG] popup row glass inserted into ${host.javaClass.simpleName}")
    }

    /** The view whose children are the menu rows; some menus wrap it, so descend through single-child groups. */
    private fun menuRowHost(content: View): ViewGroup? {
        var group = content as? ViewGroup ?: return null
        var hops = 0
        while (group.childCount == 1 && hops < 4) {
            val only = group.getChildAt(0) as? ViewGroup ?: break
            group = only
            hops++
        }
        return group
    }

    /** Seams are 1px foreground lines on one continuous glass: per-item rects scallop, and the row backgrounds were cleared. */
    private fun popupRowDividers(content: View) {
        val group = menuRowHost(content) ?: return
        val line = glassTint(POPUP_DIVIDER_ALPHA)
        val thickness = maxOf(1f, content.resources.displayMetrics.density * 0.5f)
        for (i in 0 until group.childCount - 1) {
            val row = group.getChildAt(i) ?: continue
            if (row.getTag(popupDividerTag) != null) continue
            row.setTag(popupDividerTag, true)
            row.foreground = object : Drawable() {
                private val p = Paint().apply { color = line }
                override fun draw(canvas: Canvas) {
                    val b = bounds
                    if (b.width() <= 0) return
                    canvas.drawRect(
                        b.left.toFloat(), b.bottom - thickness,
                        b.right.toFloat(), b.bottom.toFloat(), p,
                    )
                }
                override fun setAlpha(alpha: Int) { p.alpha = alpha }
                override fun setColorFilter(cf: ColorFilter?) { p.colorFilter = cf }
                @Suppress("OVERRIDE_DEPRECATION")
                override fun getOpacity() = PixelFormat.TRANSLUCENT
            }
        }
    }

    /** One union rect over the rows: per-item rects notch at the seams, and the content view runs taller than its rows. */
    private fun collectPopupRowRects(content: View, out: RectList) {
        val group = menuRowHost(content) ?: return
        if (content.width <= 0) return
        content.getLocationOnScreen(popupContentAt)
        val left = popupContentAt[0].toFloat()
        val right = left + content.width
        var top = Float.MAX_VALUE
        var bottom = Float.MIN_VALUE
        for (i in 0 until group.childCount) {
            val row = group.getChildAt(i) ?: continue
            if (row.height <= 0 || row.visibility != View.VISIBLE) continue
            row.getLocationOnScreen(popupRowAt)
            top = minOf(top, popupRowAt[1].toFloat())
            bottom = maxOf(bottom, (popupRowAt[1] + row.height).toFloat())
        }
        if (bottom - top <= 0f) return
        out.add(left, top, right, bottom)
    }

    private val popupDividerTag = tagKey("wathemer-popup-divider")
    private val popupContentAt = IntArray(2)
    private val popupRowAt = IntArray(2)

    private fun countDescendantsWithId(v: View, id: Int, depth: Int): Int {
        if (depth > 6) return 0
        var n = if (v.id == id) 1 else 0
        if (v is ViewGroup) for (i in 0 until v.childCount) {
            n += countDescendantsWithId(v.getChildAt(i) ?: continue, id, depth + 1)
        }
        return n
    }

    /** A live pane like the nav pill's; underlay only, nothing sits behind a sheet but the wallpaper. */
    private fun injectSheetGlass(v: View) {
        val sheet = v as? ViewGroup ?: return
        if (sheet.getTag(innerGlassTag) != null) return
        if (!sheet.isAttachedToWindow) return
        sheet.setTag(innerGlassTag, true)
        clearBg(sheet, "design_bottom_sheet")

        val glass = GlassView(sheet.context).apply {
            underlay = wallpaperUnderlayGlobal()
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = sheet.dp(BLUR_DP)
                // Follows the radius slider so sheets agree with every other surface.
                cornerRadius = sheet.dp(CARD_RADIUS_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = sheet.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
        }
        sheet.addView(glass, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        XposedBridge.log("[$TAG] sheet pane added (${sheet.width}x${sheet.height})")
    }

    /** Wallpaper views from the activity, not the caller's root: a sheet's root is its dialog DecorView. */
    private fun wallpaperUnderlayGlobal(): List<View> =
        contentRef?.get()?.let { wallpaperUnderlay(it) } ?: emptyList()

    /** A stacking carrier takes the live pane; the rest wear the tray's stamp material, a pane child pushes a LinearLayout's rows out. */
    private fun glassSelfSheet(v: View, name: String) {
        val sheet = v as? ViewGroup ?: return
        if (sheet.getTag(innerGlassTag) != null) return
        // One material per sheet: skip when a glassed Material wrapper already owns this subtree.
        var anc: View? = sheet.parent as? View
        var depth = 0
        while (anc != null && depth < 6) {
            if (anc.getTag(innerGlassTag) != null) return
            anc = anc.parent as? View
            depth++
        }
        // The camera and media viewer stay stock on purpose.
        val cls = activityOf(sheet)?.javaClass?.name ?: ""
        if (listOf(".camera", "mediaview").any { cls.contains(it) }) return
        // Tint-only frost on the viewers list: that window's backdrop is the status media, not the wallpaper.
        if (cls.contains(".status.")) {
            val detailsId = sheet.resources.waId("status_details_container", sheet.context.packageName)
            val details = if (detailsId != 0) sheet.findViewById<View>(detailsId) else null
            if (details == null || details.getTag(innerGlassTag) != null) return
            details.setTag(innerGlassTag, true)
            details.background = FrostDrawable(
                details, null, details.dp(CARD_RADIUS_DP), glassTint(CHIP_ALPHA),
                strokeWidth = details.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
                ignorePadding = true,
            )
            logOnce("self sheet frosted: status details")
            return
        }
        if (sheet is FrameLayout) {
            injectSheetGlass(sheet)
            logOnce("self sheet glassed live: $name")
            return
        }
        sheet.setTag(innerGlassTag, true)
        clearBg(sheet, name)
        sheet.background = FrostDrawable(
            sheet, bubbleBackdrop(contentRef?.get() ?: sheet),
            sheet.dp(CARD_RADIUS_DP), glassTintColor,
            strokeWidth = sheet.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
            ignorePadding = true,
        )
        logOnce("self sheet glassed: $name (${sheet.javaClass.simpleName})")
    }

    /** Frost one view from the wallpaper; idempotent, recycled chips come back bound to a different chip. */
    private fun frost(
        v: View,
        tintOverride: Int? = null,
        allowSquare: Boolean = false,
        ignorePadding: Boolean = false,
        /** Force the radius: half the height suits a pill, but an inner corner rounder than its outer one reads as a mistake. */
        radiusOverride: Float? = null,
    ) {
        if (v.width <= 0 || v.height <= 0) return
        // Taller than wide is a divider, not a chip, and the test must use the padded box or real chips like "All" get skipped.
        val padW = if (ignorePadding) v.width else v.width - v.paddingLeft - v.paddingRight
        val padH = if (ignorePadding) v.height else v.height - v.paddingTop - v.paddingBottom
        if (padW <= padH && !allowSquare) return

        // Half the padded height; a square accent takes the smaller side or the corners fight.
        val radius = radiusOverride ?: (minOf(padW, padH) / 2f).coerceAtLeast(1f)
        // Selection is isSelected on home chips but only in the content-desc on channel filters; check both.
        val descSelected = v.contentDescription?.toString()
            ?.contains("Not selected", ignoreCase = true) == false &&
            v.contentDescription?.toString()?.contains("selected", ignoreCase = true) == true
        val alpha = if (v.isSelected || v.isActivated || descSelected) {
            CHIP_ALPHA_SELECTED
        } else {
            CHIP_ALPHA
        }
        // glassTint, not white: the card behind follows the wallpaper and hardcoded white falls out of step.
        val tint = tintOverride ?: glassTint(alpha)

        val existing = v.getTag(frostTag) as? FrostDrawable
        if (existing != null && v.background === existing) {
            existing.setRadius(radius)
            existing.setTintColor(tint)   // recycled views arrive bound to a different chip
            return
        }
        // No bitmap: the card behind supplies the blur; passing one samples behind the card, not the chip.
        val d = FrostDrawable(
            v, null, radius, tint,
            strokeWidth = v.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
            ignorePadding = ignorePadding,
        )
        v.setTag(frostTag, d)
        v.background = d
    }

    /** Draft button ids, resolved at install; the WDSButton hook compares against them per style pass. */
    private var draftBtnIds = IntArray(0)
    private var draftSendBtnIds = IntArray(0)
    private var callBtnIds = IntArray(0)

    /** White content for the draft send button; the widget's own path turns it into paint colour and icon filter. */
    private val draftWhiteContent: ColorStateList by lazy {
        ColorStateList.valueOf(0xFFFFFFFF.toInt())
    }

    /** Recolours the shape inside WDSButton's own ripple; the mask layer keeps its colour or the ripple area shrinks. */
    private fun tintWdsShape(d: Drawable?, color: Int): Boolean {
        when (d) {
            null -> return false
            is RippleDrawable -> {
                for (i in 0 until d.numberOfLayers) {
                    if (d.getId(i) == android.R.id.mask) continue
                    if (tintWdsShape(runCatching { d.getDrawable(i) }.getOrNull(), color)) return true
                }
                return false
            }
            is InsetDrawable -> return tintWdsShape(d.drawable, color)
            is LayerDrawable -> {
                for (i in 0 until d.numberOfLayers) {
                    if (tintWdsShape(runCatching { d.getDrawable(i) }.getOrNull(), color)) return true
                }
                return false
            }
            is GradientDrawable -> { d.setColor(color); return true }
            is ShapeDrawable -> { d.paint.color = color; d.invalidateSelf(); return true }
            else -> return false
        }
    }

    /** Tuned by eye: less blur than the panes, a scrim between the menu tint and the panel scrim. */
    private const val TRAY_BLUR_DP = 10f
    private const val TRAY_SCRIM = 0x590E1418

    /** The pill is a live pane INSIDE the tray, so it rides transform animations no external pane can follow. */
    private fun frostReactionsTray(v: View) {
        val tray = v as? ViewGroup ?: return
        if (tray.getTag(frostTag) != null) return
        tray.setTag(frostTag, true)
        val containerId = tray.resources.waId("reactions_tray_container", tray.context.packageName)
        val container =
            (if (containerId != 0) tray.findViewById<View>(containerId) else null) as? FrameLayout
        val content = activityOf(tray)?.findViewById<ViewGroup>(android.R.id.content)
        if (container == null || content == null) {
            // No stacking child or no activity behind: the stamp fallback, so the tray never shows stock.
            val d = FrostDrawable(
                tray, bubbleBackdrop(tray), tray.dp(999f), glassTintColor,
                strokeWidth = tray.dp(1f), strokeColor = glassTint(BUBBLE_RIM_ALPHA),
            )
            tray.background = d
            logOnce("reactions tray frosted (stamp fallback)")
        } else {
            tray.background = null
            // The pill spans the padding box, wider than the container; the pane pokes past it to reach under the plus button.
            container.clipChildren = false
            val glass = GlassView(tray.context).apply {
                // The dialogs' recipe: the activity's content is another window, which is exactly what is behind a popup.
                backdrop = content
                underlay = wallpaperUnderlay(content)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = tray.dp(TRAY_BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = tray.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = TRAY_SCRIM
                }
            }
            container.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
            val sync = Runnable {
                if (tray.width <= 0 || tray.height <= 0) return@Runnable
                val w = tray.width - tray.paddingLeft - tray.paddingRight
                val h = tray.height - tray.paddingTop - tray.paddingBottom
                if (w <= 0 || h <= 0) return@Runnable
                val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return@Runnable
                if (lp.width != w || lp.height != h) {
                    lp.width = w
                    lp.height = h
                    lp.leftMargin = tray.paddingLeft - container.left
                    lp.topMargin = tray.paddingTop - container.top
                    glass.layoutParams = lp
                    glass.params.cornerRadius = h / 2f
                }
            }
            tray.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sync.run() }
            tray.post(sync)
            logOnce("reactions tray glassed live")
        }
        // The stock pill sits inside shadow padding; the wrappers' own fills go too.
        var p: View? = tray.parent as? View
        var hops = 0
        while (p != null && hops < 3) {
            if (p.background != null) clearBg(p, "reactions tray wrapper")
            p = p.parent as? View
            hops++
        }
    }

    /** True for containers that stack children: FrameLayout, or CoordinatorLayout matched by name to avoid the androidx dependency. */
    private fun canStack(vg: ViewGroup): Boolean =
        vg is FrameLayout || vg.javaClass.name.contains("CoordinatorLayout")

    // Never add ConstraintLayout to canStack: an unconstrained index-0 child breaks the Broadcast page's + FAB.

    private val slabSweepTag = tagKey("wathemer-slab-sweep")

    /** Divider bands to re-assert: WhatsApp re-shows them after our callback, and the flip fires no layout event. */
    private val slabRefs = mutableListOf<WeakReference<View>>()

    /** Hide one divider band, keeping its height so nothing above or below moves. */
    private fun hideSlab(v: View, what: String) {
        if (slabRefs.none { it.get() === v }) slabRefs.add(WeakReference(v))
        if (v.visibility != View.VISIBLE) return
        v.visibility = View.INVISIBLE
        logOnce("slab divider hidden: $what")
    }

    /** Cheap enough to run every frame: a handful of visibility compares. */
    private fun reassertSlabs() {
        val it = slabRefs.iterator()
        while (it.hasNext()) {
            val v = it.next().get()
            if (v == null) { it.remove(); continue }
            if (v.visibility == View.VISIBLE) v.visibility = View.INVISIBLE
        }
    }

    /** Unnamed divider bands matched by a deliberately narrow shape rule; every condition rules out something real on this page. */
    private fun hideSlabsByShape(root: ViewGroup) {
        if (root.width <= 0) return
        val maxH = root.dp(12f).toInt()
        val minW = root.width - root.dp(8f).toInt()
        fun walk(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i) ?: continue)
                return
            }
            if (v.javaClass != View::class.java) return
            if (v.background == null) return
            if (v.height <= 0 || v.height > maxH) return
            if (v.width < minW) return
            if (v.visibility != View.VISIBLE) return
            v.visibility = View.INVISIBLE
            logOnce("slab divider hidden by shape (${v.width}x${v.height})")
        }
        walk(root)
    }

    /** One per sequencing tab: the pager keeps neighbour pages realised, so shared refs would track the wrong list. */
    private class PageCard(val titleFallback: String, val navIndex: Int) {
        var host: WeakReference<ViewGroup>? = null
        var list: WeakReference<View>? = null
        var panel: WeakReference<GlassView>? = null
        var title: WeakReference<TextView>? = null
        /** WhatsApp's chrome clearance, captured once; -1 as the sentinel so a real paddingTop of 0 never reads as unknown. */
        var clearance = -1
    }

    private val communityCard = PageCard("Communities", 2)
    private val callsCard = PageCard("Calls", 3)
    private val communityCardTag = tagKey("wathemer-community-card")
    private val callsCardTag = tagKey("wathemer-calls-card")
    private val decorHookedClasses = Collections.synchronizedSet(HashSet<String>())

    /** Silence the decorations' paint but never remove them, the same objects supply the item offsets; the hook skips only this list since decoration classes are shared. */
    private fun killDecorations(list: View, listId: Int) {
        val count = runCatching {
            XposedHelpers.callMethod(list, "getItemDecorationCount") as Int
        }.getOrElse {
            XposedBridge.log("[$TAG] decoration count unreadable: $it")
            return
        }
        if (count <= 0) return

        val decorations = findDecorationList(list, count)
        if (decorations == null) {
            XposedBridge.log("[$TAG] $count decorations present but the backing list was not found")
            return
        }
        logOnce("$count decorations: ${decorations.joinToString { it?.javaClass?.name ?: "null" }}")
        for (d in decorations) {
            val cls = d?.javaClass ?: continue
            if (!decorHookedClasses.add(cls.name)) continue
            var hooked = 0
            // Walk the whole hierarchy: the paint methods live on superclasses, not the leaf class.
            var c: Class<*>? = cls
            while (c != null && c != Any::class.java) {
                for (m in c.declaredMethods) {
                    val p = m.parameterTypes
                    // Match by shape, not by name, so an obfuscated override is still caught.
                    if (p.isEmpty() || p[0] != Canvas::class.java) continue
                    XposedBridge.log("[$TAG]   paint candidate ${c.name}.${m.name}(${p.joinToString { it.simpleName }})")
                    runCatching {
                        XposedBridge.hookMethod(
                            m,
                            object : XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    // Check every argument: the obfuscated signatures put the RecyclerView at different positions.
                                    val hit = param.args.any { it is View && it.id == listId }
                                    if (!hit) return
                                    param.result = null   // skip the paint, keep the offsets
                                }
                            },
                        )
                        hooked++
                    }
                }
                c = c.superclass
            }
            XposedBridge.log("[$TAG] decoration ${cls.name} (super ${cls.superclass?.name}): $hooked paint methods silenced")
        }
    }

    /** The decorations list found by size and non-View elements; R8 may have renamed mItemDecorations. */
    private fun findDecorationList(list: View, count: Int): List<*>? {
        var c: Class<*>? = list.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                if (!List::class.java.isAssignableFrom(f.type)) continue
                val value = runCatching {
                    f.isAccessible = true
                    f.get(list) as? List<*>
                }.getOrNull() ?: continue
                if (value.size != count) continue
                if (value.any { it is View }) continue
                return value
            }
            c = c.superclass
        }
        return null
    }

    private val callActionsTag = tagKey("wathemer-call-actions")

    /** Pre-draw, not layout: syncPageCard settles over several passes, and moving the title never fires the list's layout listener. */
    private fun armPageCard(card: PageCard, v: View, tag: Int) {
        val host = v.parent as? ViewGroup ?: return
        (v as? ViewGroup)?.clipToPadding = false
        card.list = WeakReference(v)
        card.host = WeakReference(host)
        runCatching { injectPageCard(card, host) }
            .onFailure { XposedBridge.log("[$TAG] injectPageCard(${card.titleFallback}) threw: $it") }
        syncPageCard(card)
        if (v.getTag(tag) == null) {
            v.setTag(tag, true)
            v.viewTreeObserver.addOnPreDrawListener {
                runCatching { syncPageCard(card) }
                true
            }
        }
    }

    /** Frost the Calls tab's action discs, matched by shape since they carry no ids; scoped to this one row. */
    private fun frostCallActions(row: ViewGroup) {
        val min = row.dp(40f)
        fun walk(v: View) {
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i) ?: continue)
                return
            }
            if (v.background == null) return
            if (v.width < min || v.height < min) return
            if (abs(v.width - v.height) > v.width * 0.15f) return   // square only
            // ignorePadding: the ripple fills the whole disc, and an inset frost reads as a dot inside a hole.
            runCatching { frost(v, allowSquare = true, ignorePadding = true) }
        }
        walk(row)
    }

    private fun injectPageCard(card: PageCard, host: ViewGroup) {
        if (card.panel?.get()?.parent === host) return
        if (!host.isAttachedToWindow) return
        val glass = newCardGlass(host)
        // Index 0 draws behind the list; syncPageCard sets the margins once the heights are known.
        host.addView(glass, 0, ViewGroup.MarginLayoutParams(0, 0))
        card.panel = WeakReference(glass)

        // The title's margins cancel its height in this sequencing host, and it must go before the match_parent list or it measures 0px forever.
        val ctx = host.context
        val tv = TextView(ctx).apply {
            text = navLabel(card.navIndex) ?: card.titleFallback
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            typeface = Typeface.create(
                Typeface.DEFAULT, Typeface.BOLD,
            )
            setTextColor(primaryTextColor(ctx))
            includeFontPadding = false
            maxLines = 1
            setPadding(
                host.dp(16f).toInt(), host.dp(2f).toInt(),
                host.dp(16f).toInt(), host.dp(10f).toInt(),
            )
        }
        host.addView(
            tv, 1,
            ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.title = WeakReference(tv)
        XposedBridge.log("[$TAG] ${card.titleFallback} card + title '${tv.text}' inserted")
    }

    /** The host sequences, so the card's margins sum to zero height; clearance comes from the list's own paddingTop. */
    private fun syncPageCard(card: PageCard) {
        val host = card.host?.get() ?: return
        val glass = card.panel?.get() ?: return
        val list = card.list?.get() ?: return
        if (host.width <= 0 || host.height <= 0) return
        if (list.width <= 0 || list.height <= 0) return
        val inset = bottomInset
        if (inset <= 0) return                         // nav not floated yet

        val d = host.resources.displayMetrics.density
        val side = (CARD_INSET_DP * d).toInt()
        val gap = (CARD_GAP_DP * d).toInt()
        // WhatsApp's chrome clearance, captured from the list's paddingTop on the first pass, before we overwrite it.
        if (card.clearance < 0) {
            card.clearance = list.paddingTop
            XposedBridge.log("[$TAG] ${card.titleFallback} clearance = ${card.clearance}")
        }
        // Same arithmetic as syncUpdatesCard, so the two tabs' cards line up.
        val title = card.title?.get()
        val tlp = title?.layoutParams as? ViewGroup.MarginLayoutParams
        if (title != null && tlp != null) {
            if (tlp.topMargin != card.clearance) {
                tlp.topMargin = card.clearance
                title.layoutParams = tlp
                return          // re-enter once it has been laid out at its new place
            }
            if (title.height <= 0) return
            // Cancel its own height so the host's sequencing is untouched.
            val want = -(card.clearance + title.height)
            if (tlp.bottomMargin != want) {
                tlp.bottomMargin = want
                title.layoutParams = tlp
                return
            }
        }

        val top = card.clearance + (title?.height ?: 0) + gap
        val bottom = host.height - inset - gap
        val right = host.width - side
        if (bottom - top < gap || right - side < gap) return

        // The pad is the content's breathing room inside the border, equal on all four sides.
        val pad = (CARD_CONTENT_PAD_DP * d).toInt()
        val wasAtTop = !list.canScrollVertically(-1)
        if (list.paddingLeft != side + pad || list.paddingTop != top + pad ||
            list.paddingRight != side + pad || list.paddingBottom != host.height - bottom + pad
        ) {
            list.setPadding(side + pad, top + pad, side + pad, host.height - bottom + pad)
            if (wasAtTop) repinToTop(list)
        }

        val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val h = bottom - top
        if (lp.width != right - side || lp.height != h ||
            lp.leftMargin != side || lp.topMargin != top || lp.bottomMargin != -(top + h)
        ) {
            lp.width = right - side
            lp.height = h
            lp.leftMargin = side
            lp.topMargin = top
            lp.bottomMargin = -(top + h)               // net zero height in the LinearLayout
            glass.layoutParams = lp
            XposedBridge.log("[$TAG] ${card.titleFallback} card $side,$top-$right,$bottom")
        }

        // The pad comes back off so the clip lands on the border while the content sits inside it.
        val cl = list.paddingLeft - pad
        val ct = list.paddingTop - pad
        val cr = list.width - list.paddingRight + pad
        val cb = list.height - list.paddingBottom + pad
        val provider = list.outlineProvider as? CardOutline
        if (provider == null) {
            list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
            list.clipToOutline = true
            list.invalidateOutline()
        } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
            // Must stay gated on a real move: unconditional, these per-frame syncs keep an off-screen page invalidating every frame.
            list.invalidateOutline()
        }
    }

    private var channelHostRef: WeakReference<ViewGroup>? = null
    private var channelListRef: WeakReference<View>? = null
    private var channelPanelRef: WeakReference<GlassView>? = null
    private val channelCardTag = tagKey("wathemer-channel-card")
    private val channelListTag = tagKey("wathemer-channel-list")
    private val repinTag = tagKey("wathemer-repin")

    /** How many frames repinToTop watches for a mis-anchor before giving up. */
    private const val REPIN_FRAMES = 40

    private fun injectChannelCard(host: ViewGroup) {
        if (channelPanelRef?.get()?.parent === host) return
        if (!host.isAttachedToWindow) return
        val glass = newCardGlass(host)
        // MarginLayoutParams, not FrameLayout's: the CoordinatorLayout host converts via generateLayoutParams.
        host.addView(glass, 0, ViewGroup.MarginLayoutParams(0, 0))
        channelPanelRef = WeakReference(glass)
        XposedBridge.log("[$TAG] channel card inserted into ${host.javaClass.simpleName}")
    }

    /** Pre-draw driven: the appbar collapse runs no layout pass, so every write below must be cheap and no-op when nothing has moved. */
    private fun syncChannelCard() {
        val host = channelHostRef?.get() ?: return
        val glass = channelPanelRef?.get() ?: return
        val list = channelListRef?.get() ?: return
        if (host.width <= 0 || host.height <= 0) return
        if (list.width <= 0 || list.height <= 0) return

        val d = host.resources.displayMetrics.density
        val side = (CARD_INSET_DP * d).toInt()
        val gap = (CARD_GAP_DP * d).toInt()

        // Top follows the list, not the host: on Explore the appbar is the list's sibling, and list.top cannot be moved by scrolling.
        val top = list.top + gap
        val bottom = host.height - gap
        val right = host.width - side
        if (bottom - top < gap || right - side < gap) return

        // Every padding edge must stay invariant under the collapse, or setPadding fires a requestLayout per frame from pre-draw.
        // The pad on top is the content's breathing room inside the border, equal on all four sides.
        val pad = (CARD_CONTENT_PAD_DP * d).toInt()
        val padL = (side - list.left).coerceAtLeast(0) + pad
        val padR = (list.left + list.width - right).coerceAtLeast(0) + pad
        if (list.paddingLeft != padL || list.paddingTop != gap + pad ||
            list.paddingRight != padR || list.paddingBottom != gap + pad
        ) {
            list.setPadding(padL, gap + pad, padR, gap + pad)
        }

        val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.width != right - side || lp.height != bottom - top ||
            lp.leftMargin != side || lp.topMargin != top
        ) {
            lp.width = right - side
            lp.height = bottom - top
            lp.leftMargin = side
            lp.topMargin = top
            glass.layoutParams = lp
            // No log here: height tracks the collapse per frame, so any line in this branch prints per frame.
        }

        // Clip to the padding box intersected with the card's rect: the list can outgrow the card and leak under its bottom edge.
        // The pad comes back off first so the clip lands on the border while the content sits inside it.
        val cl = maxOf(list.paddingLeft - pad, side - list.left)
        val ct = list.paddingTop - pad
        val cr = minOf(list.width - list.paddingRight + pad, right - list.left)
        val cb = minOf(list.height - list.paddingBottom + pad, bottom - list.top)
        val provider = list.outlineProvider as? CardOutline
        if (provider == null) {
            list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
            list.clipToOutline = true
            list.invalidateOutline()
        } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
            // Must stay gated on a real move: unconditional, these per-frame syncs keep an off-screen page invalidating every frame.
            list.invalidateOutline()
        }
    }

    private var altToolbarRef: WeakReference<ViewGroup>? = null
    private var altToolbarGlassRef: WeakReference<GlassView>? = null

    /** The toolbar itself when the holder is not its wrapper; a single ref is safe, these screens never coexist. */
    private var altBarRef: WeakReference<ViewGroup>? = null

    private var callHeaderHostRef: WeakReference<ViewGroup>? = null
    private var callHeaderPanelRef: WeakReference<GlassView>? = null
    private val callHeaderTag = tagKey("wathemer-call-header")
    private val callLogDividerTag = tagKey("wathemer-call-log-divider")

    private fun injectCallHeaderPanel(host: ViewGroup) {
        if (callHeaderPanelRef?.get()?.parent === host) return
        if (!host.isAttachedToWindow) return
        val glass = newCardGlass(host)
        host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        callHeaderPanelRef = WeakReference(glass)
        XposedBridge.log("[$TAG] call header panel inserted")
    }

    /** The contact header's panel, sized to its collapsing toolbar; no clip, nothing scrolls under its edge. */
    private fun syncCallHeaderPanel() {
        val host = callHeaderHostRef?.get() ?: return
        val glass = callHeaderPanelRef?.get() ?: return
        if (host.width <= 0 || host.height <= 0) return

        val d = host.resources.displayMetrics.density
        val side = (CARD_INSET_DP * d).toInt()
        val gap = (CARD_GAP_DP * d).toInt()
        // Full height, side insets only: the header's own margins already supply the breathing room.
        val top = 0
        val bottom = host.height
        val right = host.width - side
        if (bottom - top < gap || right - side < gap) return

        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != right - side || lp.height != bottom - top ||
            lp.leftMargin != side || lp.topMargin != top
        ) {
            lp.width = right - side
            lp.height = bottom - top
            lp.leftMargin = side
            lp.topMargin = top
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
            XposedBridge.log("[$TAG] call header panel $side,$top-$right,$bottom")
        }
    }
    private val altToolbarTag = tagKey("wathemer-alt-toolbar")
    private val altRect = Rect()

    /** One pane behind a drilled-in toolbar's actions, unioned from the action container only: Back is an ImageButton too and would stretch it full width. */
    private fun syncAltToolbarGlass() {
        val holder = altToolbarRef?.get() ?: return
        if (holder.width <= 0 || holder.height <= 0) return

        // Use the named toolbar when given: the two-deep scan only holds when the holder is the toolbar's wrapper.
        var actions: ViewGroup? = null
        val explicitBar = altBarRef?.get()
        if (explicitBar != null) {
            // ActionMenuView by class first: the title container is also a populated ViewGroup and would otherwise take the pane.
            for (j in 0 until explicitBar.childCount) {
                val c = explicitBar.getChildAt(j) as? ViewGroup ?: continue
                if (c.javaClass.name.contains("ActionMenuView")) actions = c
            }
            if (actions == null) {
                for (j in 0 until explicitBar.childCount) {
                    val c = explicitBar.getChildAt(j) as? ViewGroup ?: continue
                    if (c.childCount > 0) actions = c
                }
            }
        } else {
            for (i in 0 until holder.childCount) {
                val tb = holder.getChildAt(i) as? ViewGroup ?: continue
                if (tb is GlassView) continue // our own panes are FrameLayouts, not toolbars
                for (j in 0 until tb.childCount) {
                    val c = tb.getChildAt(j) as? ViewGroup ?: continue
                    if (c.childCount > 0) actions = c
                }
            }
        }
        val group = actions ?: return

        // Asymmetric on purpose: the trailing pane grows by ALT_PANE_PAD_DP and the Back pill does not, yet both must land on the card's inset.
        val bar = group.parent as? ViewGroup ?: return
        val padStart = holder.dp(CARD_INSET_DP).toInt()
        val padEnd = holder.dp(CARD_INSET_DP + ALT_PANE_PAD_DP).toInt()
        if (bar.paddingLeft != padStart || bar.paddingRight != padEnd) {
            bar.setPadding(padStart, bar.paddingTop, padEnd, bar.paddingBottom)
        }

        // Scale the glyphs only, layout bounds keep the touch targets; scoped to bar or the community photo would shrink too.
        for (j in 0 until bar.childCount) {
            val c = bar.getChildAt(j) ?: continue
            if (c is ViewGroup) {
                for (k in 0 until c.childCount) shrinkGlyph(c.getChildAt(k))
            } else {
                shrinkGlyph(c)
            }
        }

        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        var n = 0
        for (i in 0 until group.childCount) {
            val a = group.getChildAt(i) ?: continue
            // isShown, not visibility: the bleed guard hides the Toolbar while its ActionMenuView stays VISIBLE inside it.
            if (a.width <= 0 || a.height <= 0 || !a.isShown) continue
            val rect = altRect
            rect.set(0, 0, a.width, a.height)
            if (!runCatching { holder.offsetDescendantRectToMyCoords(a, rect) }.isSuccess) continue
            l = minOf(l, rect.left); t = minOf(t, rect.top)
            r = maxOf(r, rect.right); b = maxOf(b, rect.bottom)
            n++
        }
        if (n == 0 || r <= l || b <= t) return
        val pad = holder.dp(ALT_PANE_PAD_DP).toInt()
        l = (l - pad).coerceAtLeast(0)
        r = (r + pad).coerceAtMost(holder.width)

        // Force the icons light: only some widget types pick up the toolbar's icon colour.
        for (i in 0 until group.childCount) {
            val a = group.getChildAt(i) ?: continue
            if (a.getTag(altIconTag) != null) continue
            a.setTag(altIconTag, true)
            val white = ColorStateList.valueOf(Color.WHITE)
            when {
                a is ImageView -> a.imageTintList = white
                // ActionMenuItemView draws its icon as a compound drawable; imageTintList never reaches it.
                a is TextView -> a.compoundDrawableTintList = white
                else -> XposedBridge.log(
                    "[$TAG] alt toolbar action ${a.javaClass.simpleName} has no known icon slot"
                )
            }
        }

        // t/b, not the padded l/r: the Back pill matches this pane's thickness and vertical position.
        syncAltLeadingGlass(holder, bar, group, t, b)

        var glass = altToolbarGlassRef?.get()
        if (glass == null || glass.parent !== holder) {
            glass = GlassView(holder.context).apply {
                underlay = wallpaperUnderlay(holder)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = holder.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = holder.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTintColor
                }
            }
            holder.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
            altToolbarGlassRef = WeakReference(glass)
            XposedBridge.log("[$TAG] alt toolbar pane inserted ($n actions)")
        }
        // A tripwire, not a repair: a canStack host may hand back non-FrameLayout params, and a bare return would hide the pane silently.
        val lp = glass.layoutParams as? FrameLayout.LayoutParams
            ?: run {
                logOnce("alt toolbar pane: host gave ${glass.layoutParams?.javaClass?.simpleName} " +
                    "instead of FrameLayout.LayoutParams; pane cannot be sized")
                return
            }
        if (lp.width != r - l || lp.height != b - t ||
            lp.leftMargin != l - holder.paddingLeft || lp.topMargin != t - holder.paddingTop
        ) {
            lp.width = r - l
            lp.height = b - t
            lp.leftMargin = l - holder.paddingLeft
            lp.topMargin = t - holder.paddingTop
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
            glass.params.cornerRadius = (b - t) / 2f
            XposedBridge.log("[$TAG] alt toolbar pane $l,$t-$r,$b")
        }
    }

    /** Icons only: a TextView without a compound drawable is the title, and scaling that would be silent damage. */
    private fun shrinkGlyph(v: View?) {
        if (v == null) return
        val isIcon = when (v) {
            is ImageView -> true
            is TextView -> v.compoundDrawables.any { it != null }
            else -> false
        }
        if (!isIcon) return
        if (v.scaleX == ALT_ICON_SCALE && v.scaleY == ALT_ICON_SCALE) return
        v.scaleX = ALT_ICON_SCALE
        v.scaleY = ALT_ICON_SCALE
    }

    private val altIconTag = tagKey("wathemer-alt-icon")
    private var altLeadingGlassRef: WeakReference<GlassView>? = null

    /** A round pill behind Back only, sized from the trailing pane's own numbers so the two always match. */
    private fun syncAltLeadingGlass(
        holder: ViewGroup,
        bar: ViewGroup,
        actions: ViewGroup,
        refTop: Int,
        refBottom: Int,
    ) {
        // A few px under the trailing pane: a circle reads larger than a pill of equal height.
        val side = refBottom - refTop - BACK_PILL_TRIM_PX
        if (side <= 0) return

        // Leftmost ImageView inside bar, not holder: the community photo is an ImageView too, and child order is WhatsApp's.
        var backLeft = Int.MAX_VALUE
        var backRight = Int.MIN_VALUE
        var backView: View? = null
        for (j in 0 until bar.childCount) {
            val a = bar.getChildAt(j) ?: continue
            if (a === actions) continue
            if (a !is ImageView) continue
            if (a.width <= 0 || a.height <= 0 || a.visibility != View.VISIBLE) continue
            val rect = altRect
            rect.set(0, 0, a.width, a.height)
            if (!runCatching { holder.offsetDescendantRectToMyCoords(a, rect) }.isSuccess) continue
            if (rect.left < backLeft) {
                backLeft = rect.left
                backRight = rect.right
                backView = a
            }
        }
        if (backRight <= backLeft) return

        // Centred on Back, so the arrow sits mid-circle however wide its touch target is.
        val cx = (backLeft + backRight) / 2
        val l = (cx - side / 2).coerceAtLeast(0)
        val t = refTop + BACK_PILL_TRIM_PX / 2      // keep it centred on the trailing pane
        val r = l + side
        val b = t + side

        var glass = altLeadingGlassRef?.get()
        if (glass == null || glass.parent !== holder) {
            glass = GlassView(holder.context).apply {
                underlay = wallpaperUnderlay(holder)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = holder.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = holder.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTintColor
                }
            }
            holder.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
            altLeadingGlassRef = WeakReference(glass)
            XposedBridge.log("[$TAG] alt toolbar back pill inserted (${side}px)")
        }
        if (glass.pressSource !== backView) glass.pressSource = backView
        // Same tripwire as [syncAltToolbarGlass]. See the note there.
        val lp = glass.layoutParams as? FrameLayout.LayoutParams
            ?: run {
                logOnce("alt toolbar back pill: host gave ${glass.layoutParams?.javaClass?.simpleName} " +
                    "instead of FrameLayout.LayoutParams; pane cannot be sized")
                return
            }
        if (lp.width != r - l || lp.height != b - t ||
            lp.leftMargin != l - holder.paddingLeft || lp.topMargin != t - holder.paddingTop
        ) {
            lp.width = r - l
            lp.height = b - t
            lp.leftMargin = l - holder.paddingLeft
            lp.topMargin = t - holder.paddingTop
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
            glass.params.cornerRadius = side / 2f
            XposedBridge.log("[$TAG] alt toolbar back pill $l,$t-$r,$b")
        }
    }

    // ── The conversation screen ────────────────────────────────────────────────────────
    // Chrome only: pills behind the toolbar and compose row; bubbles are not panes, installBubbleGlass reskins them.

    private var convHolderRef: WeakReference<ViewGroup>? = null
    private var convCoordRef: WeakReference<ViewGroup>? = null
    private var convFooterRef: WeakReference<ViewGroup>? = null
    private val convToolbarTag = tagKey("wathemer-conv-toolbar")
    private val convFooterTag = tagKey("wathemer-conv-footer")
    private val convFloatTag = tagKey("wathemer-conv-float")
    private val convPanes = WeakHashMap<View, GlassView>()
    private val convRect = Rect()

    /** Inset of the conversation pills from the screen edges. */
    private const val CONV_PILL_INSET_DP = 6f

    /** WhatsApp packs the toolbar targets edge to edge; trimming the diameter is what puts air between the pills. */
    private const val CONV_PILL_TRIM_DP = 4f

    /** Deliberately more than fits: both clamps engage and the capsule fills the header band, no slivers peek around it. */
    private const val CONV_CAPSULE_GROW_DP = 8f

    /** Resting clearance from the chrome; clipToPadding = false still lets rows scroll into the band. */
    private const val CONV_CHROME_GAP_DP = 6f

    /** Negative bottom margin grows the coordinator to y=0; translationZ is not optional, messages would paint over the toolbar. */
    private fun floatConvToolbar(holder: ViewGroup) {
        if (holder.getTag(convFloatTag) != null) return
        holder.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or_: Int, ob: Int,
            ) {
                val h = b - t
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                if (h <= 0 || lp.bottomMargin < 0) return          // already floated
                v.removeOnLayoutChangeListener(this)
                v.setTag(convFloatTag, true)
                lp.bottomMargin = -h
                v.layoutParams = lp
                if (v.translationZ < 1f) v.translationZ = 1f
                XposedBridge.log("[$TAG] conversation toolbar floated (h=$h, bottomMargin=-$h)")
                runCatching { syncConvToolbar() }
            }
        })
        holder.requestLayout()
    }

    /** RelativeLayout here, so the negative margin goes on the list host; the footer draws later and needs no lift. */
    private fun floatConvFooter(footer: ViewGroup) {
        if (footer.getTag(convFloatTag) != null) return
        val parent = footer.parent as? ViewGroup ?: return
        // Looked up in the listener: at attach every child still has height 0 and an early return would skip the float.
        footer.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int, ol: Int, ot: Int, or_: Int, ob: Int,
            ) {
                val h = b - t
                if (h <= 0) return
                val listHost = convListHost(parent) ?: return
                val lp = listHost.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                if (lp.bottomMargin < 0) return                    // already floated
                v.removeOnLayoutChangeListener(this)
                v.setTag(convFloatTag, true)
                val wasAtBottom = !listHost.canScrollVertically(1)
                lp.bottomMargin = -h
                listHost.layoutParams = lp
                XposedBridge.log(
                    "[$TAG] conversation footer floated (h=$h, list host bottomMargin=-$h)",
                )
                runCatching { padConvList(listHost, h) }
                // Only correct an offset we caused, and only when the list was pinned; from onPreDraw, see repinToBottom.
                if (wasAtBottom) {
                    (0 until listHost.childCount)
                        .mapNotNull { listHost.getChildAt(it) as? AbsListView }
                        .firstOrNull()
                        ?.let { repinToBottom(it) }
                }
                runCatching { syncConvFooter() }
            }
        })
        footer.requestLayout()
    }

    /** The row dissolve runs from the list's top edge to the pill's bottom plus this; bigger is gentler. */
    private const val CONV_ROW_FADE_DP = 56f

    private var convRowFadeLen = 0f

    private var convBandRef: WeakReference<GlassView>? = null

    /** The list's own parent: a plain FrameLayout, and the fade pane's host. */
    private var convListHostRef: WeakReference<ViewGroup>? = null

    /** The tints add where band and pill stack, so they split TINT_ALPHA: the sum must equal it exactly, including at 0. */
    private fun convBandAlpha(): Int = (TINT_ALPHA / 2).coerceAtLeast(0)

    private fun convPillAlpha(): Int =
        if (convBandRef?.get()?.parent != null) {
            (TINT_ALPHA - convBandAlpha()).coerceAtLeast(0)
        } else {
            TINT_ALPHA
        }

    /** Full-width band fading below the toolbar pill. Wallpaper only: a backdrop would flicker as messages scroll under it. */
    private fun syncConvBand(holder: ViewGroup) {
        if (holder.height <= 0) return
        val under = wallpaperUnderlay(holder)
        // No wallpaper means nothing to transmit, and a tint-only band would just be a grey wash.
        if (under.isEmpty()) return
        val res = holder.resources
        val abrId = res.waId("action_bar_root", holder.context.packageName)
        if (abrId == 0) return
        val abr = holder.rootView?.findViewById<View>(abrId) as? FrameLayout ?: return

        // Solid to the pill's bottom, not the holder's; only the capsule pane knows where the pill ends.
        val at = IntArray(2)
        val abrAt = IntArray(2)
        holder.getLocationOnScreen(at)
        abr.getLocationOnScreen(abrAt)
        val pillBottom = convPanes.values
            .filter { it.parent === holder }
            .mapNotNull { it.layoutParams as? FrameLayout.LayoutParams }
            .maxOfOrNull { it.topMargin + it.height }
            ?: holder.height
        val solid = (at[1] - abrAt[1]) + pillBottom
        if (solid <= 0) return
        // One pane only: two abutting panes cannot be seamless, each blur kernel is clipped to its own capture.
        // Hard stop on the pill's bottom edge; the SDF is expanded past it so the cut has no rim of its own.
        val total = solid

        var band = convBandRef?.get()
        if (band == null || band.parent !== abr) {
            band = GlassView(abr.context).apply {
                underlay = under
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = holder.dp(BLUR_DP)
                    refractionEnabled = true
                    // With the SDF expanded there is no bevel to size; a 1px nominal one keeps depth and displacement at zero.
                    bevelFraction = 0f
                    bevelThickness = 1f
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = holder.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    cornerRadius = 0f
                    // Full-bleed panel; a rim here would outline the screen.
                    rimEnabled = false
                    edgeExpandLeft = 8f
                    edgeExpandTop = 8f
                    edgeExpandRight = 8f
                    edgeExpandBottom = 8f
                }
            }
            // Above the wallpaper and its dim; if they are not abr's children the band hides behind them, hence the log.
            var insertAt = 0
            for (i in 0 until abr.childCount) {
                val c = abr.getChildAt(i)
                if (c === under.firstOrNull() || (under.size > 1 && c === under[1])) insertAt = i + 1
            }
            if (insertAt == 0) {
                XposedBridge.log(
                    "[$TAG] WARN: wallpaper views are not children of action_bar_root; the band " +
                        "will be behind them and invisible",
                )
            }
            abr.addView(
                band, insertAt,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, total).apply {
                    gravity = Gravity.TOP
                },
            )
            convBandRef = WeakReference(band)
            XposedBridge.log(
                "[$TAG] conversation band inserted at index $insertAt " +
                    "(solid=${solid}px tint=${convBandAlpha()})",
            )
        }
        band.params.tintColor = glassTint(convBandAlpha())
        val lp = band.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.height != total) {
            lp.height = total
            band.layoutParams = lp
            XposedBridge.log("[$TAG] conversation band resized to ${total}px")
        }
        convRowFadeLen = (solid - (convListHostRef?.get()?.let { h ->
            val a = IntArray(2); h.getLocationOnScreen(a); a[1]
        } ?: 0)) + holder.dp(CONV_ROW_FADE_DP)
        syncRowFade()
    }

    /** The framework's own fading edge; the ramp is also handed to GlassBubblePane so bubble glass fades with the text. */
    private fun syncRowFade() {
        val list = convListRef?.get() ?: return
        val len = convRowFadeLen
        if (len <= 0f) return
        if (!list.isVerticalFadingEdgeEnabled) {
            list.isVerticalFadingEdgeEnabled = true
            XposedBridge.log("[$TAG] row fade armed (${len.toInt()}px from the list's top edge)")
        }
        if (list.verticalFadingEdgeLength != len.toInt()) list.setFadingEdgeLength(len.toInt())
        val at = IntArray(2)
        list.getLocationOnScreen(at)
        convBubblePaneRef?.get()?.let { pane ->
            pane.fadeLineScreenY = at[1].toFloat()
            pane.fadeLen = len
        }
    }

    private val pillPadTag = tagKey("wathemer-pill-pad")
    private val mentionPaneTag = tagKey("wathemer-mention-pane")
    private val mentionPreDrawTag = tagKey("wathemer-mention-predraw")

    /** The mention list blurs the conversation behind it; its own painted fill goes. */
    private fun syncMentionPane(host: FrameLayout) {
        var pane = host.getTag(mentionPaneTag) as? GlassView
        if (pane == null || pane.parent !== host) {
            pane = GlassView(host.context).apply {
                backdrop = convCoordRef?.get()
                underlay = wallpaperUnderlay(host)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = host.dp(BLUR_DP)
                    cornerRadius = host.dp(16f)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = host.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTint(convPillAlpha())
                }
            }
            host.addView(
                pane, 0,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            host.setTag(mentionPaneTag, pane)
            logOnce("mention pane inserted")
        } else if (pane.backdrop == null) {
            pane.backdrop = convCoordRef?.get()
        }
        // WhatsApp repaints the picker's fill per data pass with no layout to catch, so pre-draw it away.
        if (host.getTag(mentionPreDrawTag) == null) {
            host.setTag(mentionPreDrawTag, true)
            host.viewTreeObserver.addOnPreDrawListener {
                for (i in 0 until host.childCount) {
                    val c = host.getChildAt(i)
                    if (c !is GlassView && c.background != null) c.background = null
                }
                true
            }
        }
    }

    /** Zero every header pane but the one taking the capsule now, or two pills stack. */
    private fun collapseHeaderPanes(holder: ViewGroup, keep: View) {
        for ((owner, g) in convPanes.entries) {
            if (g.parent !== holder || owner === keep) continue
            val glp = g.layoutParams ?: continue
            if (glp.width != 0 || glp.height != 0) {
                glp.width = 0
                glp.height = 0
                g.layoutParams = glp
            }
        }
    }

    /** One capsule spanning the toolbar; every rect is re-derived, never stored, custom_view resizes when the menu inflates. */
    private fun syncConvToolbar() {
        val holder = convHolderRef?.get() ?: return
        if (holder.width <= 0 || holder.height <= 0) return
        val toolbar = (0 until holder.childCount)
            .mapNotNull { holder.getChildAt(it) as? ViewGroup }
            .firstOrNull { it !is GlassView && it.javaClass.name.contains("Toolbar") } ?: return

        // In search the holder swaps in its own bar; the capsule and the band's bottom edge follow it.
        val barId = holder.resources.waId("search_view_toolbar", holder.context.packageName)
        val searchBar = (if (barId != 0) holder.findViewById<View>(barId) else null)
            ?.takeIf { it.isShown && it.width > 0 && it.height > 0 }
        if (searchBar != null) {
            collapseHeaderPanes(holder, searchBar)
            val r = Rect(0, 0, searchBar.width, searchBar.height)
            if (runCatching { holder.offsetDescendantRectToMyCoords(searchBar, r) }.isSuccess) {
                clearBg(searchBar, "conversation search bar")
                pill(holder, searchBar, r.left, r.top, r.right, r.bottom)
            }
            return
        }
        // In selection the bar lives in action_bar_root over this band; leave the capsule or that bar loses its pill.
        if (!toolbar.isShown) return
        collapseHeaderPanes(holder, toolbar)

        // WhatsApp's own bar fill has to go, or the pills sit on a slab instead of on the wallpaper.
        clearBg(toolbar, "conversation toolbar")
        // The 1dp line is WDSToolbar.onDraw's, handled by ensureToolbarDividerHook; cleared per-id, never blanket.
        if (toolbar.foreground != null) {
            logOnce("conversation toolbar foreground cleared: ${toolbar.foreground?.javaClass?.name}")
            toolbar.foreground = null
        }

        val d = holder.resources.displayMetrics.density
        val inset = (CONV_PILL_INSET_DP * d).toInt()

        // Found structurally: the ActionMenuView has no id at all.
        var custom: ViewGroup? = null
        var actions: ViewGroup? = null
        for (i in 0 until toolbar.childCount) {
            val c = toolbar.getChildAt(i) as? ViewGroup ?: continue
            if (c.javaClass.name.contains("ActionMenuView")) actions = c
            else if (c.childCount > 0) custom = c
        }

        val backId = holder.resources.waId("whatsapp_toolbar_home", holder.context.packageName)
        val back = if (backId != 0) custom?.findViewById<View>(backId) else null

        // The band comes from the Back button, not the bar: the elements do not share a height.
        var refTop = -1
        var refBottom = -1
        if (back != null && back.width > 0) {
            rectInHolder(holder, back)
            refTop = convRect.top
            refBottom = convRect.bottom
        } else if (actions != null && actions.childCount > 0) {
            val a = actions.getChildAt(0)
            if (a != null && a.height > 0) {
                rectInHolder(holder, a)
                refTop = convRect.top
                refBottom = convRect.bottom
            }
        }
        if (refBottom <= refTop) return
        val trim = (CONV_PILL_TRIM_DP * d).toInt()
        // The holder clamp matters: clipChildren=true silently cuts an oversize capsule's rounded ends.
        val grow = (CONV_CAPSULE_GROW_DP * d).toInt()
        val pt = (refTop + trim / 2 - grow).coerceAtLeast(0)
        val pb = (refBottom - trim / 2 + grow).coerceAtMost(holder.height)
        val band = (pb - pt).coerceAtLeast(1)

        // ── One capsule ───────────────────────────────────────────────────────────────
        // WhatsApp's own layout spaces the controls inside it, so none of the per-pill centring defects can recur.
        val l = inset
        val r = holder.width - inset
        if (r - l > band) pill(holder, toolbar, l, pt, r, pb)
    }

    // ── Chat bubbles as glass ──────────────────────────────────────────────────────────
    // The one surface that cannot be a pane: rows carry no ids and nothing hosts a child; see GlassBubbleDrawable.

    /** Bubble corner radius. Independent of the card radius slider; bubbles want their own. */
    private const val BUBBLE_RADIUS_DP = 16f

    /** Merged continuations keep this much corner; the shader's own corner smoothing softens it further. */
    private const val BUBBLE_FLAT_RADIUS_DP = 4f
    private var bubbleMergeOn = false

    /* Bubbles take the panes' tint. */

    /** Follows the slider so the user's control still works; 5 heavier because a bubble transmits only wallpaper. */
    private const val BUBBLE_TINT_BOOST = 5

    /** The bubble tint: the slider's value plus [BUBBLE_TINT_BOOST], clamped to a legal alpha. */
    private val bubbleTintColor: Int
        get() = glassTint((TINT_ALPHA + BUBBLE_TINT_BOOST).coerceIn(0, 255))

    /** Fallback rim, drawn only when AGSL is unavailable; otherwise the light pass draws the edge. */
    private const val BUBBLE_RIM_ALPHA = 46

    private var bubbleBlurred: Bitmap? = null
    private var bubbleBlurredResolved = false
    private var bubbleDim = 0f
    private var bubbleDimFolded = false
    private var bubbleWpPlacement: Matrix? = null

    /** Set when any wallpaper-transmitting surface resizes; costs a matrix rebuild, not a bitmap rebuild. */
    @Volatile private var bubbleWpDirty = true

    /** Set only through [markWallpaperGeometryDirty], never alone; cleared only where the selection placement is written. */
    @Volatile private var selectionWpDirty = true

    /** Every wallpaper-geometry change makes both cached placements stale. */
    private fun markWallpaperGeometryDirty() {
        bubbleWpDirty = true
        selectionWpDirty = true
    }

    /** Shrunk to original to imageMatrix to screen; one definition so the first resolve and re-derives cannot drift. */
    private fun wallpaperPlacement(
        img: ImageView,
        src: Bitmap,
        shrunk: Bitmap,
    ): Matrix {
        val m = Matrix()
        m.setScale(src.width.toFloat() / shrunk.width, src.height.toFloat() / shrunk.height)
        m.postConcat(img.imageMatrix)
        val at = IntArray(2)
        img.getLocationOnScreen(at)
        m.postTranslate(at[0].toFloat(), at[1].toFloat())
        return m
    }

    /** Built once, resolved from the live view so the flag cannot latch before any wallpaper view exists. */
    private fun bubbleBackdrop(host: View): Bitmap? {
        // The bitmap latches, the placement does not: a latched matrix kept the old scale after rotation.
        if (bubbleBlurredResolved && !bubbleWpDirty) return bubbleBlurred
        if (bubbleBlurredResolved) {
            // Geometry only: re-derive the mapping from the live ImageView and keep the bitmap.
            val img = host.rootView?.findViewWithTag<View>("wt_wallpaper") as? ImageView
            val shrunk = bubbleBlurred
            val src = (img?.drawable as? BitmapDrawable)?.bitmap
            if (img != null && shrunk != null && src != null && img.width > 0) {
                bubbleWpPlacement = wallpaperPlacement(img, src, shrunk)
                bubbleWpDirty = false
                XposedBridge.log("[$TAG] bubble backdrop placement re-derived after a geometry change")
            }
            return bubbleBlurred
        }
        val root = host.rootView
        val img = root.findViewWithTag<View>("wt_wallpaper") as? ImageView ?: return null
        val src = (img.drawable as? BitmapDrawable)?.bitmap ?: return null
        bubbleDim = (root.findViewWithTag<View>("wt_wallpaper_dim")?.alpha ?: 0f).coerceIn(0f, 1f)
        bubbleBlurred = FrostDrawable.shrinkOf(src)
        bubbleBlurredResolved = bubbleBlurred != null
        // Fold the dim in so refraction matches the screen; guarded, the shrink can hand back WhatsApp's own bitmap.
        bubbleDimFolded = false
        bubbleBlurred?.let { shrunk ->
            if (bubbleDim > 0f && shrunk !== src && shrunk.isMutable) {
                runCatching {
                    Canvas(shrunk).drawColor(
                        ((bubbleDim * 255f).toInt().coerceIn(0, 255) shl 24),
                        PorterDuff.Mode.SRC_OVER,
                    )
                    bubbleDimFolded = true
                }
            }
        }
        // See [wallpaperPlacement] for the three steps this matrix is built from.
        bubbleBlurred?.let { shrunk ->
            val m = wallpaperPlacement(img, src, shrunk)
            bubbleWpPlacement = m
            bubbleWpDirty = false
            XposedBridge.log(
                "[$TAG] bubble backdrop placement: iv=${img.width}x${img.height} " +
                    "src=${src.width}x${src.height} shrunk=${shrunk.width}x${shrunk.height} m=$m",
            )
        }
        if (bubbleBlurredResolved) {
            XposedBridge.log(
                "[$TAG] bubble backdrop built ${bubbleBlurred?.width}x${bubbleBlurred?.height} " +
                    "dim=$bubbleDim folded=$bubbleDimFolded",
            )
        }
        return bubbleBlurred
    }

    /** The panes' recipe, field for field; fresh per drawable, a shared GlassParams is last-writer-wins on bevelThickness. */
    private fun bubbleParams(density: Float) = GlassParams(density).apply {
        refractionEnabled = true
        bevelFraction = BEVEL_FRACTION
        depthRatio = DEPTH_RATIO
        maxDisplacePx = DISPLACE_DP * density
        fresnelStrength = 0.5f
        cornerRadius = BUBBLE_RADIUS_DP * density
        // tintColor deliberately unset: it resolves after WhatsApp asks for this drawable, so a provider supplies it.
    }

    /** PRIORITY_HIGHEST so this result wins over BubbleShapes' hook; the user's bubble colours are deliberately ignored while glass is on. */
    private fun installBubbleGlass(app: Application) {
        val cl = app.classLoader
        // Open the bridge ourselves: never rely on BubbleColors or BubbleShapes to have opened it.
        val dex = Deobfuscator
        if (!dex.ensureBridge(app)) {
            XposedBridge.log("[$TAG] DexKit bridge unavailable; no bubble glass")
            return
        }
        val method = dex.loadBubbleDrawableMethod(cl) ?: run {
            XposedBridge.log("[$TAG] bubble drawable method unresolved; no bubble glass")
            return
        }
        // Persist the lookup so the next launch is a cache hit.
        runCatching { dex.saveCache() }
        var left = 0
        var right = 0
        runCatching {
            val p = XSharedPreferences(
                BuildConfig.APPLICATION_ID,
                Prefs.FILE,
            )
            p.reload()
            left = p.getInt(Prefs.BUBBLE_LEFT_BG, 0)
            right = p.getInt(Prefs.BUBBLE_RIGHT_BG, 0)
            bubbleMergeOn = p.getBoolean(Prefs.KEY_GLASS_BUBBLE_MERGE, false)
        }
        // Logged, not used: both sides share one tint, and this line answers "the bubbles are not my colour".
        XposedBridge.log(
            "[$TAG] bubble glass arming on ${method.name} " +
                "(prefs left=%08x right=%08x; deliberately ignored, one glass tint; merge=%b)"
                    .format(left, right, bubbleMergeOn),
        )
        XposedBridge.hookMethod(
            method,
            object : XC_MethodHook(PRIORITY_HIGHEST) {
                override fun afterHookedMethod(param: MethodHookParam) {
                    // WA convention, from BubbleColors: position 3 is outgoing, anything else incoming.
                    val position = param.args.getOrNull(0) as? Int ?: return
                    val density = app.resources.displayMetrics.density
                    // Arg 1 is WA's own collapse state, named by its error string; 2 and 3 are the tail-less continuations.
                    val collapse = param.args.getOrNull(1) as? Int ?: -1
                    val flag = if (bubbleMergeOn && (position == 2 || position == 3) && (collapse == 2 || collapse == 3)) {
                        GlassBubblePane.FLAG_EXT or (if (position == 3) GlassBubblePane.FLAG_OUTGOING else 0)
                    } else {
                        0
                    }
                    // One tint for both sides; sender is carried by alignment, per-side colour belongs behind an explicit opt-in.
                    val bp = bubbleParams(density)
                    param.result = GlassBubbleDrawable(
                        params = bp,
                        // A lambda, not a baked colour: the channel resolves after WhatsApp asks for this drawable.
                        tint = { bubbleTintColor },
                        density = density,
                        rimColor = glassTint(BUBBLE_RIM_ALPHA),
                        rimWidth = density,
                        // 0 when the dim is already inside the bitmap, which is the normal case.
                        dim = if (bubbleDimFolded) 0f else bubbleDim,
                        backdrop = { host -> bubbleBackdrop(host) },
                        placement = { bubbleWpPlacement },
                        rowProvider = { currentRow },
                        // A copy is stored: bounds is the drawable's live Rect and WhatsApp mutates it for the next row.
                        report = { row, bounds, f ->
                            // Stored at rest: the swipe offset is subtracted here and collectBubbleRects adds the live one back.
                            val dx = rowDisplacement(row).toInt()
                            val cur = bubbleBoundsByRow[row]
                            if (cur == null) {
                                bubbleBoundsByRow[row] =
                                    BubbleMark(
                                        Rect(bounds).also { it.offset(-dx, 0) },
                                        row.height,
                                        f,
                                    )
                            } else {
                                if (cur.rect.left != bounds.left - dx || cur.rect.right != bounds.right - dx ||
                                    cur.rect.top != bounds.top || cur.rect.bottom != bounds.bottom
                                ) {
                                    cur.rect.set(bounds)
                                    cur.rect.offset(-dx, 0)
                                }
                                cur.rowH = row.height
                                cur.flag = f
                            }
                        },
                        paneActive = { bubblePaneActive() },
                        flag = flag,
                        flatRadiusPx = if (bubbleMergeOn) BUBBLE_FLAT_RADIUS_DP * density else 0f,
                    )
                    logOnce("bubble glass applied (position=$position)")
                }
            },
        )
    }

    private val innerRoundTag = tagKey("wathemer-inner-round")

    /** Inner corner slightly tighter than the bubble's so the arcs look parallel; rows recycle, so the paint is idempotent. */
    private fun roundInnerSurface(v: View, what: String) {
        val r = (BUBBLE_RADIUS_DP - 4f).coerceAtLeast(2f) * v.resources.displayMetrics.density
        if (v.outlineProvider !is InnerRound) {
            v.outlineProvider = InnerRound(r)
            v.clipToOutline = true
            logOnce("inner surface rounded: $what")
        }
        v.invalidateOutline()
        if (v.getTag(innerRoundTag) == null) {
            v.setTag(innerRoundTag, true)
            v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> view.invalidateOutline() }
        }
    }

    private class InnerRound(private val radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
    }

    /* ── A selected chat row ────────────────────────────────────────────────────────────── */

    private val rowPopTag = tagKey("wathemer-row-pop")
    private val rowScrollTag = tagKey("wathemer-row-scroll")

    /** Scrolling repositions rows without re-recording, freezing the sampled wallpaper; armed once, never removed, deliberately. */
    private class RowScrollWatch(private val host: View) :
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val at = IntArray(2)
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE
        private var observing = false

        fun arm() {
            host.addOnAttachStateChangeListener(this)
            if (host.isAttachedToWindow) attach()
        }

        private fun attach() {
            if (observing) return
            host.viewTreeObserver.addOnPreDrawListener(this)
            observing = true
        }

        override fun onViewAttachedToWindow(v: View) = attach()

        override fun onViewDetachedFromWindow(v: View) {
            if (!observing) return
            host.viewTreeObserver.removeOnPreDrawListener(this)
            observing = false
        }

        override fun onPreDraw(): Boolean {
            if (host.background !is SelectionPane) return true
            host.getLocationOnScreen(at)
            if (at[0] != lastX || at[1] != lastY) {
                lastX = at[0]
                lastY = at[1]
                host.invalidate()
            }
            return true
        }
    }

    /** WhatsApp selects a row by swapping in an opaque ColorDrawable; its colour is reused so the user's theme stays in charge. */
    private fun rowSelectionPane(v: View, source: ColorDrawable): Drawable {
        val c = source.color
        return SelectionPane(
            host = v,
            insetX = v.dp(ROW_SELECT_INSET_X_DP),
            insetY = v.dp(ROW_SELECT_INSET_Y_DP),
            radius = v.dp(ROW_SELECT_RADIUS_DP),
            fill = Color.argb(ROW_SELECT_ALPHA, Color.red(c), Color.green(c), Color.blue(c)),
            lift = glassTint(ROW_SELECT_LIFT),
            rim = glassTint(ROW_SELECT_RIM_ALPHA),
            rimWidth = v.dp(1f),
            sharp = { selectionBackdrop(v) },
            placement = { selectionWpPlacement },
        )
    }

    /* The selected row's own, barely-blurred copy of the wallpaper. See [ROW_SELECT_SHRINK]. */
    private var selectionSharp: Bitmap? = null
    private var selectionSharpResolved = false
    private var selectionWpPlacement: Matrix? = null

    /** Deliberately lighter than bubbleBackdrop so the wallpaper's shapes stay readable; dim folded in with the same guard. */
    private fun selectionBackdrop(host: View): Bitmap? {
        if (selectionSharpResolved && !selectionWpDirty) return selectionSharp
        val img = host.rootView?.findViewWithTag<View>("wt_wallpaper")
            as? ImageView ?: return selectionSharp
        val src = (img.drawable as? BitmapDrawable)?.bitmap
            ?: return selectionSharp
        if (img.width <= 0) return selectionSharp
        if (!selectionSharpResolved) {
            val w = (src.width / ROW_SELECT_SHRINK).coerceAtLeast(1)
            val h = (src.height / ROW_SELECT_SHRINK).coerceAtLeast(1)
            val small = runCatching { Bitmap.createScaledBitmap(src, w, h, true) }
                .getOrNull() ?: return null
            val dim = (host.rootView?.findViewWithTag<View>("wt_wallpaper_dim")?.alpha ?: 0f)
                .coerceIn(0f, 1f)
            if (dim > 0f && small !== src && small.isMutable) {
                runCatching {
                    Canvas(small).drawColor(
                        ((dim * 255f).toInt().coerceIn(0, 255) shl 24),
                        PorterDuff.Mode.SRC_OVER,
                    )
                }
            }
            selectionSharp = small
            selectionSharpResolved = true
            logOnce("selection backdrop built ${small.width}x${small.height} dim=$dim")
        }
        selectionSharp?.let {
            selectionWpPlacement = wallpaperPlacement(img, src, it)
            // Cleared by the consumer that owns this placement, never by the bubble path.
            selectionWpDirty = false
        }
        return selectionSharp
    }

    /** A plain Drawable on purpose: an InsetDrawable reports its inset as padding and the row walks away from itself. */
    private class SelectionPane(
        private val host: View,
        private val insetX: Float,
        private val insetY: Float,
        private val radius: Float,
        private val fill: Int,
        private val lift: Int,
        private val rim: Int,
        private val rimWidth: Float,
        private val sharp: () -> Bitmap?,
        private val placement: () -> Matrix?,
    ) : Drawable() {
        private val box = RectF()
        private val path = Path()
        private val at = IntArray(2)
        private val m = Matrix()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bmpPaint = Paint(
            Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG,
        )

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() <= 0 || b.height() <= 0) return
            box.set(b)
            box.inset(insetX, insetY)
            if (box.width() <= 0f || box.height() <= 0f) return
            val r = minOf(radius, box.height() / 2f)

            val bmp = runCatching { sharp() }.getOrNull()
            val place = if (bmp != null) placement() else null
            val save = canvas.save()
            path.reset()
            path.addRoundRect(box, r, r, Path.Direction.CW)
            canvas.clipPath(path)
            if (bmp != null && place != null) {
                host.getLocationOnScreen(at)
                m.set(place)
                m.postTranslate(-at[0].toFloat(), -at[1].toFloat())
                canvas.drawBitmap(bmp, m, bmpPaint)
            }
            paint.style = Paint.Style.FILL
            if (lift ushr 24 != 0) {
                paint.color = lift
                canvas.drawRoundRect(box, r, r, paint)
            }
            paint.color = fill
            canvas.drawRoundRect(box, r, r, paint)
            canvas.restoreToCount(save)

            if (rimWidth > 0f && rim ushr 24 != 0) {
                val h = rimWidth / 2f
                box.inset(h, h)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = rimWidth
                paint.color = rim
                canvas.drawRoundRect(box, r - h, r - h, paint)
            }
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** A transform, not an inset, so the content rises too; the same setBackground signal resets recycled rows. */
    private fun popRow(v: View, up: Boolean) {
        val target = if (up) ROW_SELECT_SCALE else 1f
        (v.getTag(rowPopTag) as? ValueAnimator)?.cancel()
        if (v.scaleX == target) return
        // Detached means recycled: snap, or the next row inherits a half-finished scale.
        if (!v.isAttachedToWindow) {
            v.scaleX = target
            v.scaleY = target
            return
        }
        val anim = ValueAnimator.ofFloat(v.scaleX, target).apply {
            duration = if (up) 220L else 140L
            interpolator = if (up) {
                OvershootInterpolator(2.4f)
            } else {
                DecelerateInterpolator()
            }
            addUpdateListener { a ->
                val s = a.animatedValue as Float
                v.scaleX = s
                v.scaleY = s
            }
        }
        v.setTag(rowPopTag, anim)
        anim.start()
    }

    private var quoteMaskHookInstalled = false

    /** Kills the quote's green corner mask per bind; abstains while a quote colour is set, QuoteAndLabelColors owns that case. */
    private fun installQuoteMaskKill(frameId: Int) {
        if (quoteMaskHookInstalled) return
        quoteMaskHookInstalled = true
        // Through the shared dispatcher, this setter had three interceptors; the gate must be evaluated per call.
        ForegroundKillDispatcher.kill(frameId) { !quoteColored }
        logOnce("quote corner-mask kill armed")
    }

    /** A bubble cannot learn its own position (null callback, identity matrix); the row hooks record it instead. */
    private var rowHookInstalled = false

    /* ── A selected message row, measurements ──────────────────────────────────────────── */

    /** MSG_SELECT_ALPHA survives only for the no-pane fallback, which reshapes WhatsApp's own fill in place. */
    private const val MSG_SELECT_ALPHA = 62
    private const val MSG_SELECT_RIM_ALPHA = 70

    /** Measured: the pill is a 159x501 stadium, so half its width, 29dp; the pane clamps anyway. */
    private const val LOCK_PILL_RADIUS_DP = 29f
    private const val MSG_SELECT_INSET_X_DP = 8f
    private const val MSG_SELECT_INSET_Y_DP = 1f

    /** Widened only where a bubble would overhang the base inset, so common rows keep one width. */
    private const val MSG_SELECT_BUBBLE_PAD_DP = 2f
    private const val MSG_SELECT_RADIUS_DP = 18f

    private var msgSelectHooked = false
    private val msgSelectRect = RectF()
    private val msgSelectPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Intercepted at BaseRecordingCanvas, a Canvas hook never fires; matched by shape, never by WhatsApp's theme colour. */
    private fun installMessageSelectionShape() {
        if (msgSelectHooked) return
        msgSelectHooked = true
        val canvasCls = runCatching { Class.forName("android.graphics.BaseRecordingCanvas") }
            .getOrNull() ?: Canvas::class.java
        // All three overloads: the plain-Canvas fallback has no delegation, and p.result = null stops double handling.
        var hooked = 0
        for (m in canvasCls.declaredMethods) {
            if (m.name != "drawRect") continue
            val types = m.parameterTypes
            if (types.isEmpty() || types.last() != Paint::class.java) continue
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        // Cheap gate first: this fires for every rect in the process.
                        if (lockPillDrawing && dropsLockFill(p)) {
                            p.result = null
                            return
                        }
                        val row0 = currentRow
                        val paint = p.args.last() as? Paint ?: return
                        val col = paint.color
                        val a = Color.alpha(col)
                        val row = row0 ?: return
                        if (a == 0 || a > 200 || paint.shader != null) return

                        val l: Float; val t: Float; val r: Float; val b: Float
                        when (val first = p.args[0]) {
                            is Float -> {
                                l = first; t = p.args[1] as Float
                                r = p.args[2] as Float; b = p.args[3] as Float
                            }
                            is RectF -> {
                                l = first.left; t = first.top; r = first.right; b = first.bottom
                            }
                            is Rect -> {
                                l = first.left.toFloat(); t = first.top.toFloat()
                                r = first.right.toFloat(); b = first.bottom.toFloat()
                            }
                            else -> return
                        }
                        if (row.width <= 0 || (r - l) < row.width * 0.98f) return
                        if (b - t < 1f) return
                        val canvas = p.thisObject as? Canvas ?: return

                        // The fill is the whole signal: WhatsApp draws it from the row's own onDraw, nothing else carries the state.
                        msgSelDrewThisRecord = true
                        if (msgSelectPaneActive()) {
                            // Nothing position-dependent may be recorded into a row (see GlassBubblePane); just drop the fill.
                            p.result = null
                            return
                        }

                        val ix = row.dp(MSG_SELECT_INSET_X_DP)
                        val iy = row.dp(MSG_SELECT_INSET_Y_DP)
                        msgSelectRect.set(l + ix, t + iy, r - ix, b - iy)
                        if (msgSelectRect.width() <= 0f || msgSelectRect.height() <= 0f) return
                        val rad = minOf(row.dp(MSG_SELECT_RADIUS_DP), msgSelectRect.height() / 2f)
                        msgSelectPaint.color =
                            Color.argb(MSG_SELECT_ALPHA, Color.red(col), Color.green(col), Color.blue(col))
                        canvas.drawRoundRect(msgSelectRect, rad, rad, msgSelectPaint)
                        // Skip the original: for a void method this is how the flat slab is dropped.
                        p.result = null
                        logOnce("message selection reshaped in-row, no pane (was %08x)".format(col))
                    }
                })
                hooked++
            }
        }
        XposedBridge.log("[$TAG] message selection: $hooked drawRect overloads hooked")

        // drawRect is already covered above, never two hooks on one hot method; a stadium needs the two shape calls too.
        var shapes = 0
        for (m in canvasCls.declaredMethods) {
            if (m.name != "drawRoundRect" && m.name != "drawPath") continue
            if (m.parameterTypes.lastOrNull() != Paint::class.java) continue
            runCatching {
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        if (!lockPillDrawing) return
                        if (dropsLockFill(p)) p.result = null
                    }
                })
                shapes++
            }
        }
        XposedBridge.log("[$TAG] lock pill: $shapes fill shapes hooked")
    }

    /** ListView overrides drawChild, hook that exact method; onDraw runs before child dispatch, so the recorded row is right. */
    @Synchronized
    private fun ensureRowHook() {
        if (rowHookInstalled) return
        rowHookInstalled = true
        // One runCatching per hook: a single guard silently took three features down with one failed resolve.
        runCatching {
            XposedHelpers.findAndHookMethod(
                ListView::class.java, "drawChild",
                Canvas::class.java, View::class.java, Long::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !== convListRef?.get()) return
                        currentRow = param.args[1] as? View
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.thisObject !== convListRef?.get()) return
                        currentRow = null
                    }
                },
            )
            XposedBridge.log("[$TAG] row hook installed on ListView.drawChild")
        }.onFailure { XposedBridge.log("[$TAG] row hook (drawChild) failed: $it") }

        runCatching {
            // The path that skips drawChild; View.draw, not updateDisplayListIfDirty, whose fast path never re-runs onDraw.
            XposedHelpers.findAndHookMethod(
                View::class.java, "draw", Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v.parent === lockHostRef?.get()) {
                            lockPillDrawing = true
                            lockPillW = v.width
                            lockPillH = v.height
                        }
                        if (v.parent === convListRef?.get()) currentRow = v
                        if (currentRow == null) return
                        // Each nested draw gets its own flag, so a fill is attributed to the view that drew it, not an ancestor.
                        if (msgSelDepth < msgSelDrewStack.size) {
                            msgSelDrewStack[msgSelDepth] = msgSelDrewThisRecord
                        }
                        msgSelDepth++
                        msgSelDrewThisRecord = false
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        val row = currentRow
                        if (row != null) {
                            val drew = msgSelDrewThisRecord
                            msgSelDepth--
                            msgSelDrewThisRecord =
                                if (msgSelDepth in msgSelDrewStack.indices) {
                                    msgSelDrewStack[msgSelDepth]
                                } else {
                                    false
                                }
                            latchMsgSelection(v, row, drew)
                        }
                        if (v.parent === convListRef?.get()) currentRow = null
                        if (v.parent === lockHostRef?.get()) lockPillDrawing = false
                    }
                },
            )
            XposedBridge.log("[$TAG] row hook installed on View.draw(Canvas)")
        }.onFailure { XposedBridge.log("[$TAG] row hook (View.draw) failed: $it") }

        runCatching {
            // A separate job from View.draw, and neither hook covers the other; collapsing them regressed the second-selection slab.
            XposedHelpers.findAndHookMethod(
                View::class.java, "updateDisplayListIfDirty",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v.parent !== convListRef?.get()) return
                        currentRow = v
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (v.parent !== convListRef?.get()) return
                        currentRow = null
                    }
                },
            )
            XposedBridge.log("[$TAG] row hook installed on View.updateDisplayListIfDirty")
        }.onFailure { XposedBridge.log("[$TAG] row hook (updateDisplayListIfDirty) failed: $it") }

        installMessageSelectionShape()
    }

    /** Weak keys, rows recycle; only the row-relative rect is cached, the screen position is re-read every frame. */
    private val bubbleBoundsByRow = WeakHashMap<View, BubbleMark>()

    /** rowH is a staleness check: a re-bound row re-reports, so a height mismatch means do not trust the rect. */
    private class BubbleMark(val rect: Rect, var rowH: Int, var flag: Int = 0)

    /** Roots whose rows carry no bubble: a sticker draws bare, a video note draws a circle. */
    private var bubblelessRootIds = IntArray(0)
    private val bubblelessRowCache = WeakHashMap<View, Boolean>()

    /** Painting a bubble for these puts a slab where WhatsApp shows none. */
    private fun isBubblelessRow(row: View): Boolean {
        if (bubblelessRootIds.isEmpty()) return false
        bubblelessRowCache[row]?.let { return it }
        val found = bubblelessRootIds.any { row.findViewById<View>(it) != null }
        bubblelessRowCache[row] = found
        return found
    }
    private var convBubblePaneRef: WeakReference<GlassBubblePane>? = null
    private val bubbleRowAt = IntArray(2)
    private val bubbleRectScratch = RectF()

    private fun bubblePaneActive(): Boolean =
        convBubblePaneRef?.get()?.let { it.parent != null } == true

    /** One pane behind the list draws every bubble's glass; in-row glass freezes on scroll, see GlassBubblePane. */
    private fun ensureBubblePane(listHost: ViewGroup, list: AbsListView) {
        val existing = convBubblePaneRef?.get()
        if (existing != null && existing.parent === listHost) return
        if (listHost !is FrameLayout) {
            logOnce("bubble pane skipped: ${listHost.javaClass.simpleName} does not stack children")
            return
        }
        val density = listHost.resources.displayMetrics.density
        val pane = GlassBubblePane(listHost.context)
        pane.params = bubbleParams(density)
        pane.tint = { bubbleTintColor }
        // The pane is its own backdrop host; convFooterRef can be null and depends on discovery order.
        pane.backdrop = { bubbleBackdrop(pane) }
        pane.placement = { bubbleWpPlacement }
        pane.dim = { if (bubbleDimFolded) 0f else bubbleDim }
        pane.rimColor = glassTint(BUBBLE_RIM_ALPHA)
        pane.rimWidth = density
        pane.collect = { out -> collectBubbleRects(out) }
        pane.flatRadiusPx = if (bubbleMergeOn) BUBBLE_FLAT_RADIUS_DP * density else 0f
        // Rotation, split screen and folds resize this pane; the bitmap survives, the mapping does not.
        pane.onGeometryChanged = { markWallpaperGeometryDirty() }
        listHost.addView(
            pane, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        convBubblePaneRef = WeakReference(pane)
        // One forced re-record: pre-hook rows never reported, and pre-pane rows painted their own glass.
        for (i in 0 until list.childCount) list.getChildAt(i)?.invalidate()
        XposedBridge.log("[$TAG] bubble pane inserted behind the conversation list")
    }

    /** Opaque and big, both needed: the glyphs use the same primitives, and only the fill spans the pill. */
    private fun dropsLockFill(p: XC_MethodHook.MethodHookParam): Boolean {
        if (lockPillW <= 0 || lockPillH <= 0) return false
        val paint = p.args.lastOrNull() as? Paint ?: return false
        if (Color.alpha(paint.color) < 200) return false
        val a0 = p.args.getOrNull(0)
        val w: Float
        val h: Float
        when (a0) {
            is RectF -> { w = a0.width(); h = a0.height() }
            is Rect -> { w = a0.width().toFloat(); h = a0.height().toFloat() }
            is Path -> {
                val b = RectF()
                a0.computeBounds(b, true)
                w = b.width(); h = b.height()
            }
            is Float -> {
                val l = a0
                val t = p.args.getOrNull(1) as? Float ?: return false
                val r = p.args.getOrNull(2) as? Float ?: return false
                val bo = p.args.getOrNull(3) as? Float ?: return false
                w = r - l; h = bo - t
            }
            else -> return false
        }
        return w >= lockPillW * 0.8f && h >= lockPillH * 0.8f
    }

    /* ── The call screen ────────────────────────────────────────────────────────────────── */

    /** Uniform gap between the call card's content and the pane border; WA's own audio card measures the same 24dp. */
    private const val CALL_CARD_PAD_DP = 24f

    private val callGlassTag = tagKey("wathemer-call-glass")
    private val callBgStashTag = tagKey("wathemer-call-bg-stash")
    private var callVideoLive = false
    private val callAt = IntArray(2)
    private var callCardRef: WeakReference<ViewGroup>? = null
    private var callCardBgRef: WeakReference<View>? = null
    private var callSymLogged = false

    /** Wallpaper revealed and the video watcher armed; the card's pane rides its own registration. */
    private fun callScreenGlass(root: ViewGroup) {
        val pkg = root.context.packageName
        val res = root.resources
        // The wallpaper injector reaches every Activity; the glass only lies without a real backdrop under it.
        if (root.rootView?.findViewWithTag<View>("wt_wallpaper") == null) {
            logOnce("call screen: no wallpaper in this window; glass stands down")
            return
        }
        val bgId = res.waId("call_background", pkg)
        root.findViewById<View>(bgId)?.let {
            // INVISIBLE not GONE, the hideNativeWallpaper rule: the layout slot stays.
            if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE
        }
        val surfaceId = res.waId("surface_view", pkg)
        val callScreenId = res.waId("call_screen", pkg)
        clearCallShells(root, callScreenId)

        // Layout-driven, never per frame: findViewById is a tree walk and calls upgrade to video mid-session.
        if (root.getTag(callGlassTag) == null) {
            root.setTag(callGlassTag, true)
            val refresh = refresh@{
                // Re-assert per layout: WA repaints its backdrop on connect, and the flip lands before the draw.
                root.findViewById<View>(bgId)?.let { if (it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE }
                clearCallShells(root, callScreenId)
                val was = callVideoLive
                callVideoLive = surfaceId != 0 && root.findViewById<View>(surfaceId)?.isShown == true
                if (was == callVideoLive) return@refresh
                XposedBridge.log(
                    "[$TAG] call video ${if (callVideoLive) "LIVE; card glass standing down" else "gone; card glass resumes"}",
                )
                if (callVideoLive) restoreCallCardBg()
            }
            refresh()
            root.viewTreeObserver.addOnGlobalLayoutListener { runCatching { refresh() } }
        }
    }

    /** The doodle image hid solid fills on the shells; every one of them sits over the injected wallpaper. */
    private fun clearCallShells(root: ViewGroup, callScreenId: Int) {
        var v: View? = root
        var hops = 0
        while (v != null && hops < 6) {
            val bg = v.background
            if (bg != null) {
                logOnce("call shell fill cleared: ${v.javaClass.simpleName} (${bg.javaClass.simpleName})")
                v.background = null
            }
            if (v.id == android.R.id.content) break
            v = v.parent as? View
            hops++
        }
        if (callScreenId != 0) {
            root.findViewById<View>(callScreenId)?.let {
                val bg = it.background
                if (bg != null) {
                    logOnce("call shell fill cleared: call_screen (${bg.javaClass.simpleName})")
                    it.background = null
                }
            }
        }
    }

    /** Refreshes the refs on every card (re)build and inserts the pane once per host. */
    private fun callCardGlass(card: ViewGroup) {
        val pkg = card.context.packageName
        val res = card.resources
        val root = card.rootView ?: return
        if (root.findViewWithTag<View>("wt_wallpaper") == null) return
        callCardRef = WeakReference(card)
        callCardBgRef = WeakReference(card.findViewById(res.waId("background", pkg)))

        // Guard on the HOST: WA rebuilds the card per call type, and a tag on the dead instance would stack panes.
        val host = card.parent as? ViewGroup ?: return
        if (host.getTag(callGlassTag) != null) return
        host.setTag(callGlassTag, true)
        val d = card.resources.displayMetrics.density
        // The stock fill's own radius, read before the clear strips it; the card family default otherwise.
        val stockRadius = callCardBgRef?.get()?.let { readCornerRadius(it) }
        val g = GlassBubblePane(host.context)
        g.params = GlassParams(d).apply {
            blurRadius = d * BLUR_DP
            cornerRadius = stockRadius ?: (d * CARD_RADIUS_DP)
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = d * DISPLACE_DP
            fresnelStrength = 0.5f
            tintColor = glassTintColor
        }
        g.tint = { glassTintColor }
        g.backdrop = { bubbleBackdrop(g) }
        g.placement = { bubbleWpPlacement }
        g.dim = { 0f }
        g.rimColor = glassTint(BUBBLE_RIM_ALPHA)
        g.rimWidth = d
        g.collect = { out -> collectCallCard(out) }
        g.onGeometryChanged = { markWallpaperGeometryDirty() }
        // Just under the card in its own parent: over the grid and header, never over the buttons.
        host.addView(
            g, host.indexOfChild(card).coerceAtLeast(0),
            ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        XposedBridge.log("[$TAG] call card pane inserted (stockRadius=${stockRadius?.toInt() ?: -1}px)")
    }

    /** The pane rect is the content union plus one equal pad on all four sides, symmetric by construction. */
    private fun collectCallCard(out: RectList) {
        if (callVideoLive) return
        val card = callCardRef?.get() ?: return
        if (!card.isShown || card.width <= 0 || card.height <= 0) return
        val bg = callCardBgRef?.get()
        // Prevention, not correction: WA rebuilds the card per call type, so the clear rides the pre-draw collect.
        if (bg != null && bg.background != null) {
            bg.setTag(callBgStashTag, bg.background)
            bg.background = null
        }
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        for (i in 0 until card.childCount) {
            val c = card.getChildAt(i) ?: continue
            // Bare Views are the card's own fill and click targets, not content; header_click spans the card.
            if (c === bg || c.javaClass == View::class.java) continue
            if (!c.isShown || c.width <= 0 || c.height <= 0) continue
            if (c.left < l) l = c.left
            if (c.top < t) t = c.top
            if (c.right > r) r = c.right
            if (c.bottom > b) b = c.bottom
        }
        if (r <= l || b <= t) return
        val pad = card.dp(CALL_CARD_PAD_DP)
        card.getLocationOnScreen(callAt)
        out.add(
            callAt[0] + l - pad,
            callAt[1] + t - pad,
            callAt[0] + r + pad,
            callAt[1] + b + pad,
        )
        if (!callSymLogged) {
            callSymLogged = true
            XposedBridge.log(
                "[$TAG] call card pane: union=[$l,$t,$r,$b] pad=${pad.toInt()}px card=${card.width}x${card.height}",
            )
        }
    }

    /** The More dialog: its own window, so the container wears the sheet stamp and the rows keep ripples only. */
    private fun callMoreMenuGlass(label: View) {
        val row = label.parent as? ViewGroup ?: return
        val container = row.parent as? ViewGroup ?: return
        if (container.getTag(callGlassTag) != null) return
        container.setTag(callGlassTag, true)
        val d = label.resources.displayMetrics.density
        var rows = 0
        for (i in 0 until container.childCount) {
            val r = container.getChildAt(i) as? ViewGroup ?: continue
            if (r.background == null) continue
            // Radius read before the fill goes; the ripple keeps the touch feedback the fill carried.
            val radius = readCornerRadius(r) ?: (14f * d)
            val mask = GradientDrawable().apply {
                setColor(-1)
                cornerRadius = radius
            }
            r.background = RippleDrawable(
                ColorStateList.valueOf(0x2EFFFFFF), null, mask,
            )
            rows++
            for (j in 0 until r.childCount) {
                val c = r.getChildAt(j)
                if (c is FrameLayout && c.background != null) {
                    runCatching { tintWdsShape(c.background, glassTint(CHIP_ALPHA)) }
                }
            }
        }
        // The card is menu_card_frame above the rows (ancestry-traced); its own fill is the slab, nothing else is touched.
        val frameId = label.resources.waId("menu_card_frame", label.context.packageName)
        var frame: View? = container
        var hops = 0
        while (frame != null && frame.id != frameId && hops < 4) {
            frame = frame.parent as? View
            hops++
        }
        if (frame == null || frame.id != frameId) {
            logOnce("call more menu: menu_card_frame not found above the rows")
            return
        }
        val radius = readCornerRadius(frame) ?: (d * CARD_RADIUS_DP)
        // Straight assignment, never clearBg: its keep-clear re-assert kills the stamp.
        frame.background = FrostDrawable(
            frame, bubbleBackdrop(frame),
            radius, glassTintColor,
            strokeWidth = frame.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
            ignorePadding = true,
        )
        XposedBridge.log("[$TAG] call more menu: menu_card_frame frosted, $rows rows cleared to ripple")
    }

    /** Video went live: the stamp stops, so the card must get its own fill back or it floats bare over the feed. */
    private fun restoreCallCardBg() {
        val bg = callCardBgRef?.get() ?: return
        val stash = bg.getTag(callBgStashTag) as? Drawable ?: return
        if (bg.background == null) bg.background = stash
        bg.setTag(callBgStashTag, null)
    }

    /* ── The contact picker ─────────────────────────────────────────────────────────────── */

    /** Half the gap between picker cards; without it adjacent groups share an edge and read as one shape. */
    private const val PICKER_CARD_GAP_DP = 6f

    private val pickerCardTag = tagKey("wathemer-picker-card")
    private val pickerDiscTag = tagKey("wathemer-picker-disc")
    private var pickerListRef: WeakReference<ListView>? = null
    private val pickerAt = IntArray(2)

    /** Two cards from one pane over one ListView; the toolbar pill is its own pane, one corner radius per pane. */
    private fun pickerGlass(root: ViewGroup) {
        // A MATCH_PARENT pane may only go in a parent whose own size does not depend on its children; content is that parent.
        val host = root.rootView?.findViewById<View>(android.R.id.content) as? FrameLayout
        if (host == null) {
            logOnce("contact picker: no full-size content host")
            return
        }
        val listHost = root.findViewById<View>(
            root.resources.waId("contact_list", root.context.packageName),
        ) as? ViewGroup
        val list = listHost?.let { h ->
            (0 until h.childCount).map { h.getChildAt(it) }
                .firstOrNull { it is ListView } as? ListView
        }
        if (list != null) {
            pickerListRef = WeakReference(list)
            list.clipToPadding = false
        }
        if (host.getTag(pickerCardTag) != null) return
        host.setTag(pickerCardTag, true)
        val d = host.resources.displayMetrics.density
        val toolbar = root.findViewById<View>(
            root.resources.waId("toolbar", root.context.packageName),
        )

        // Two panes: a GlassBubblePane carries one corner radius for everything it stamps.
        fun pane(radiusDp: Float, collect: (GlassBubblePane, RectList) -> Unit, what: String) {
            val g = GlassBubblePane(host.context)
            g.params = GlassParams(d).apply {
                blurRadius = d * BLUR_DP
                cornerRadius = d * radiusDp
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = d * DISPLACE_DP
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
            g.tint = { glassTintColor }
            g.backdrop = { bubbleBackdrop(g) }
            g.placement = { bubbleWpPlacement }
            g.dim = { 0f }
            g.rimColor = glassTint(BUBBLE_RIM_ALPHA)
            g.rimWidth = d
            g.collect = { out -> collect(g, out) }
            g.onGeometryChanged = { markWallpaperGeometryDirty() }
            host.addView(
                g, 0,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            XposedBridge.log("[$TAG] contact picker: $what inserted")
        }

        pane(CARD_RADIUS_DP, { _, out -> collectPickerCards(out) }, "cards pane")
        // The bar, not the toolbar in it: the two swap during search and the bar's own box does not move.
        val header = root.findViewById<View>(
            root.resources.waId("wds_search_bar", root.context.packageName),
        ) ?: toolbar
        if (header != null) {
            root.viewTreeObserver.addOnGlobalLayoutListener {
                runCatching { syncPickerBand(root, header) }
            }
            syncPickerBand(root, header)
        }
    }

    // Resolved once: waId is an uncached getIdentifier and this collect runs per pre-draw frame.
    private var pickerIdsResolved = false
    private var pickerActionIds = IntArray(0)
    private var pickerFooterId = 0
    private var pickerSelectorId = 0
    private var pickerPhotoId = 0
    private var pickerNameId = 0

    /** Kind, height and the label it was classified at; two row types share a height, so height alone let a recycled view keep the wrong kind. */
    private class PickerRowKind(val kind: Int, val height: Int, val name: CharSequence?)
    private val pickerRowKinds = WeakHashMap<View, PickerRowKind>()

    /** The name view per row. Cached because the subtree walk is the cost; its text is a field read. */
    private val pickerNameViews = WeakHashMap<View, TextView>()
    private var loggedGlyph = false

    private fun pickerNameOf(row: View): CharSequence? {
        if (pickerNameId == 0) return null
        val tv = pickerNameViews[row] ?: (row.findViewById<View>(pickerNameId) as? TextView)
            ?.also { pickerNameViews[row] = it } ?: return null
        return tv.text
    }

    /** Rows sort by contained id; the section header carries none, which is what makes the gap between the cards. */
    private fun collectPickerCards(out: RectList) {
        val list = pickerListRef?.get() ?: return
        if (!list.isShown) return
        val pkg = list.context.packageName
        if (!pickerIdsResolved) {
            pickerIdsResolved = true
            // Their own ids: header_footer_row is the invite rows' layout and carries a contact_selector too.
            pickerActionIds = intArrayOf(
                list.resources.waId("menuitem_new_group_row", pkg),
                list.resources.waId("menuitem_new_contact_row", pkg),
                list.resources.waId("menuitem_new_communities_row", pkg),
                list.resources.waId("menuitem_new_broadcast", pkg),
            ).filter { it != 0 }.toIntArray()
            pickerFooterId = list.resources.waId("header_footer_row", pkg)
            pickerSelectorId = list.resources.waId("contact_selector", pkg)
            pickerPhotoId = list.resources.waId("contactpicker_row_photo", pkg)
            pickerNameId = list.resources.waId("contactpicker_row_name", pkg)
        }
        val ix = list.dp(CARD_INSET_DP)
        var aT = Float.MAX_VALUE
        var aB = -Float.MAX_VALUE
        var cT = Float.MAX_VALUE
        var cB = -Float.MAX_VALUE
        var fT = Float.MAX_VALUE
        var fB = -Float.MAX_VALUE
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) ?: continue
            if (row.height <= 0 || row.visibility != View.VISIBLE) continue
            // Cached per row: the findViewById subtree walks are what this avoids.
            val cached = pickerRowKinds[row]
            val shown = pickerNameOf(row)
            val kind: Int
            if (cached != null && cached.height == row.height && cached.name == shown) {
                kind = cached.kind
            } else {
                val isA = pickerActionIds.any { row.findViewById<View>(it) != null }
                val isF = !isA && pickerFooterId != 0 && row.findViewById<View>(pickerFooterId) != null
                val isC = !isA && !isF && pickerSelectorId != 0 &&
                    row.findViewById<View>(pickerSelectorId) != null
                kind = if (isA) 1 else if (isC) 2 else if (isF) 3 else 0
                pickerRowKinds[row] = PickerRowKind(kind, row.height, shown)
            }
            if (kind == 0) continue
            if (kind == 1) runCatching { glassActionDisc(row) }
            if (kind == 3) runCatching { frostPickerDiscs(row) }
            row.getLocationOnScreen(pickerAt)
            val t = pickerAt[1].toFloat()
            val b = (pickerAt[1] + row.height).toFloat()
            when (kind) {
                1 -> {
                    if (t < aT) aT = t
                    if (b > aB) aB = b
                }
                2 -> {
                    if (t < cT) cT = t
                    if (b > cB) cB = b
                }
                else -> {
                    if (t < fT) fT = t
                    if (b > fB) fB = b
                }
            }
        }
        val l = ix
        val r = list.width - ix
        if (r <= l) return
        val gap = list.dp(PICKER_CARD_GAP_DP)
        val radius = list.dp(CARD_RADIUS_DP)

        // One clip, the box the topmost card is clamped to; a partly scrolled row reaches the list's edge.
        val clipTop = gap.toInt()
        val provider = list.outlineProvider as? CardOutline
        if (provider == null) {
            list.outlineProvider = CardOutline(l.toInt(), clipTop, r.toInt(), list.height, radius)
            list.clipToOutline = true
            list.invalidateOutline()
        } else if (provider.set(l.toInt(), clipTop, r.toInt(), list.height, radius)) {
            // Gated on a real move: unconditional from this per-frame path it schedules frames forever.
            list.invalidateOutline()
        }

        // Clamped to the clip, not the list's edge: that is what puts air between the header and the card.
        list.getLocationOnScreen(pickerAt)
        val top = (pickerAt[1] + clipTop).toFloat()
        val bottom = (pickerAt[1] + list.height).toFloat()
        // Unrolled: a listOf of pairs boxes four floats per pre-draw frame on this path.
        fun emit(t0: Float, b0: Float) {
            if (b0 <= t0) return
            // Inset first, clamp second: the gap belongs between the groups.
            val t = (t0 + gap).coerceAtLeast(top)
            val b = (b0 - gap).coerceAtMost(bottom)
            if (b <= t) return
            out.add(pickerAt[0] + l, t, pickerAt[0] + r, b)
        }
        // Three groups, so the invite rows keep a card of their own instead of extending the contacts'.
        emit(aT, aB)
        emit(cT, cB)
        emit(fT, fB)
    }

    /**
     * The New rows' discs. WhatsApp bakes the accent circle and the white glyph into one bitmap, so
     * there is no fill to clear: the glyph is lifted out and the circle behind it becomes glass.
     */
    private fun glassActionDisc(row: View) {
        if (row.getTag(pickerDiscTag) != null) return
        val icon = firstImageIn(row, 0) ?: return
        if (icon.width <= 0 || icon.height <= 0) return
        row.setTag(pickerDiscTag, true)
        runCatching {
            val glyph = glyphOf(icon.resources, icon.drawable)
            if (glyph !== icon.drawable) icon.setImageDrawable(glyph)
            // For an icon that was never a bitmap: a vector keeps its theme fill, and community's is dark.
            icon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            frost(icon, allowSquare = true, ignorePadding = true)
        }
    }

    /** The row's icon has no id of its own, so it is found by shape: the first ImageView in it. */
    private fun firstImageIn(v: View, depth: Int): ImageView? {
        if (v is ImageView) return v
        if (depth >= 4 || v !is ViewGroup) return null
        for (i in 0 until v.childCount) {
            firstImageIn(v.getChildAt(i) ?: continue, depth + 1)?.let { return it }
        }
        return null
    }

    /** The glyph, white, whatever the icon is made of; the mask is read from the bitmap's own opacity, never assumed. */
    private fun glyphOf(res: android.content.res.Resources, d: Drawable?): Drawable? {
        val src = (d as? BitmapDrawable)?.bitmap ?: return d
        return runCatching {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)

            // Quantised to 5 bits a channel, so antialiasing does not split the fill into a thousand keys.
            val counts = HashMap<Int, Int>()
            var opaque = 0
            for (p in px) {
                if (p ushr 24 < 128) continue
                opaque++
                val q = p and 0xF8F8F8
                counts[q] = (counts[q] ?: 0) + 1
            }
            val discInBitmap = opaque * 2 > px.size
            val fill = counts.maxByOrNull { it.value }?.key ?: 0
            if (!loggedGlyph) {
                loggedGlyph = true
                XposedBridge.log(
                    "[$TAG] action glyph " + w + "x" + h + " opaque=" + opaque + "/" + px.size +
                        " discInBitmap=" + discInBitmap + " fill=" + Integer.toHexString(fill),
                )
            }
            val fr = (fill shr 16) and 0xFF
            val fg = (fill shr 8) and 0xFF
            val fb = fill and 0xFF

            for (i in px.indices) {
                val p = px[i]
                val a = p ushr 24
                if (a == 0) {
                    px[i] = 0
                    continue
                }
                val m = if (discInBitmap) {
                    val dr = (p shr 16) and 0xFF
                    val dg = (p shr 8) and 0xFF
                    val db = p and 0xFF
                    // Saturating at 255 of summed channel distance: a white glyph and a dark one both clear it.
                    val dist = kotlin.math.abs(dr - fr) + kotlin.math.abs(dg - fg) +
                        kotlin.math.abs(db - fb)
                    if (dist >= 255) 255 else dist
                } else {
                    a
                }
                px[i] = ((m * a / 255) shl 24) or 0xFFFFFF
            }
            BitmapDrawable(res, Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888))
        }.getOrDefault(d)
    }

    /** Scoped to the row: the same photo id is every contact's avatar, and frosting those would erase the photos. */
    private fun frostPickerDiscs(row: View) {
        // The caller hands us classified rows only; the row tag makes repeat frames a single getTag.
        if (row.getTag(pickerDiscTag) != null) return
        if (pickerPhotoId == 0) return
        val photo = row.findViewById<View>(pickerPhotoId) ?: return
        if (photo.getTag(pickerDiscTag) != null) return
        if (photo.width <= 0 || photo.height <= 0) return
        photo.setTag(pickerDiscTag, true)
        row.setTag(pickerDiscTag, true)
        runCatching { frost(photo, allowSquare = true, ignorePadding = true) }
    }

    /** Band and pill stack, so they split TINT_ALPHA: the sum over the overlap must equal one surface. */
    private fun pickerBandAlpha(): Int = (TINT_ALPHA / 2).coerceAtLeast(0)

    private fun pickerPillAlpha(): Int = (TINT_ALPHA - pickerBandAlpha()).coerceAtLeast(0)

    private var pickerBandRef: WeakReference<GlassView>? = null

    /** Full-width band from the top edge to the pill's bottom; it lives in action_bar_root because id/content carries the status inset as padding and clips it. */
    private fun syncPickerBand(root: ViewGroup, header: View) {
        if (header.height <= 0) return
        val under = wallpaperUnderlay(root)
        // No wallpaper means nothing to transmit, and a tint-only band is just a grey wash.
        if (under.isEmpty()) return
        val abrId = root.resources.waId("action_bar_root", root.context.packageName)
        if (abrId == 0) return
        val abr = root.rootView?.findViewById<View>(abrId) as? FrameLayout ?: return

        val at = IntArray(2)
        val abrAt = IntArray(2)
        header.getLocationOnScreen(at)
        abr.getLocationOnScreen(abrAt)
        val total = (at[1] - abrAt[1]) + header.height
        if (total <= 0) return

        var band = pickerBandRef?.get()
        if (band == null || band.parent !== abr) {
            band = GlassView(abr.context).apply {
                // Wallpaper only: a full-bleed surface tracking live content reads as a flicker.
                underlay = under
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = root.dp(BLUR_DP)
                    refractionEnabled = true
                    // With the SDF expanded there is no bevel to size; a 1px nominal one keeps displacement at zero.
                    bevelFraction = 0f
                    bevelThickness = 1f
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = root.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    cornerRadius = 0f
                    // Full-bleed panel; a rim here would outline the screen.
                    rimEnabled = false
                    edgeExpandLeft = 8f
                    edgeExpandTop = 8f
                    edgeExpandRight = 8f
                    edgeExpandBottom = 8f
                }
            }
            // Directly after the wallpaper and its dim, or the band hides behind them.
            var insertAt = 0
            for (i in 0 until abr.childCount) {
                val c = abr.getChildAt(i)
                if (c === under.firstOrNull() || (under.size > 1 && c === under[1])) insertAt = i + 1
            }
            if (insertAt == 0) {
                XposedBridge.log(
                    "[$TAG] WARN: wallpaper views are not children of action_bar_root; the picker " +
                        "band will be behind them and invisible",
                )
            }
            abr.addView(
                band, insertAt,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, total).apply {
                    gravity = Gravity.TOP
                },
            )
            pickerBandRef = WeakReference(band)
            XposedBridge.log("[$TAG] contact picker band inserted at " + insertAt + " (" + total + "px)")
        }
        band.params.tintColor = glassTint(pickerBandAlpha())
        val lp = band.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.height != total) {
            lp.height = total
            band.layoutParams = lp
        }
    }

    private var meTabContainerRef: WeakReference<View>? = null
    private val meTabAt = IntArray(2)
    private val meTabHeaderAt = IntArray(2)
    private var meTabHeaderRef: WeakReference<View>? = null
    private val meTabPaneTag = tagKey("wathemer-me-tab-pane")

    /** A stamped card on me_tab_container's box behind the profile block; the page scrolls, so a fixed panel or a full-bounds frost would not fit. */
    private fun ensureMeTabCard(container: View) {
        meTabContainerRef = WeakReference(container)
        val host = container.rootView?.findViewById<View>(android.R.id.content) as? FrameLayout ?: return
        if (host.getTag(meTabPaneTag) != null) return
        if (!host.isAttachedToWindow) return
        host.setTag(meTabPaneTag, true)
        val d = host.resources.displayMetrics.density
        val g = GlassBubblePane(host.context)
        g.params = GlassParams(d).apply {
            blurRadius = d * BLUR_DP
            cornerRadius = d * CARD_RADIUS_DP
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = d * DISPLACE_DP
            fresnelStrength = 0.5f
            tintColor = glassTintColor
        }
        g.tint = { glassTintColor }
        g.backdrop = { bubbleBackdrop(g) }
        g.placement = { bubbleWpPlacement }
        g.dim = { 0f }
        g.rimColor = glassTint(BUBBLE_RIM_ALPHA)
        g.rimWidth = d
        g.collect = { out -> collectMeTabCard(out) }
        g.onGeometryChanged = { markWallpaperGeometryDirty() }
        host.addView(
            g, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        XposedBridge.log("[$TAG] me tab profile card inserted")
    }

    private fun collectMeTabCard(out: RectList) {
        val c = meTabContainerRef?.get() ?: return
        if (!c.isShown || c.width <= 0 || c.height <= 0) return
        val side = c.dp(CARD_INSET_DP)
        val gap = c.dp(CARD_GAP_DP)
        c.getLocationOnScreen(meTabAt)
        // Clamped under the header. The header lives INSIDE this page's scroller and its motion scene
        // pins it while the block collapses behind it, so an unclamped rect rides up over the capsule.
        var top = meTabAt[1] + gap
        // Re-resolved whenever the cache is dead or belongs to another window: this page is rebuilt on
        // re-entry, and a detached header reads isShown false, so the clamp silently stops running.
        var header = meTabHeaderRef?.get()
        if (header == null || !header.isAttachedToWindow || header.rootView !== c.rootView) {
            header = c.rootView?.findViewById(
                c.resources.waId("wds_search_bar", c.context.packageName),
            )
            meTabHeaderRef = header?.let { WeakReference(it) }
        }
        if (header != null && header.isShown && header.height > 0) {
            header.getLocationOnScreen(meTabHeaderAt)
            top = maxOf(top, (meTabHeaderAt[1] + header.height + gap).toFloat())
        }
        val bottom = (meTabAt[1] + c.height).toFloat()

        // The clip FIRST, never behind an early return: a skipped frame leaves the last value
        // standing. A later sibling than the header, so it paints over it once that fill is gone.
        val cl = side.toInt()
        val ct = (top - meTabAt[1]).toInt().coerceIn(0, c.height)
        val cr = c.width - side.toInt()
        val cb = c.height
        // Capped at half the height: a bigger radius yields an outline that cannot clip.
        val radius = minOf(c.dp(CARD_RADIUS_DP), (cb - ct) / 2f).coerceAtLeast(0f)
        val provider = c.outlineProvider as? CardOutline
        if (provider == null) {
            c.outlineProvider = CardOutline(cl, ct, cr, cb, radius)
            c.invalidateOutline()
        } else if (provider.set(cl, ct, cr, cb, radius)) {
            // Gated on a real move: unconditional from this per-frame path it schedules frames forever.
            c.invalidateOutline()
        }
        // Asserted every sync, never once: returning to a retained page cleared it and the block bled.
        if (!c.clipToOutline) c.clipToOutline = true
        // Collapsed far enough that nothing of the block is left below the header.
        if (bottom <= top) return
        out.add(meTabAt[0] + side, top, meTabAt[0] + c.width - side, bottom)
    }

    private val wdsBarPanes = WeakHashMap<ViewGroup, GlassView>()
    private val wdsBackAt = IntArray(2)
    private val wdsRect = Rect()
    private val wdsOwnerAt = IntArray(2)
    private val wdsBarTag = tagKey("wathemer-wds-search-bar")

    /** A band behind this header means the capsule takes the remainder of the tint, not all of it. */
    private fun headerBandBehind(bar: View): Boolean {
        val abrId = bar.resources.waId("action_bar_root", bar.context.packageName)
        val abr = if (abrId != 0) bar.rootView?.findViewById<View>(abrId) else null
        if (abr != null && folderBands[abr]?.parent != null) return true
        return pickerBandRef?.get()?.parent != null
    }

    /** The search_view id covers two unlike bar families; acted on only where the field's parent can hold a pane, else the fill is left alone on purpose, because clearing it with no pane makes the bar invisible. */
    private fun syncSearchViewGlass(field: View) {
        val host = field.parent as? FrameLayout ?: return
        if (field.width <= 0 || field.height <= 0) return
        val res = field.resources
        val pkg = field.context.packageName

        var glass = wdsBarPanes[host]
        if (glass == null || glass.parent !== host) {
            val under = wallpaperUnderlay(host)
            if (under.isEmpty()) return
            glass = GlassView(host.context).apply {
                underlay = under
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = host.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = host.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                }
            }
            host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
            wdsBarPanes[host] = glass
            XposedBridge.log("[$TAG] search view capsule inserted")
        }
        // Only now, with a pane behind it, is the fill safe to take; two unlike families share this id.
        if (field.background != null) clearBg(field, "search_view")
        for (n in listOf("search_edit_frame", "search_plate", "submit_area", "search_view_toolbar")) {
            val id = res.waId(n, pkg)
            if (id == 0) continue
            field.findViewById<View>(id)?.let { if (it.background != null) clearBg(it, n) }
        }
        glass.params.tintColor =
            glassTint(if (headerBandBehind(host)) pickerPillAlpha() else TINT_ALPHA)

        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != field.width || lp.height != field.height ||
            lp.leftMargin != field.left || lp.topMargin != field.top
        ) {
            lp.width = field.width
            lp.height = field.height
            lp.leftMargin = field.left
            lp.topMargin = field.top
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
            glass.params.cornerRadius = field.height / 2f
        }
    }

    /** One GlassView capsule per WDSSearchBar, keyed on the component; it swaps a toolbar and a search view, and a stamped rect's light pass would not match a real pill. */
    private fun syncWdsSearchBar(bar: FrameLayout) {
        if (bar.width <= 0 || bar.height <= 0) return
        val res = bar.resources
        val pkg = bar.context.packageName
        val fieldId = res.waId("wds_search_view", pkg)
        val toolbarId = res.waId("toolbar", pkg)
        val field = (if (fieldId != 0) bar.findViewById<View>(fieldId) else null)
            ?.takeIf { it.isShown && it.height > 0 }
        val toolbar = (if (toolbarId != 0) bar.findViewById<View>(toolbarId) else null)
            ?.takeIf { it.isShown && it.height > 0 }
        // The field wins: while it is up the toolbar is still a child and still measures.
        val owner = field ?: toolbar ?: return
        if (owner.parent !== bar) return
        // Clear both: the search field's fill is on backgroundHolder, a CHILD, so clearing the field
        // itself clears nothing and its rounded rect stays over the glass.
        clearBg(owner, "wds search bar")
        val holderId = res.waId("backgroundHolder", pkg)
        (if (holderId != 0) owner.findViewById<View>(holderId) else null)
            ?.let { clearBg(it, "wds search field fill") }

        var glass = wdsBarPanes[bar]
        if (glass == null || glass.parent !== bar) {
            val under = wallpaperUnderlay(bar)
            if (under.isEmpty()) return
            glass = GlassView(bar.context).apply {
                underlay = under
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = bar.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = bar.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                }
            }
            // Index 0 draws behind the bar's own controls.
            bar.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
            wdsBarPanes[bar] = glass
            val name = runCatching { res.getResourceEntryName(bar.id) }.getOrNull() ?: "?"
            XposedBridge.log("[$TAG] wds search capsule inserted on " + name)
        }
        // A backdrop only if it reaches up here: sampled at our own position, a lower list reads black.
        val list = bar.rootView?.findViewById<View>(android.R.id.list)
        if (list != null) {
            list.getLocationOnScreen(wdsBackAt)
            owner.getLocationOnScreen(wdsOwnerAt)
            val covers = wdsBackAt[1] <= wdsOwnerAt[1]
            if (covers && glass.backdrop !== list) {
                runCatching { glass.backdrop = list }
                    .onFailure { logOnce("wds search backdrop rejected: $it") }
            } else if (!covers && glass.backdrop != null) {
                glass.backdrop = null
            }
        }
        glass.params.tintColor =
            glassTint(if (headerBandBehind(bar)) pickerPillAlpha() else TINT_ALPHA)

        // The bar's own box in both states: one box cannot disagree with itself, so the swap is seamless.
        val side = bar.dp(CARD_INSET_DP).toInt()
        val box = wdsRect
        box.set(side, 0, bar.width - side, bar.height)
        if (box.width() <= 0 || box.height() <= 0) return
        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != box.width() || lp.height != box.height() ||
            lp.leftMargin != box.left || lp.topMargin != box.top
        ) {
            lp.width = box.width()
            lp.height = box.height()
            lp.leftMargin = box.left
            lp.topMargin = box.top
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
            glass.params.cornerRadius = box.height() / 2f
        }
    }

    /* ── The voice-recording lock pill ──────────────────────────────────────────────────── */

    private var lockPaneRef: WeakReference<GlassBubblePane>? = null
    private var lockHostRef: WeakReference<ViewGroup>? = null
    private val lockAt = IntArray(2)

    /** Set only while the lock pill records; it paints its own fill in onDraw, so clearBg cannot reach it. */
    private var lockPillDrawing = false
    private var lockPillW = 0
    private var lockPillH = 0

    /** The full-screen container is the host, never a frost target; the pill is resolved per frame, not captured. */
    private fun ensureLockPane(container: View) {
        val host = container as? FrameLayout ?: run {
            logOnce("lock pane skipped: ${container.javaClass.simpleName} does not stack")
            return
        }
        val existing = lockPaneRef?.get()
        if (existing != null && existing.parent === host) return
        val d = host.resources.displayMetrics.density
        val pane = GlassBubblePane(host.context)
        pane.params = GlassParams(d).apply {
            blurRadius = d * BLUR_DP
            // The pane clamps to half the shorter side, so an oversize radius just means fully round.
            cornerRadius = d * LOCK_PILL_RADIUS_DP
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = d * DISPLACE_DP
            fresnelStrength = 0.5f
            tintColor = glassTintColor
        }
        pane.tint = { glassTintColor }
        pane.backdrop = { bubbleBackdrop(pane) }
        pane.placement = { bubbleWpPlacement }
        pane.dim = { 0f }
        pane.rimColor = glassTint(BUBBLE_RIM_ALPHA)
        pane.rimWidth = d
        pane.collect = { out -> collectLockRect(host, out) }
        pane.onGeometryChanged = { markWallpaperGeometryDirty() }
        host.addView(
            pane, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        lockPaneRef = WeakReference(pane)
        lockHostRef = WeakReference(host)
        XposedBridge.log("[$TAG] lock pill pane inserted behind voice_note_lock_container")
    }

    /** The lock pill's rect in screen pixels, or nothing while no recording is in progress. */
    private fun collectLockRect(host: FrameLayout, out: RectList) {
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i) ?: continue
            if (c is GlassBubblePane) continue
            if (!c.isShown || c.width <= 0 || c.height <= 0) continue
            c.getLocationOnScreen(lockAt)
            out.add(
                lockAt[0].toFloat(), lockAt[1].toFloat(),
                (lockAt[0] + c.width).toFloat(), (lockAt[1] + c.height).toFloat(),
            )
        }
    }

    /* ── A selected message row ─────────────────────────────────────────────────────────── */

    private val msgSelFillTag = tagKey("wathemer-msg-fill-owner")
    private val msgSelOwnerTag = tagKey("wathemer-msg-owner-ref")
    private var convMsgSelectPaneRef: WeakReference<GlassBubblePane>? = null
    private val msgSelectAt = IntArray(2)

    /** True once the selection pane is in the tree, i.e. once it is the one painting the panel. */
    private fun msgSelectPaneActive(): Boolean =
        convMsgSelectPaneRef?.get()?.let { it.parent != null } == true

    /** Runs after ensureBubblePane and both insert at 0, so the panel lands beneath the bubbles; do not reorder. */
    private fun ensureMsgSelectPane(listHost: ViewGroup, list: AbsListView) {
        val existing = convMsgSelectPaneRef?.get()
        if (existing != null && existing.parent === listHost) return
        if (listHost !is FrameLayout) {
            logOnce("message selection pane skipped: ${listHost.javaClass.simpleName} does not stack")
            return
        }
        val d = listHost.resources.displayMetrics.density
        val pane = GlassBubblePane(listHost.context)
        pane.params = GlassParams(d).apply {
            blurRadius = d * BLUR_DP
            cornerRadius = d * MSG_SELECT_RADIUS_DP
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = d * DISPLACE_DP
            fresnelStrength = 0.5f
            tintColor = glassTintColor
        }
        pane.tint = { glassTintColor }
        // Same wallpaper, mapping and dirty flag as the bubbles: one material that cannot drift.
        pane.backdrop = { bubbleBackdrop(pane) }
        pane.placement = { bubbleWpPlacement }
        pane.dim = { 0f }
        pane.rimColor = glassTint(MSG_SELECT_RIM_ALPHA)
        pane.rimWidth = d
        pane.collect = { out -> collectMsgSelectRects(out) }
        pane.onGeometryChanged = { markWallpaperGeometryDirty() }
        listHost.addView(
            pane, 0,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        convMsgSelectPaneRef = WeakReference(pane)
        // Rows already on screen reshaped their own fill; re-record so they stop and latch their tag.
        for (i in 0 until list.childCount) list.getChildAt(i)?.invalidate()
        XposedBridge.log("[$TAG] message selection pane inserted under the bubble pane")
    }

    /** The mark lives on the view that drew the fill; only that owner re-recording without one counts as deselection. */
    private fun latchMsgSelection(v: View, row: View, drew: Boolean) {
        val owns = v.getTag(msgSelFillTag) != null
        if (drew == owns) return
        v.setTag(msgSelFillTag, if (drew) true else null)
        // The row only ever caches a handle to the owner. It is not the state; see [msgSelectOwnerOf].
        if (drew) row.setTag(msgSelOwnerTag, WeakReference(v))
        // Ask for another frame: this traversal's pre-draw has already run and nothing else is scheduled.
        convMsgSelectPaneRef?.get()?.postInvalidateOnAnimation()
    }

    /** The state lives on the owner, never the row; every check below matters. */
    private fun msgSelectOwnerOf(row: View): View? {
        val owner = (row.getTag(msgSelOwnerTag) as? WeakReference<*>)?.get() as? View
            ?: return null
        if (owner.getTag(msgSelFillTag) == null) return null
        if (!owner.isAttachedToWindow) return null
        // Visibility is the deselection signal: WhatsApp hides the owner, and invalidate() cannot reach a hidden view.
        if (!owner.isShown) return null
        var a: View? = owner
        var hop = 0
        while (a != null && a !== row && hop < 6) {
            a = a.parent as? View
            hop++
        }
        return if (a === row) owner else null
    }

    /** Rebuilt per frame; a row whose height changed since it recorded was re-bound, so its mark is skipped. */
    private fun collectMsgSelectRects(out: RectList) {
        val list = convListRef?.get() ?: return
        val insetX = list.dp(MSG_SELECT_INSET_X_DP)
        val insetY = list.dp(MSG_SELECT_INSET_Y_DP)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) ?: continue
            if (row.width <= 0 || row.height <= 0 || row.visibility != View.VISIBLE) continue
            if (msgSelectOwnerOf(row) == null) continue
            row.getLocationOnScreen(msgSelectAt)
            var l = insetX
            var r = row.width - insetX
            // Widen to the bubble where it is wider; a stale-height mark means re-bound, so it is not trusted (BubbleMark).
            val mark = bubbleBoundsByRow[row]
            if (mark != null && mark.rowH == row.height) {
                val pad = list.dp(MSG_SELECT_BUBBLE_PAD_DP)
                l = minOf(l, mark.rect.left - pad)
                r = maxOf(r, mark.rect.right + pad)
            }
            l = l.coerceAtLeast(0f)
            r = r.coerceAtMost(row.width.toFloat())
            val t = insetY
            val b = row.height - insetY
            if (r <= l || b <= t) continue
            out.add(msgSelectAt[0] + l, msgSelectAt[1] + t, msgSelectAt[0] + r, msgSelectAt[1] + b)
        }
    }

    /** The row never moves during a swipe; a descendant carries the translation, first non-zero wins. */
    private fun rowDisplacement(v: View, depth: Int = 0): Float {
        if (v.translationX != 0f) return v.translationX
        if (v is ViewGroup && depth < 3) {
            for (c in 0 until v.childCount) {
                val r = rowDisplacement(v.getChildAt(c) ?: continue, depth + 1)
                if (r != 0f) return r
            }
        }
        return 0f
    }

    /** Rebuilt per frame, no allocation; a row with no mark has not drawn since the hook armed and is skipped. */
    private fun collectBubbleRects(out: RectList) {
        val list = convListRef?.get() ?: return
        val density = list.resources.displayMetrics.density
        val insetX = density
        val insetY = 2f * density
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) ?: continue
            if (row.height <= 0 || row.visibility != View.VISIBLE) continue
            val mark = bubbleBoundsByRow[row] ?: continue
            // See BubbleMark: a height mismatch means re-bound and not yet re-reported, so the rect is not trusted.
            if (mark.rowH != row.height) continue
            if (isBubblelessRow(row)) continue
            GlassBubbleDrawable.clamp(mark.rect, row.height, insetX, insetY, bubbleRectScratch)
            if (bubbleRectScratch.width() <= 0f || bubbleRectScratch.height() <= 0f) continue
            row.getLocationOnScreen(bubbleRowAt)
            // The mark is at rest; adding the live descendant translation back gives the exact screen position.
            val swipeDx = rowDisplacement(row)
            out.add(
                bubbleRowAt[0] + bubbleRectScratch.left + swipeDx,
                bubbleRowAt[1] + bubbleRectScratch.top,
                bubbleRowAt[0] + bubbleRectScratch.right + swipeDx,
                bubbleRowAt[1] + bubbleRectScratch.bottom,
                mark.flag,
            )
        }
        // Deliberately no frame driver: report stores rest, swipeDx re-adds the offset; do not re-add a per-frame invalidate.
    }

    /** From onPreDraw, returning false so the bad position is never presented; post() starves behind startup. */
    private fun repinToBottom(list: AbsListView) {
        // Remove through the observer captured at registration; a detached list hands back a floating observer.
        // No registration guard, deliberately: the listener self-removes and the repin is idempotent.
        val observer = list.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            private var frames = 0
            private fun drop() {
                runCatching {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    else list.viewTreeObserver.removeOnPreDrawListener(this)
                }
            }
            override fun onPreDraw(): Boolean {
                frames++
                // Pinned already, or the list went away: nothing to correct.
                if (!list.isAttachedToWindow || list.count == 0 || !list.canScrollVertically(1)) {
                    drop()
                    return true
                }
                list.setSelection(list.count - 1)
                if (frames >= 6) {
                    drop()
                    return true
                }
                return false
            }
        }
        observer.addOnPreDrawListener(listener)
    }

    /** [convRect] = [child]'s bounds mapped into [holder]'s coordinates. */
    private fun rectInHolder(holder: ViewGroup, child: View) {
        convRect.set(0, 0, child.width, child.height)
        runCatching { holder.offsetDescendantRectToMyCoords(child, convRect) }
    }

    /** Padding parks the messages clear of the chrome; clipToPadding = false lets them scroll behind it. */
    private fun padConvList(listHost: ViewGroup, footerHeight: Int) {
        val list = (0 until listHost.childCount)
            .map { listHost.getChildAt(it) }
            .firstOrNull { it is AbsListView } as? AbsListView ?: return
        convListRef = WeakReference(list)
        convListHostRef = WeakReference(listHost)
        ensureRowHook()
        ensureBubblePane(listHost, list)
        // After the bubble pane, deliberately: both insert at index 0, so this one ends up beneath it.
        ensureMsgSelectPane(listHost, list)
        val d = list.resources.displayMetrics.density
        val gap = (CONV_CHROME_GAP_DP * d).toInt()
        val holder = convHolderRef?.get()
        val top = (holder?.height ?: 0) + gap
        val bottom = footerHeight + gap
        if (list.paddingTop == top && list.paddingBottom == bottom) return
        val wasAtBottom = !list.canScrollVertically(1)
        list.clipToPadding = false
        list.setPadding(list.paddingLeft, top, list.paddingRight, bottom)
        XposedBridge.log("[$TAG] conversation list padded (top=$top bottom=$bottom)")
        if (wasAtBottom) repinToBottom(list)
    }

    /** The conversation's own AbsListView: the row hooks' identity test and the collectors' row source. */
    private var convListRef: WeakReference<ViewGroup>? = null

    /** The row being drawn; plain field, written and read on the UI thread inside one draw pass. */
    private var currentRow: View? = null

    /** Reset per record and latched at its end, so it describes this record only. */
    private var msgSelDrewThisRecord = false

    /** One flag per nested draw, so a fill lands on the view that drew it. Depth is bounded by ART. */
    private val msgSelDrewStack = BooleanArray(64)
    private var msgSelDepth = 0

    /** Found structurally, child 0 is a zero-height clipper; never derive from an index you did not set. */
    private fun convListHost(parent: ViewGroup): ViewGroup? {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i) as? ViewGroup ?: continue
            if (c.height <= 0) continue
            if (containsList(c, 0)) return c
        }
        return null
    }

    private fun containsList(v: View, depth: Int): Boolean {
        if (v is AbsListView ||
            v is ViewGroup && v.javaClass.name.contains("ConversationListView")
        ) {
            return true
        }
        if (depth >= 3 || v !is ViewGroup) return false
        for (i in 0 until v.childCount) {
            if (containsList(v.getChildAt(i) ?: continue, depth + 1)) return true
        }
        return false
    }

    /** Keyed by the element, not by index: the ActionMenuView's children change when the menu inflates. */
    private fun pill(holder: ViewGroup, owner: View, l: Int, t: Int, r: Int, b: Int) {
        if (r <= l || b <= t) return
        var glass = convPanes[owner]
        if (glass == null || glass.parent !== holder) {
            glass = GlassView(holder.context).apply {
                backdrop = convCoordRef?.get()
                underlay = wallpaperUnderlay(holder)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = holder.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = holder.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTint(convPillAlpha())
                }
            }
            holder.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
            convPanes[owner] = glass
            logOnce("conversation pill inserted")
        } else if (glass.backdrop == null) {
            // The coordinator may have attached after the pill did.
            glass.backdrop = convCoordRef?.get()
        }
        // Re-asserted: the band may arrive after the pill, and the split depends on whether it exists.
        glass.params.tintColor = glassTint(convPillAlpha())
        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != r - l || lp.height != b - t || lp.leftMargin != l || lp.topMargin != t) {
            lp.width = r - l
            lp.height = b - t
            lp.leftMargin = l
            lp.topMargin = t
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
            glass.params.cornerRadius = (b - t) / 2f
            // One log line per distinct rect, so an off-centre pill can be checked against numbers.
            val name = runCatching { owner.resources.getResourceEntryName(owner.id) }
                .getOrNull() ?: owner.javaClass.simpleName
            XposedBridge.log(
                "[$TAG] pill $name owner=${owner.left},${owner.top}-${owner.right},${owner.bottom} " +
                    "pad(${owner.paddingLeft},${owner.paddingRight}) -> pane $l,$t-$r,$b",
            )
        }
    }

    /** Sized to input_layout so the send button keeps its own circle; backdrop is a sibling, so the capture cannot recurse. */
    private fun syncConvFooter() {
        val footer = convFooterRef?.get() ?: return
        if (footer.width <= 0 || footer.height <= 0) return
        val res = footer.resources
        val pkg = footer.context.packageName
        val inputId = res.waId("input_layout", pkg)
        val sendId = res.waId("conversation_entry_action_button", pkg)
        // ── No compose row where you cannot compose ────────────────────────────────────
        // These replace the compose row while input_layout stays VISIBLE; collapse by size, syncPaneVisibility owns visibility.
        for (n in listOf(
            "read_only_chat_info_container",
            "composer_blocker",
            "voice_note_draft_layout_v2",
        )) {
            val id = res.waId(n, pkg)
            if (id == 0) continue
            val v = footer.rootView?.findViewById<View>(id) ?: continue
            if (v.isShown) {
                collapseConvPanes(footer)
                // The recorder's stock card was neutralised with the shell; give it back, sized to the layout, the footer is taller.
                if (n == "voice_note_draft_layout_v2" && v.width > 0 && v.height > 0) {
                    convRect.set(0, 0, v.width, v.height)
                    runCatching { footer.offsetDescendantRectToMyCoords(v, convRect) }
                    composePane(
                        footer, v,
                        convRect.left, convRect.top, convRect.right, convRect.bottom,
                        (footer.parent as? ViewGroup)?.let { p -> convListHost(p) },
                    )
                }
                return
            }
        }

        // A pane whose owner left is removed, not zero-sized: zeroed LayoutParams only apply if something re-measures.
        if (convPanes.isNotEmpty()) {
            // parent === footer only: the header capsule shares this map and belongs to syncConvToolbar.
            val dead = convPanes.entries
                .filter { (_, g) -> g.parent === footer }
                .filter { (o, _) -> !o.isShown || o.width <= 0 || o.height <= 0 }
                .map { it.key }
            for (o in dead) {
                convPanes.remove(o)?.let { g -> (g.parent as? ViewGroup)?.removeView(g) }
            }
        }
        val input = if (inputId != 0) footer.findViewById<View>(inputId) else null
        val send = if (sendId != 0) footer.findViewById<View>(sendId) else null
        // Same lookup as floatConvFooter, and for the same reason: index 0 is a zero-height clipper.
        val listHost = (footer.parent as? ViewGroup)?.let { convListHost(it) }

        if (input != null && input.width > 0) {
            clearBg(input, "input_layout")
            // The mic disc's own screen margin; the pill's left edge mirrors it or the corner clips.
            var sideFloor = 0
            if (send != null && send.width > 0) {
                val sr = Rect(0, 0, send.width, send.height)
                if (runCatching { footer.offsetDescendantRectToMyCoords(send, sr) }.isSuccess) {
                    sideFloor = (footer.width - sr.right).coerceAtLeast(0)
                }
            }
            convRect.set(0, 0, input.width, input.height)
            runCatching { footer.offsetDescendantRectToMyCoords(input, convRect) }
            // Grown so the icons can breathe, clamped because the footer is a ClippingLayout and shaves the overflow.
            val pad = footer.dp(COMPOSE_PILL_PAD_DP).toInt()
            composePane(
                footer, input,
                (convRect.left - pad).coerceAtLeast(sideFloor),
                (convRect.top - pad).coerceAtLeast(0),
                (convRect.right + pad).coerceAtMost(footer.width),
                (convRect.bottom + pad).coerceAtMost(footer.height),
                listHost,
            )
        }
        if (send != null && send.width > 0) {
            convRect.set(0, 0, send.width, send.height)
            runCatching { footer.offsetDescendantRectToMyCoords(send, convRect) }
            composePane(footer, send, convRect.left, convRect.top, convRect.right, convRect.bottom, listHost)
        }
    }

    /** Zero-size, never hide: syncPaneVisibility is the only visibility writer. Scoped by parent, the map also holds the header capsule. */
    private fun collapseConvPanes(footer: ViewGroup) {
        for (glass in convPanes.values) {
            if (glass.parent !== footer) continue
            val lp = glass.layoutParams ?: continue
            if (lp.width != 0 || lp.height != 0) {
                lp.width = 0
                lp.height = 0
                glass.layoutParams = lp
            }
        }
    }

    private fun composePane(
        footer: ViewGroup,
        owner: View,
        l: Int,
        t: Int,
        r: Int,
        b: Int,
        backdropView: ViewGroup?,
    ) {
        if (r <= l || b <= t) return
        var glass = convPanes[owner]
        if (glass == null || glass.parent !== footer) {
            glass = GlassView(footer.context).apply {
                backdrop = backdropView
                underlay = wallpaperUnderlay(footer)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = footer.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = footer.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTint(TINT_ALPHA)
                }
            }
            // Index 0, so WhatsApp's own icons and the text field keep painting on top of it.
            footer.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
            convPanes[owner] = glass
            logOnce("conversation compose pane inserted")
        } else if (glass.backdrop == null && backdropView != null) {
            glass.backdrop = backdropView
        }
        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != r - l || lp.height != b - t || lp.leftMargin != l || lp.topMargin != t) {
            lp.width = r - l
            lp.height = b - t
            lp.leftMargin = l
            lp.topMargin = t
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
            // Capped: height/2 on a two-row compose box sweeps the corners across the quote's thumbnail.
            glass.params.cornerRadius = minOf((b - t) / 2f, footer.dp(COMPOSE_MAX_RADIUS_DP))
        }
    }

    private val frostListenerTag = tagKey("wathemer-frost-listener")
    private val toolbarTitleTag = tagKey("wathemer-toolbar-title")
    private val hidTitleTag = tagKey("wathemer-hid-title")

    /** Frost an accent button with its own colour at partial alpha: the dark label needs the contrast, and WDSButton ignores setTextColor. */
    private fun frostAccent(v: View) {
        applyAccentFrost(v)
        if (v.getTag(accentFgTag) == null) {
            v.setTag(accentFgTag, true)
            v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                runCatching { applyAccentFrost(view) }
            }
        }
    }

    private val accentFgTag = tagKey("wathemer-accent-fg")
    private val accentColorTag = tagKey("wathemer-accent-color")

    /** How much of the button's own colour survives in the glass. */
    private const val ACCENT_ALPHA = 130

    private fun applyAccentFrost(v: View) {
        var accent = v.getTag(accentColorTag) as? Int
        if (accent == null) {
            accent = sampleFill(v) ?: return       // not laid out yet; try again next layout
            v.setTag(accentColorTag, accent)
        }
        frost(
            v,
            tintOverride = Color.argb(
                ACCENT_ALPHA, Color.red(accent), Color.green(accent), Color.blue(accent),
            ),
            allowSquare = true,                    // add_members_icon is a circle
        )

        // frost()'s plain background set is not enough here: the button re-applies its own opaque fill, so route it through forceBg.
        (v.getTag(frostTag) as? Drawable)?.let { d ->
            val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: "accent"
            forceBg(v, d, name)
        }
    }

    /** A drawable's effective fill colour; drawn [FILL_SAMPLE_PX] square because a rounded rect drawn into 1x1 rounds away to nothing. */
    private fun sampleFill(v: View): Int? {
        val d = v.background ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        return runCatching {
            val saved = Rect(d.bounds)
            val n = FILL_SAMPLE_PX
            val bmp = Bitmap.createBitmap(
                n, n, Bitmap.Config.ARGB_8888,
            )
            // Scale the canvas, keep the view's real bounds: a 24px box makes the rounded rect degenerate and it renders nothing.
            val c = Canvas(bmp)
            c.scale(n.toFloat() / v.width, n.toFloat() / v.height)
            d.setBounds(0, 0, v.width, v.height)
            d.draw(c)
            d.bounds = saved
            val px = bmp.getPixel(n / 2, n / 2)
            bmp.recycle()
            if (Color.alpha(px) < 32) null else px  // a ripple with no fill tells us nothing
        }.getOrNull()
    }

    private const val FILL_SAMPLE_PX = 24

    /** The Calls-disc treatment: a full-bounds circle, re-applied on layout. */
    private fun frostSquareOnLayout(v: View) {
        runCatching { frost(v, allowSquare = true, ignorePadding = true) }
        if (v.getTag(frostListenerTag) == null) {
            v.setTag(frostListenerTag, true)
            v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                runCatching { frost(view, allowSquare = true, ignorePadding = true) }
            }
        }
    }

    /** [frostOnLayout] with an explicit tint and radius; see the reply-quote call site. */
    private fun frostOnLayoutWith(v: View, tint: Int, radius: Float?) {
        val apply = Runnable { runCatching { frost(v, tintOverride = tint, radiusOverride = radius) } }
        apply.run()
        if (v.getTag(frostListenerTag) == null) {
            v.setTag(frostListenerTag, true)
            v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
        }
    }

    /** True when [id] names an ancestor within [depth] hops; scopes generic ids to one host. */
    private fun insideId(v: View, id: Int, depth: Int): Boolean {
        var p: View? = v.parent as? View
        var hops = 0
        while (p != null && hops < depth) {
            if (p.id == id) return true
            p = p.parent as? View
            hops++
        }
        return false
    }

    private val frostMoveTag = tagKey("wathemer-frost-move")

    /** offsetTopAndBottom moves rows without a redraw and a draw-time wallpaper patch freezes; invalidate on any screen move. */
    private fun watchFrostPosition(v: View) {
        if (v.getTag(frostMoveTag) != null) return
        v.setTag(frostMoveTag, true)
        FrostMoveWatch(v).arm()
    }

    /** Detaches with its view: one listener per recycled row, left registered, accumulates for the window's life. */
    private class FrostMoveWatch(private val host: View) :
        ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        private val at = IntArray(2)
        private var lastX = Int.MIN_VALUE
        private var lastY = Int.MIN_VALUE
        private var observing = false

        fun arm() {
            host.addOnAttachStateChangeListener(this)
            if (host.isAttachedToWindow) attach()
        }

        private fun attach() {
            if (observing) return
            host.viewTreeObserver.addOnPreDrawListener(this)
            observing = true
        }

        override fun onViewAttachedToWindow(v: View) = attach()

        override fun onViewDetachedFromWindow(v: View) {
            if (!observing) return
            host.viewTreeObserver.removeOnPreDrawListener(this)
            observing = false
        }

        override fun onPreDraw(): Boolean {
            if (host.background !is FrostDrawable || !host.isShown || host.width <= 0) return true
            host.getLocationOnScreen(at)
            if (at[0] != lastX || at[1] != lastY) {
                lastX = at[0]
                lastY = at[1]
                host.invalidate()
            }
            return true
        }
    }

    /** Blurred-wallpaper fill kept on layout; the film variant lets whatever is underneath bleed through. */
    private fun liquidFrostOnLayout(
        v: View,
        radiusDp: Float? = null,
        tintAlpha: Int = CHIP_ALPHA,
        /** For a wrap_content pill whose width comes from the stock drawable's own padding. */
        keepPadding: Boolean = false,
    ) {
        watchFrostPosition(v)
        val apply = Runnable {
            runCatching {
                if (keepPadding) keepStockPadding(v)
                if (v.height <= 0) return@runCatching
                val radius = radiusDp?.let { v.dp(it) } ?: (v.height / 2f)
                val existing = v.getTag(frostTag) as? FrostDrawable
                if (existing != null && v.background === existing) {
                    existing.setRadius(radius)
                    return@runCatching
                }
                val d = FrostDrawable(
                    v, bubbleBackdrop(v), radius, glassTint(tintAlpha),
                    strokeWidth = v.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
                    ignorePadding = true,
                )
                v.setTag(frostTag, d)
                v.background = d
            }
        }
        apply.run()
        if (v.getTag(frostListenerTag) == null) {
            v.setTag(frostListenerTag, true)
            v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
        }
    }

    /** [frostOnLayout] for square views; the aspect gate would drop them, a disc is the point here. */
    private fun frostCircleOnLayout(v: View) {
        val apply = Runnable { runCatching { frost(v, allowSquare = true, ignorePadding = true) } }
        apply.run()
        if (v.getTag(frostListenerTag) == null) {
            v.setTag(frostListenerTag, true)
            v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
        }
    }

    /** Chip treatment kept on layout: guard the registration, never the paint; recycled rows re-bind without a fresh attach. */
    private fun frostOnLayout(v: View) {
        runCatching { frost(v) }
        if (v.getTag(frostListenerTag) == null) {
            v.setTag(frostListenerTag, true)
            v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                runCatching { frost(view) }
            }
        }
    }

    // The corner arcs on the call-info header are the pane's own specular rim, not an elevation shadow.
    // If they are ever unwanted the levers are specStrength/specPower/light1/light2, not elevation.

    /** Hide the id-less WDSDividers inside a glassed bar, matched by class and scoped to it; INVISIBLE, not GONE, so nothing shifts. */
    private fun hideWdsDividers(root: ViewGroup) {
        fun walk(v: View) {
            if (v.javaClass.name.contains("WDSDivider")) {
                if (v.visibility == View.VISIBLE) {
                    v.visibility = View.INVISIBLE
                    logOnce("WDSDivider hidden")
                }
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i) ?: continue)
        }
        walk(root)
    }

    // forceBg keeps re-asserting its drawable; never pair this with a background you set yourself, it will null yours too.
    /** A TRANSPARENT ColorDrawable, never null: Material reads its background back, and mutate() on null crashes WhatsApp. */
    private fun clearBg(v: View, what: String) = forceBg(v, ColorDrawable(Color.TRANSPARENT), what)

    /** Round by outline clip, never by swapping the background, which discards the user's tint and fights recolorBg. */
    private fun roundView(v: View, what: String) {
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        v.clipToOutline = true
        v.invalidateOutline()
        XposedBridge.log("[$TAG] outline-clipped $what to a pill")
    }

    private fun forceBg(v: View, d: Drawable, what: String) {
        synchronized(forcedBgLabels) { forcedBgLabels[v] = what }
        val existing = synchronized(forcedBg) { forcedBg[v] }
        if (existing === d && v.background === d) return
        synchronized(forcedBg) { forcedBg[v] = d }
        v.background = d
        logOnce("background forced on $what")
    }

    /** One-time log lines: the re-apply sites fire repeatedly per launch and bury the geometry lines. */
    private val loggedOnce = Collections.synchronizedSet(HashSet<String>())

    private var dividerHookInstalled = false

    /** Suppress WDSToolbar's onDraw divider, scoped to toolbars in [forcedBg]; the rest of its onDraw draws nothing visible. */
    private fun ensureToolbarDividerHook(app: Application) {
        if (dividerHookInstalled) return
        dividerHookInstalled = true
        runCatching {
            val cls = XposedHelpers.findClass(
                "com.whatsapp.ui.wds.components.topbar.WDSToolbar", app.classLoader,
            )
            XposedHelpers.findAndHookMethod(
                cls, "onDraw", Canvas::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        // Only ours; a map probe keeps this hot path cheap.
                        if (synchronized(forcedBg) { forcedBg.containsKey(v) }) {
                            param.result = null
                            logOnce("WDSToolbar divider suppressed")
                        }
                    }
                },
            )
            XposedBridge.log("[$TAG] toolbar divider hook armed on WDSToolbar.onDraw")
        }.onFailure { XposedBridge.log("[$TAG] toolbar divider hook failed: $it") }
    }

    private fun logOnce(msg: String) {
        if (loggedOnce.add(msg)) XposedBridge.log("[$TAG] $msg")
    }

    /** Backgrounds we insist on, keyed by INSTANCE: an id key fired mid-construction and crashed mutate(); the every-View hook must stay cheap. */
    private val forcedBg = WeakHashMap<View, Drawable>()

    /** Human-readable names for [forcedBg]'s keys, for the log only. */
    private val forcedBgLabels = WeakHashMap<View, String>()

    private var bgHookInstalled = false

    @Synchronized
    private fun ensureBgHook() {
        if (bgHookInstalled) return
        bgHookInstalled = true
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java, "setBackground", Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        val incoming = param.args[0] as? Drawable

                        // Selection rows must see null too (WhatsApp clears them with it) and stay in this hook; a second interceptor started the foreground fights.
                        // Two ids: the Calls tab's rows are call_row_container, not the chat list's. Selected message rows are deliberately not handled.
                        if ((rowContainerId != 0 && v.id == rowContainerId) ||
                            (callRowContainerId != 0 && v.id == callRowContainerId)
                        ) {
                            if (incoming is ColorDrawable) {
                                param.args[0] = rowSelectionPane(v, incoming)
                                popRow(v, up = true)
                                if (v.getTag(rowScrollTag) == null) {
                                    val w = RowScrollWatch(v)
                                    v.setTag(rowScrollTag, w)
                                    w.arm()
                                }
                            } else if (incoming !is SelectionPane) {
                                popRow(v, up = false)
                            }
                            return
                        }

                        if (incoming == null) return
                        val want = synchronized(forcedBg) { forcedBg[v] } ?: return
                        if (incoming === want) return
                        val label = synchronized(forcedBgLabels) { forcedBgLabels[v] } ?: "view"
                        param.args[0] = want
                        logOnce("re-asserted background on $label")
                    }
                },
            )
        }.onFailure { XposedBridge.log("[$TAG] setBackground hook failed: $it") }

        // setBackgroundColor mutates an existing ColorDrawable in place and never reaches setBackground, so both setters are hooked.
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java, "setBackgroundColor", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == Color.TRANSPARENT) return
                        val v = param.thisObject as? View ?: return
                        val label = synchronized(forcedBgLabels) { forcedBgLabels[v] } ?: return
                        param.args[0] = Color.TRANSPARENT
                        logOnce("suppressed a re-applied bg COLOR on $label")
                    }
                },
            )
        }.onFailure { XposedBridge.log("[$TAG] setBackgroundColor hook failed: $it") }
    }

    /** Float the nav as a pill. The negative top margin is what puts rows behind the pill, an accepted tab-switch cost, not a shader problem. */
    private fun floatNav(container: View) {
        if (container.getTag(doneTag) != null) return
        container.setTag(doneTag, true)
        // Captured outside the listener below: it removes itself after one layout, the anchor is needed for the pane's life.
        navHostRef = WeakReference(container)
        registerFadeOnHide(container)

        container.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or_: Int, ob: Int,
            ) {
                val h = b - t
                val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
                if (h <= 0 || lp.topMargin < 0) return          // already floated
                v.removeOnLayoutChangeListener(this)

                val d = v.resources.displayMetrics.density
                val side = (16 * d).toInt()
                val lift = (16 * d).toInt()

                lp.leftMargin = side
                lp.rightMargin = side
                lp.bottomMargin = lift
                lp.topMargin = -(h + lift)                     // hand the space to id/content
                v.layoutParams = lp

                bottomInset = h + lift
                navGeom = intArrayOf(side, h, lift)
                // bottomInset before syncListCard, or the card bails and waits for the next global layout.
                syncListCard()
                applyLifts()
                injectNavGlass()

                XposedBridge.log(
                    "[$TAG] nav floated: h=$h side=$side lift=$lift topMargin=${lp.topMargin}"
                )
            }
        })
        container.requestLayout()
    }

    /** A search bar covering this header, whose own capsule is the one that should show. */
    private fun searchOverlayShown(content: ViewGroup): Boolean {
        val res = content.resources
        val pkg = content.context.packageName
        for (n in listOf("search_view", "search_fragment")) {
            val id = res.waId(n, pkg)
            if (id == 0) continue
            val v = content.rootView?.findViewById<View>(id) ?: continue
            if (v.isShown && v.height > 0) return true
        }
        return false
    }

    /** One pane over the union of the toolbar actions: per-button circles came out uneven, and ActionMenuView has no id to hang one on. */
    private fun registerAction(v: View) {
        if (chipAnchors.none { it.get() === v }) {
            chipAnchors.add(WeakReference(v))
            v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncActionsGlass() }
        }
        val content = contentRef?.get()
        val backdrop = pagerHolderRef?.get()
        val header = headerRef?.get()
        if (content != null && backdrop != null && header != null && content.isAttachedToWindow &&
            actionsGlassRef?.get()?.parent !== content
        ) {
            val glass = GlassView(content.context).apply {
                this.backdrop = backdrop
                this.underlay = wallpaperUnderlay(backdrop)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = content.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = content.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTint(TINT_ALPHA)
                }
            }
            content.addView(glass, content.indexOfChild(header), FrameLayout.LayoutParams(0, 0))
            actionsGlassRef = WeakReference(glass)
            XposedBridge.log("[$TAG] actions pane inserted")
        }
        // Bound to the header, which outlives the short-lived items; outside the branch above so a reused pane is re-bound (see [injectSearchPanel]).
        actionsGlassRef?.get()?.let { g -> headerRef?.get()?.let { bindPane(g, it, "toolbar actions") } }
        v.post { syncActionsGlass() }
    }

    private fun syncActionsGlass() {
        val content = contentRef?.get() ?: return
        val glass = actionsGlassRef?.get() as? GlassView ?: return
        // Re-resolve the live header and re-bind here: attach never re-fires after recreation, and a stale headerRef hid the pill for good.
        val liveHeader = (if (homeHeaderId != 0) content.findViewById<View>(homeHeaderId) else null)
            ?: headerRef?.get()
        liveHeader?.let { header ->
            if (headerRef?.get() !== header) headerRef = WeakReference(header)
            val bound = paneBindings.firstOrNull { it.pane.get() === glass }?.anchor?.get()
            if (bound !== header) bindPane(glass, header, "toolbar actions")
        }
        // A shown search bar owns the header; its buttons stay isShown under it and the unions overlap.
        val searchOverlay = searchOverlayShown(content)
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = Int.MIN_VALUE
        var b = Int.MIN_VALUE
        var n = 0
        // Dead anchors are pruned in-walk (main thread only); selection mode wins when any of its icons is shown, else the union spans both bars.
        var selectionMode = false
        for (ref in chipAnchors) {
            val a = ref.get() ?: continue
            if (a.getTag(actionGroupTag) != GROUP_SELECTION) continue
            // Same-window check: a chat left in selection mode keeps a shown action bar in its own decor for a beat after returning home.
            if (a.rootView !== content.rootView) continue
            if (a.isShown && a.width > 0) { selectionMode = true; break }
        }
        val anchors = chipAnchors.iterator()
        while (anchors.hasNext()) {
            val a = anchors.next().get()
            if (a == null) { anchors.remove(); continue }
            // isAttachedToWindow is the real filter: removed items keep size and VISIBLE, and the union spanned another tab's button.
            if (!a.isAttachedToWindow) continue
            // Skip anchors from another window root: a left Activity's menu stays attached, and the pill wore the shape of the screen you just left.
            if (a.rootView !== content.rootView) continue
            // isShown, not visibility == VISIBLE: a hidden ancestor leaves the item itself still reporting VISIBLE.
            if (a.width <= 0 || a.height <= 0 || !a.isShown) continue
            if (searchOverlay) continue
            // Only the group that owns the toolbar, plus the overflow button which is in both.
            val group = a.getTag(actionGroupTag)
            if (group != GROUP_BOTH &&
                group != (if (selectionMode) GROUP_SELECTION else GROUP_NORMAL)
            ) continue
            whitenActionIcons(a)
            for (icon in iconTargets(a)) {
                val rect = Rect(0, 0, icon.width, icon.height)
                if (!runCatching { content.offsetDescendantRectToMyCoords(icon, rect) }.isSuccess) {
                    // The contextual action bar is not under id/content, so the offset call throws; screen coordinates work whatever the hierarchy.
                    val la = IntArray(2)
                    val lc = IntArray(2)
                    icon.getLocationOnScreen(la)
                    content.getLocationOnScreen(lc)
                    rect.set(
                        la[0] - lc[0], la[1] - lc[1],
                        la[0] - lc[0] + icon.width, la[1] - lc[1] + icon.height,
                    )
                }
                l = minOf(l, rect.left)
                t = minOf(t, rect.top)
                r = maxOf(r, rect.right)
                b = maxOf(b, rect.bottom)
                n++
                }
        }
        // No anchor on screen: collapse by SIZE, never visibility, which belongs to [syncPaneVisibility] alone; a second writer would fight it.
        if (n == 0) {
            val lp = glass.layoutParams
            if (lp != null && (lp.width != 0 || lp.height != 0)) {
                lp.width = 0
                lp.height = 0
                glass.layoutParams = lp
            }
            return
        }
        // No vertical trim: the full touch-target height matches the horizontal gap; trimming read as uneven.
        if (r <= l || b <= t) return

        // Widen a lone action's union to a square so the half-height radius reads as a circle, not a lozenge.
        if (r - l < b - t) {
            val grow = (b - t) - (r - l)
            l -= grow / 2
            r = l + (b - t)
            if (l < 0) { r -= l; l = 0 }
            if (r > content.width) { l -= r - content.width; r = content.width }
        }

        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != r - l || lp.height != b - t ||
            lp.leftMargin != l - content.paddingLeft || lp.topMargin != t - content.paddingTop
        ) {
            lp.width = r - l
            lp.height = b - t
            lp.leftMargin = l - content.paddingLeft
            lp.topMargin = t - content.paddingTop
            glass.layoutParams = lp
            glass.params.cornerRadius = (b - t) / 2f
        }
    }

    /** The chat-list card, at index 0 of the page's own coordinator so it travels with the page; Chats-only, geometry in [syncListCard]. */
    private fun injectListPanel(parent: ViewGroup) {
        if (listPanelRef?.get()?.parent === parent) return
        if (!parent.isAttachedToWindow) return

        val glass = GlassView(parent.context).apply {
            underlay = wallpaperUnderlay(parent)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = parent.dp(BLUR_DP)
                cornerRadius = parent.dp(CARD_RADIUS_DP)
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = parent.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
        }
        // Zero-sized until syncListCard measures; plain MarginLayoutParams keeps out a compile-time coordinatorlayout dependency.
        parent.addView(glass, 0, ViewGroup.MarginLayoutParams(0, 0))
        listPanelRef = WeakReference(glass)
        syncListCard()
        XposedBridge.log("[$TAG] list card inserted at index 0 of ${parent.javaClass.simpleName}")
    }

    private val cardRect = Rect()
    private var loggedHostMismatch = false

    /** Shared card recipe for four surfaces; Chats is NOT one of them, its twin lives in [injectListPanel] and gets left behind. */
    private fun newCardGlass(parent: ViewGroup): GlassView = GlassView(parent.context).apply {
        underlay = wallpaperUnderlay(parent)
        params.apply {
            downsample = DOWNSAMPLE
            blurRadius = parent.dp(BLUR_DP)
            cornerRadius = parent.dp(CARD_RADIUS_DP)
            refractionEnabled = true
            bevelFraction = BEVEL_FRACTION
            depthRatio = DEPTH_RATIO
            maxDisplacePx = parent.dp(DISPLACE_DP)
            fresnelStrength = 0.5f
            tintColor = glassTintColor
        }
    }

    /** Updates card and big title in the page's FrameLayout; it stacks, so the list is moved down by paddingTop in [syncUpdatesCard]. */
    private fun injectUpdatesCard(host: ViewGroup) {
        if (host.getTag(updatesTitleTag) != null) return
        if (!host.isAttachedToWindow) return
        host.setTag(updatesTitleTag, true)

        val glass = newCardGlass(host)
        host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        updatesPanelRef = WeakReference(glass)

        val ctx = host.context
        val tv = TextView(ctx).apply {
            text = navLabel(1) ?: "Updates"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            typeface = Typeface.create(
                Typeface.DEFAULT, Typeface.BOLD,
            )
            setTextColor(primaryTextColor(ctx))
            includeFontPadding = false
            maxLines = 1
            setPadding(
                host.dp(16f).toInt(), host.dp(2f).toInt(),
                host.dp(16f).toInt(), host.dp(10f).toInt(),
            )
        }
        host.addView(
            tv,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        updatesTitleRef = WeakReference(tv)
        XposedBridge.log("[$TAG] updates card + title '${tv.text}' inserted")
    }

    /** Title clearance is header.height + 4dp to match the Chats title, and every clip edge is the list's own padding box. */
    private fun syncUpdatesCard() {
        val host = updatesHostRef?.get() ?: return
        val glass = updatesPanelRef?.get() as? GlassView ?: return
        val list = updatesListRef?.get() ?: return
        val title = updatesTitleRef?.get() ?: return
        val header = headerRef?.get() ?: return
        if (host.width <= 0 || host.height <= 0 || header.height <= 0) return
        val inset = bottomInset
        if (inset <= 0) return

        val d = host.resources.displayMetrics.density
        val side = (CARD_INSET_DP * d).toInt()
        val gap = (CARD_GAP_DP * d).toInt()

        // Captured before anything below mutates, and used by both mutation sites.
        val wasAtTop = !list.canScrollVertically(-1)

        val clearance = header.height + (4f * d).toInt()
        val tlp = title.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (tlp.topMargin != clearance) {
            tlp.topMargin = clearance
            title.layoutParams = tlp
            // The title is re-created on every visit, so this branch is what re-introduced the offset each time.
            if (wasAtTop) repinToTop(list)
            return              // re-enter once the title has been laid out at its new place
        }
        if (title.height <= 0) return

        val top = clearance + title.height + gap
        val bottom = host.height - inset - gap
        val right = host.width - side
        if (bottom - top < gap || right - side < gap) return

        // Padding first, because the clip below reads it. The pad is the content's breathing room inside the border, equal on all four sides.
        val pad = (CARD_CONTENT_PAD_DP * d).toInt()
        val padBottom = host.height - bottom + pad
        if (list.paddingLeft != side + pad || list.paddingTop != top + pad ||
            list.paddingRight != side + pad || list.paddingBottom != padBottom
        ) {
            list.setPadding(side + pad, top + pad, side + pad, padBottom)
            if (wasAtTop) repinToTop(list)
        }

        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != right - side || lp.height != bottom - top ||
            lp.leftMargin != side || lp.topMargin != top
        ) {
            lp.width = right - side
            lp.height = bottom - top
            lp.leftMargin = side
            lp.topMargin = top
            glass.layoutParams = lp
            XposedBridge.log("[$TAG] updates card $side,$top-$right,$bottom")
        }

        // The pad comes back off so the clip lands on the border while the content sits inside it.
        val cl = list.paddingLeft - pad
        val ct = list.paddingTop - pad
        val cr = list.width - list.paddingRight + pad
        val cb = list.height - list.paddingBottom + pad
        val provider = list.outlineProvider as? CardOutline
        if (provider == null) {
            list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
            list.clipToOutline = true
            list.invalidateOutline()
        } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
            // Gated on a real move: an unconditional invalidateOutline from a pre-draw path schedules the next frame forever.
            list.invalidateOutline()
        }
    }

    /** Size the card and clip the list to the same rect; runs on every global layout, so every write is guarded to stay idempotent. */
    private fun syncListCard() {
        val host = cardHostRef?.get() ?: return
        val glass = listPanelRef?.get() as? GlassView ?: return
        val list = listRef?.get() ?: return
        if (host.width <= 0 || host.height <= 0) return
        if (list.width <= 0 || list.height <= 0) return
        // Archived inflates this coordinator too and has no floating nav, so the inset is taken only when the nav is in THIS window.
        // Asked of the window structurally, never of an Activity name or of install order.
        val navInWindow = navContainerIdPin != 0 &&
            host.rootView?.findViewById<View>(navContainerIdPin) != null
        val inset = if (navInWindow) bottomInset else 0
        if (navInWindow && inset <= 0) return          // nav is here but not floated yet

        val d = host.resources.displayMetrics.density
        val side = (CARD_INSET_DP * d).toInt()
        val gap = (CARD_GAP_DP * d).toInt()

        // The list's origin in its own page, so this never crosses the pager boundary that cut the avatars off.
        val r = cardRect
        r.set(0, 0, list.width, list.height)
        if (!runCatching { host.offsetDescendantRectToMyCoords(list, r) }.isSuccess) return
        val listTop = r.top

        // bottomInset is measured in id/content; the page shares its bounds, and this logs once if that ever stops holding.
        val content = contentRef?.get()
        if (content != null && content.height != host.height && !loggedHostMismatch) {
            loggedHostMismatch = true
            XposedBridge.log(
                "[$TAG] card host height ${host.height} != id/content ${content.height}; " +
                    "the card's bottom edge assumes they match"
            )
        }

        // paddingTop already carries the content pad (syncListExtension owns it), so take it back off to land on the border.
        val pad = (CARD_CONTENT_PAD_DP * d).toInt()
        val top = listTop + list.paddingTop - pad
        val bottom = host.height - inset - gap
        val right = host.width - side
        if (bottom - top < gap || right - side < gap) return

        val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.width != right - side || lp.height != bottom - top ||
            lp.leftMargin != side || lp.topMargin != top
        ) {
            lp.width = right - side
            lp.height = bottom - top
            lp.leftMargin = side
            lp.topMargin = top
            glass.layoutParams = lp
            XposedBridge.log("[$TAG] list card $side,$top-$right,$bottom (r=${(CARD_RADIUS_DP * d).toInt()})")
        }

        // Inset the rows to the card; without the horizontal padding the clip slices the avatars.
        val padBottom = host.height - bottom + pad
        if (list.paddingLeft != side + pad || list.paddingRight != side + pad || list.paddingBottom != padBottom) {
            list.setPadding(side + pad, list.paddingTop, side + pad, padBottom)
        }

        // ── The clip is the list's own padding box, and nothing else ──────────────────
        // Derived any other way it crosses the pager boundary, sticks (child offsets skip layout), and cut the avatars off.
        // The pad comes back off so the clip lands on the card's border while the content sits inside it.
        val cl = list.paddingLeft - pad
        val ct = list.paddingTop - pad
        val cr = list.width - list.paddingRight + pad
        val cb = list.height - list.paddingBottom + pad
        val provider = list.outlineProvider as? CardOutline
        if (provider == null) {
            list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
            list.clipToOutline = true
            list.invalidateOutline()
        } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
            // Gated on a real move: an unconditional invalidateOutline from a pre-draw path schedules the next frame forever.
            list.invalidateOutline()
        }
    }

    // ── Folder pages that reuse the chat-list layout ───────────────────────────────────
    // Archived carries conversations_coordinator_layout but no header, nav or big title, so it takes its own card.
    // Its own refs too, so cardHostRef never follows the user off the home screen.
    private var folderHostRef: WeakReference<ViewGroup>? = null
    private var folderPanelRef: WeakReference<GlassView>? = null
    private val folderCardTag = tagKey("wathemer-folder-card")

    private fun injectFolderCard(host: ViewGroup) {
        if (host.getTag(folderCardTag) != null) return
        host.setTag(folderCardTag, true)
        val glass = newCardGlass(host)
        host.addView(glass, 0, ViewGroup.MarginLayoutParams(0, 0))
        folderHostRef = WeakReference(host)
        folderPanelRef = WeakReference(glass)
        // The home hub never fires here, so this card drives itself or it is sized once at attach and never again.
        host.viewTreeObserver.addOnGlobalLayoutListener { runCatching { syncFolderCard() } }
        XposedBridge.log("[$TAG] folder card inserted into ${host.javaClass.simpleName}")
    }

    /** One gap in from every side of the host. The list is resolved by id each pass, never remembered. */
    private fun syncFolderCard() {
        val host = folderHostRef?.get() ?: return
        val glass = folderPanelRef?.get() ?: return
        if (host.width <= 0 || host.height <= 0) return
        runCatching { ensureFolderHeader(host, "archived") }
        val list = host.findViewById<View>(android.R.id.list) ?: return
        if (list.width <= 0 || list.height <= 0) return

        val d = host.resources.displayMetrics.density
        val side = (CARD_INSET_DP * d).toInt()
        val gap = (CARD_GAP_DP * d).toInt()
        val pad = (CARD_CONTENT_PAD_DP * d).toInt()

        // The list fills the host whatever it holds, so the card hugs the rows' ink, clamped to the host's band.
        inkUnion.setEmpty()
        (list as? ViewGroup)?.let { g ->
            for (i in 0 until g.childCount) collectInk(g.getChildAt(i), 1)
        }
        if (inkUnion.isEmpty) {
            if (glass.visibility != View.GONE) glass.visibility = View.GONE
            return
        }
        if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE
        host.getLocationOnScreen(hostAt)

        // Scrollable takes the viewport box so the clip can be the card; short content keeps the hug.
        val scrollable = list.canScrollVertically(1) || list.canScrollVertically(-1)
        val top =
            if (scrollable) gap else maxOf(gap, inkUnion.top - hostAt[1] - pad)
        var bottom =
            if (scrollable) host.height - gap
            else minOf(host.height - gap, inkUnion.bottom - hostAt[1] + pad)
        // The same three-quarters snap as the content cards: almost-full reads as cut off mid air.
        if (!scrollable && (bottom - top) * 4 >= (host.height - gap - top) * 3) {
            bottom = host.height - gap
        }
        val right = host.width - side
        if (bottom - top < gap || right - side < gap) return

        // Card edges in the list's own coordinates, then the content pad on top of each.
        val padL = (side - list.left).coerceAtLeast(0) + pad
        val padT = (top - list.top).coerceAtLeast(0) + pad
        val padR = (list.left + list.width - right).coerceAtLeast(0) + pad
        if (list.paddingLeft != padL || list.paddingTop != padT || list.paddingRight != padR) {
            // Before the mutation: growing paddingTop leaves the list scrolled by what was added.
            val wasAtTop = !list.canScrollVertically(-1)
            (list as? ViewGroup)?.clipToPadding = false
            list.setPadding(padL, padT, padR, list.paddingBottom)
            if (wasAtTop) repinToTop(list)
        }

        val lp = glass.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.width != right - side || lp.height != bottom - top ||
            lp.leftMargin != side || lp.topMargin != top
        ) {
            lp.width = right - side
            lp.height = bottom - top
            lp.leftMargin = side
            lp.topMargin = top
            glass.layoutParams = lp
            XposedBridge.log("[$TAG] folder card $side,$top-$right,$bottom")
        }

        // The clip IS the card rect in the list's coordinates, all four edges.
        val cl = side - list.left
        val ct = top - list.top
        val cr = right - list.left
        val cb = bottom - list.top
        val provider = list.outlineProvider as? CardOutline
        if (provider == null) {
            list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
            list.clipToOutline = true
            list.invalidateOutline()
        } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
            list.invalidateOutline()
        }
    }

    // ── List pages whose own root cannot host a pane ───────────────────────────────────
    // None of their roots stack, so android.R.id.content hosts: a later sibling of the wallpaper, its index 0 sits above it and below the page.
    // A framework id, not a WhatsApp one, so it is looked up directly and never through waId.
    private val contentCards = WeakHashMap<View, GlassView>()
    private val contentCardTag = tagKey("wathemer-content-card")
    private val contentCardRect = Rect()
    private val inkUnion = Rect()
    private val inkAt = IntArray(2)
    private val hostAt = IntArray(2)

    /** Screen-px union of everything under [v] that actually paints; a group that only holds children is not ink.
     *  A transparent background is not ink either: the wallpaper protocol installs ColorDrawable(0) all over this tree. */
    private fun collectInk(v: View, depth: Int) {
        // GONE, not "other than VISIBLE": an INVISIBLE view reserves its box and turning it on is no layout.
        if (v.visibility == View.GONE || v.width <= 0 || v.height <= 0) return
        val group = v as? ViewGroup
        if (group != null && depth < 6 && group.childCount > 0) {
            for (i in 0 until group.childCount) collectInk(group.getChildAt(i), depth + 1)
            if (!paintsSomething(v)) return
        }
        v.getLocationOnScreen(inkAt)
        inkUnion.union(inkAt[0], inkAt[1], inkAt[0] + v.width, inkAt[1] + v.height)
    }

    private fun paintsSomething(v: View): Boolean {
        val bg = v.background ?: return false
        if (bg is ColorDrawable && (bg.color ushr 24) == 0) return false
        return bg.alpha != 0
    }

    /** frameCard is for grids that own their geometry: the VIEW is inset by margins and the card wraps its frame, so nothing inside is ever mutated. */
    private fun injectContentCard(list: View, label: String, frameCard: Boolean = false) {
        if (list.getTag(contentCardTag) != null) return
        val host = list.rootView?.findViewById<ViewGroup>(android.R.id.content) ?: run {
            logOnce("$label: no android.R.id.content in this window, no card")
            return
        }
        if (!canStack(host)) {
            logOnce("$label: content is ${host.javaClass.simpleName}, which does not stack, no card")
            return
        }
        // A page painting its own full-size background buries a card at index 0; glass under an opaque page is work with no pixels.
        var anc: View? = list
        while (anc != null && anc !== host) {
            if (paintsSomething(anc) && anc.height >= host.height / 2 &&
                (anc.background !is ColorDrawable)
            ) {
                logOnce("$label paints its own background (${anc.javaClass.simpleName}), no card")
                return
            }
            anc = anc.parent as? View
        }
        list.setTag(contentCardTag, true)
        val glass = newCardGlass(host)
        host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        contentCards[list] = glass
        if (!frameCard) {
            (list as? ViewGroup)?.clipToPadding = false
            // A ListView's own divider reads as a seam over glass; transparent rather than null keeps the spacing.
            (list as? ListView)?.let { lv ->
                val h = lv.dividerHeight
                lv.divider = ColorDrawable(0)
                lv.dividerHeight = h
            }
        }
        val ref = WeakReference(list)
        host.viewTreeObserver.addOnGlobalLayoutListener {
            runCatching { ref.get()?.let { syncContentCard(it, label, frameCard) } }
        }
        if (frameCard) {
            // The appbar collapses because the grid REPORTS its scroll to it; cut the link and it cannot.
            list.isNestedScrollingEnabled = false
            runCatching { pinGalleryAppbar(host, label) }
            // Nothing in a pager page stacks, so the card follows the grid's live screen X from pre-draw: two reads at rest, a write on swipe.
            val glassRef = WeakReference(glass)
            val obs = host.viewTreeObserver
            val gAt = IntArray(2)
            val hAt = IntArray(2)
            obs.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    val grid = ref.get()
                    val gl = glassRef.get()
                    if (grid == null || gl == null || gl.parent == null) {
                        (if (obs.isAlive) obs else host.viewTreeObserver)
                            .removeOnPreDrawListener(this)
                        return true
                    }
                    if (!grid.isAttachedToWindow || gl.visibility != View.VISIBLE) return true
                    val fg = (gl.getTag(frameGapTag) as? Int) ?: 0
                    grid.getLocationOnScreen(gAt)
                    host.getLocationOnScreen(hAt)
                    val tx = (gAt[0] - fg - (hAt[0] + gl.left)).toFloat()
                    if (abs(tx - gl.translationX) > 0.5f) gl.translationX = tx
                    return true
                }
            })
        }
        XposedBridge.log("[$TAG] $label card inserted into ${host.javaClass.simpleName}")
    }

    private val frameGapTag = tagKey("wathemer-frame-gap")
    private val appbarPinTag = tagKey("wathemer-appbar-pin")

    /** The grid's own spacing, so the card's breathing room matches what sits between two thumbnails. */
    private fun gridItemGap(grid: View, d: Float): Int {
        val g = grid as? ViewGroup ?: return (3f * d).toInt()
        var best = -1
        val cap = (24f * d).toInt()
        for (i in 0 until g.childCount) {
            val a = g.getChildAt(i) ?: continue
            for (j in i + 1 until g.childCount) {
                val b = g.getChildAt(j) ?: continue
                if (a.top == b.top && b.left >= a.right) {
                    val gp = b.left - a.right
                    if (gp in 1..cap && (best < 0 || gp < best)) best = gp
                }
            }
        }
        return if (best > 0) best else (3f * d).toInt()
    }

    /** Stock scrolls the gallery header under the status bar; pinned so it stays readable. */
    private fun pinGalleryAppbar(host: ViewGroup, label: String) {
        val appbarId = host.resources.waId("appbar", host.context.packageName)
        val appbar =
            (if (appbarId != 0) host.rootView?.findViewById<ViewGroup>(appbarId) else null) ?: return
        if (appbar.getTag(appbarPinTag) != null) return
        appbar.setTag(appbarPinTag, true)
        var flagged = 0
        for (i in 0 until appbar.childCount) {
            val c = appbar.getChildAt(i) ?: continue
            // Reflective: AppBarLayout.LayoutParams is Material, which this module does not link.
            if (runCatching { XposedHelpers.callMethod(c.layoutParams, "setScrollFlags", 0) }.isSuccess) {
                flagged++
            }
        }
        appbar.requestLayout()
        // The count tells R8's verdict apart from a working pin; nested-scroll-off is the real stop.
        logOnce("$label appbar pin: $flagged/${appbar.childCount} flags cleared")
    }

    private val pageCardWatchTag = tagKey("wathemer-page-card-watch")

    /** Pages that load their list async miss the one post; watch layouts until it shows, bounded. */
    private fun watchForPageScroller(content: ViewGroup, label: String) {
        if (content.getTag(pageCardWatchTag) != null) return
        content.setTag(pageCardWatchTag, true)
        val obs = content.viewTreeObserver
        obs.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            private var tries = 0
            override fun onGlobalLayout() {
                val done = runCatching {
                    findPageScroller(content)?.let { injectContentCard(it, label); true } ?: false
                }.getOrDefault(false)
                tries++
                if (done || tries >= 40) {
                    // Remove through the observer captured at registration; a dead one silently no-ops.
                    (if (obs.isAlive) obs else content.viewTreeObserver)
                        .removeOnGlobalLayoutListener(this)
                    if (!done) logOnce("$label: no scroller found after $tries layouts, no card")
                }
            }
        })
    }

    /** By name up the chain: WhatsApp's androidx copies live in another classloader, so `is` cannot see them. */
    private fun isAppScroller(v: View): Boolean {
        var c: Class<*>? = v.javaClass
        while (c != null) {
            when (c.name) {
                "androidx.recyclerview.widget.RecyclerView",
                "androidx.core.widget.NestedScrollView" -> return true
            }
            c = c.superclass
        }
        return false
    }

    /** The page's main scroller: the first shown vertical scrolling container of real height. */
    private fun findPageScroller(content: ViewGroup): View? {
        val queue = ArrayDeque<View>()
        queue.add(content)
        var depth = 0
        while (queue.isNotEmpty() && depth < 400) {
            depth++
            val v = queue.removeFirst()
            if (v !== content && v.isShown && v.height > content.height / 2 && (
                    v is ScrollView ||
                        v is AbsListView ||
                        isAppScroller(v)
                    )
            ) {
                return v
            }
            (v as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) queue.add(g.getChildAt(i)) }
        }
        return null
    }

    /** True when this row shows an icon, so its indent is earning the space it takes. */
    private fun rowHasIcon(v: View, depth: Int): Boolean {
        if (v is ImageView && v.visibility == View.VISIBLE) return true
        if (depth >= 2 || v !is ViewGroup) return false
        for (i in 0 until v.childCount) {
            if (rowHasIcon(v.getChildAt(i) ?: continue, depth + 1)) return true
        }
        return false
    }

    /** Iconless settings rows still indent to the icon column, far right of their own heading. */
    private fun alignIconlessRows(v: View, gutter: Int, want: Int, depth: Int) {
        val g = v as? ViewGroup ?: return
        for (i in 0 until g.childCount) {
            val row = g.getChildAt(i) ?: continue
            if (row is ViewGroup && row.paddingLeft >= gutter && !rowHasIcon(row, 0)) {
                row.setPadding(want, row.paddingTop, row.paddingRight, row.paddingBottom)
            } else if (depth < 3) {
                // Rows sit three deep here, and a two level walk found none of them.
                alignIconlessRows(row, gutter, want, depth + 1)
            }
        }
    }

    /** Wraps the list itself, so an empty page whose list is GONE gets no slab. */
    private fun syncContentCard(list: View, label: String, frameCard: Boolean = false) {
        val glass = contentCards[list] ?: return
        val host = glass.parent as? ViewGroup ?: return
        if (host.width <= 0 || host.height <= 0) return
        // Before the empty early-out: the header band belongs to the window, not to the list's content.
        runCatching { ensureFolderHeader(host, label) }
        // The list is GONE on an empty Starred or Broadcast page; a card behind nothing is a blank slab.
        if (!list.isShown || list.width <= 0 || list.height <= 0) {
            if (glass.visibility != View.GONE) glass.visibility = View.GONE
            return
        }
        if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE

        // The frame card insets the VIEW, never its insides: headers and rows move together, so the sticky copy cannot drift.
        // The card then CONTAINS the frame by the grid's own item gap, so thumbnails breathe off its border.
        var frameGap = 0
        if (frameCard) {
            val dd = host.resources.displayMetrics.density
            frameGap = gridItemGap(list, dd)
            glass.setTag(frameGapTag, frameGap)
            val want = (CARD_INSET_DP * dd).toInt() + frameGap
            val mlp = list.layoutParams as? ViewGroup.MarginLayoutParams
            if (mlp != null && (mlp.leftMargin != want || mlp.rightMargin != want)) {
                mlp.leftMargin = want
                mlp.rightMargin = want
                list.layoutParams = mlp
                return    // re-syncs on the layout this causes, with the frame settled
            }
        }

        val r = contentCardRect
        r.set(0, 0, list.width, list.height)
        if (!runCatching { host.offsetDescendantRectToMyCoords(list, r) }.isSuccess) return
        // The mapping subtracts the view's OWN scroll, so a scrolled ScrollView lands the card off screen; child-scrolling lists keep scrollY 0.
        r.offset(list.scrollX, list.scrollY)

        val d = host.resources.displayMetrics.density
        val side = (CARD_INSET_DP * d).toInt()
        val gap = (CARD_GAP_DP * d).toInt()
        val pad = (CARD_CONTENT_PAD_DP * d).toInt()

        // A row is often a full-height container with a picture in the middle, so its box is not its content; union what the rows ink.
        // From the ROWS, not the list: the list fills its viewport, so starting there is a slab.
        inkUnion.setEmpty()
        (list as? ViewGroup)?.let { g ->
            for (i in 0 until g.childCount) collectInk(g.getChildAt(i), 1)
        }
        if (inkUnion.isEmpty) {                        // nothing laid out yet, or nothing to card
            if (glass.visibility != View.GONE) glass.visibility = View.GONE
            return
        }
        host.getLocationOnScreen(hostAt)
        val inkTop = inkUnion.top - hostAt[1]
        val inkBottom = inkUnion.bottom - hostAt[1]

        // A scrollable page takes the viewport box: an ink-tied top cannot be the clip, and ink chased through padding is a runaway recurrence.
        // Short content keeps the hug; nothing scrolls, so nothing can leak.
        val scrollable = list.canScrollVertically(1) || list.canScrollVertically(-1)
        // The frame card wraps AROUND the inset frame; the list card sits INSIDE the full-width box.
        val l = if (frameCard) r.left - frameGap else r.left + side
        val right = if (frameCard) r.right + frameGap else r.right - side
        val t = when {
            frameCard -> maxOf(gap, r.top - frameGap)
            scrollable -> r.top + gap
            else -> maxOf(r.top + gap, inkTop - pad)
        }
        val full = if (frameCard) r.bottom + frameGap else r.bottom - gap
        var bottom = when {
            frameCard && scrollable -> full
            frameCard -> inkBottom + frameGap
            scrollable -> full
            else -> minOf(full, inkBottom + pad)
        }
        // A bottom edge just short of the viewport reads as cut off mid air, so a hug filling three quarters of the box snaps to the full box.
        if (!scrollable && (bottom - t) * 4 >= (full - t) * 3) bottom = full
        // The gallery grid runs taller than the window, and a card ending off screen has no bottom edge.
        if (frameCard) bottom = minOf(bottom, host.height - gap)
        if (right - l < gap || bottom - t < gap) return

        // Horizontal padding puts the rows inside the card; none on the bottom, where the card ends at the rows and padding would chase itself.
        // Not on a frame-carded grid: the gallery lays its own edges, and the write doubled its sticky headers.
        if (!frameCard &&
            (list.paddingLeft != side + pad || list.paddingTop != gap + pad ||
                list.paddingRight != side + pad)
        ) {
            // Before the mutation: growing paddingTop leaves the list scrolled by what was added.
            val wasAtTop = !list.canScrollVertically(-1)
            list.setPadding(side + pad, gap + pad, side + pad, list.paddingBottom)
            if (wasAtTop) repinToTop(list)
        }
        if (!frameCard) runCatching {
            val den = list.resources.displayMetrics.density
            alignIconlessRows(list, (64f * den).toInt(), (24f * den).toInt(), 0)
        }

        // Margins measure from the host's padding box, which carries the status bar inset, so a margin of t alone lands a status bar too low.
        val ml = l - host.paddingLeft
        val mt = t - host.paddingTop
        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != right - l || lp.height != bottom - t ||
            lp.leftMargin != ml || lp.topMargin != mt
        ) {
            lp.width = right - l
            lp.height = bottom - t
            lp.leftMargin = ml
            lp.topMargin = mt
            glass.layoutParams = lp
            XposedBridge.log("[$TAG] $label card $l,$t-$right,$bottom (margin $ml,$mt)")
        }

        // The frame card CONTAINS the frame, so the view's own bounds cannot out-draw it; no clip.
        if (frameCard) return

        // The clip IS the card rect in the list's coordinates, all four edges; derived any other way it drifts off the card.
        val cl = l - r.left
        val ct = t - r.top
        val cr = right - r.left
        val cb = bottom - r.top
        val provider = list.outlineProvider as? CardOutline
        if (provider == null) {
            list.outlineProvider = CardOutline(cl, ct, cr, cb, CARD_RADIUS_DP * d)
            list.clipToOutline = true
            list.invalidateOutline()
        } else if (provider.set(cl, ct, cr, cb, CARD_RADIUS_DP * d)) {
            list.invalidateOutline()
        }
    }

    // Its own pad: these blocks are dense ink to every edge, and the shared 8dp read cramped on sight.
    private const val BLOCK_CONTENT_PAD_DP = 10f
    private val blockCards = WeakHashMap<View, GlassView>()
    private val blockCardTag = tagKey("wathemer-block-card")
    private val folderFabTag = tagKey("wathemer-folder-fab")

    /** A sibling block above a carded list, like the broadcast quota stats; same material, own pane. */
    private fun injectBlockCard(block: View, label: String) {
        if (block.getTag(blockCardTag) != null) return
        val host = block.rootView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (!canStack(host)) {
            logOnce("$label: content is ${host.javaClass.simpleName}, which does not stack, no panel")
            return
        }
        block.setTag(blockCardTag, true)
        val glass = newCardGlass(host)
        host.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
        blockCards[block] = glass
        val ref = WeakReference(block)
        host.viewTreeObserver.addOnGlobalLayoutListener {
            runCatching { ref.get()?.let { syncBlockCard(it, label) } }
        }
        XposedBridge.log("[$TAG] $label panel inserted into ${host.javaClass.simpleName}")
    }

    /** Sides pinned to the list card's inset so the two edges align; top and bottom hug the block's ink. */
    private fun syncBlockCard(block: View, label: String) {
        val glass = blockCards[block] ?: return
        val host = glass.parent as? ViewGroup ?: return
        if (host.width <= 0 || host.height <= 0) return
        if (!block.isShown || block.width <= 0 || block.height <= 0) {
            if (glass.visibility != View.GONE) glass.visibility = View.GONE
            return
        }
        if (glass.visibility != View.VISIBLE) glass.visibility = View.VISIBLE
        // From here, not from inject: attach is not layout, and the divider's height reads 0 there.
        // A rule reads as a seam over glass; INVISIBLE keeps the row's height.
        block.findViewById<View>(block.resources.waId("divider", "com.whatsapp"))?.let {
            if (it.height in 1..8 && it.visibility == View.VISIBLE) it.visibility = View.INVISIBLE
        }

        inkUnion.setEmpty()
        collectInk(block, 1)
        if (inkUnion.isEmpty) return
        host.getLocationOnScreen(hostAt)

        val d = host.resources.displayMetrics.density
        val side = (CARD_INSET_DP * d).toInt()
        val gap = (CARD_GAP_DP * d).toInt()
        val pad = (BLOCK_CONTENT_PAD_DP * d).toInt()

        // The pad wins over edge alignment: sides start at the card inset and move outward when the block's ink sits closer than the pad.
        val l = minOf(side, inkUnion.left - hostAt[0] - pad)
        val right = maxOf(host.width - side, inkUnion.right - hostAt[0] + pad)
        val t = maxOf(gap, inkUnion.top - hostAt[1] - pad)
        val bottom = inkUnion.bottom - hostAt[1] + pad
        if (right - l < gap || bottom - t < gap) return

        val ml = l - host.paddingLeft
        val mt = t - host.paddingTop
        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != right - l || lp.height != bottom - t ||
            lp.leftMargin != ml || lp.topMargin != mt
        ) {
            lp.width = right - l
            lp.height = bottom - t
            lp.leftMargin = ml
            lp.topMargin = mt
            glass.layoutParams = lp
            XposedBridge.log("[$TAG] $label panel $l,$t-$right,$bottom")
        }
    }

    // ── Folder header: the chat screen's band and capsule, one pair per window ─────────
    // One band only: two abutting panes cannot be seamless, each blur kernel is clipped to its own capture.
    private val folderBands = WeakHashMap<View, GlassView>()
    private val folderCapsules = WeakHashMap<View, GlassView>()
    private val folderHeaderAt = IntArray(2)
    private val folderHeaderAbrAt = IntArray(2)

    /** Solid over the status bar and toolbar, then the chat screen's fade; a capsule under the toolbar's content. */
    private fun ensureFolderHeader(anchor: View, label: String) {
        val root = anchor.rootView ?: return
        val content = root.findViewById<ViewGroup>(android.R.id.content) ?: return
        val under = wallpaperUnderlay(content)
        if (under.isEmpty()) return
        // Settings hides its toolbar behind a persistent search bar, so the shown bar decides the band's reach; a capsule needs a shown toolbar.
        val toolbar = (if (homeToolbarId != 0) root.findViewById<View>(homeToolbarId) else null)
            ?.takeIf { it.isShown && it.height > 0 }
        val searchBarId = content.resources.waId("wds_search_bar", content.context.packageName)
        val headerBar = toolbar
            ?: (if (searchBarId != 0) root.findViewById<View>(searchBarId) else null)
                ?.takeIf { it.isShown && it.height > 0 }
            ?: return
        val abrId = content.resources.waId("action_bar_root", content.context.packageName)
        val abr = (if (abrId != 0) root.findViewById<View>(abrId) else null) as? FrameLayout ?: return

        headerBar.getLocationOnScreen(folderHeaderAt)
        abr.getLocationOnScreen(folderHeaderAbrAt)
        val solid = folderHeaderAt[1] - folderHeaderAbrAt[1] + headerBar.height
        if (solid <= 0) return
        val total = solid

        var band = folderBands[abr]
        if (band == null || band.parent !== abr) {
            band = GlassView(abr.context).apply {
                underlay = under
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = abr.dp(BLUR_DP)
                    refractionEnabled = true
                    // With the SDF expanded there is no bevel to size; a 1px nominal one keeps depth and displacement at zero.
                    bevelFraction = 0f
                    bevelThickness = 1f
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = abr.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    cornerRadius = 0f
                    // Full-bleed panel; a rim here would outline the screen.
                    rimEnabled = false
                    edgeExpandLeft = 8f
                    edgeExpandTop = 8f
                    edgeExpandRight = 8f
                    edgeExpandBottom = 8f
                }
            }
            var insertAt = 0
            for (i in 0 until abr.childCount) {
                val c = abr.getChildAt(i)
                if (c === under.firstOrNull() || (under.size > 1 && c === under[1])) insertAt = i + 1
            }
            if (insertAt == 0) {
                XposedBridge.log("[$TAG] WARN: $label wallpaper views are not children of action_bar_root")
            }
            abr.addView(
                band, insertAt,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, total).apply {
                    gravity = Gravity.TOP
                },
            )
            folderBands[abr] = band
            XposedBridge.log("[$TAG] $label band inserted (solid=${solid}px)")
        }
        band.params.tintColor = glassTint(convBandAlpha())
        (band.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.height != total) { lp.height = total; band.layoutParams = lp }
        }

        // No capsule without a shown toolbar; the settings search pill is its own surface.
        if (toolbar == null) return
        var cap = folderCapsules[abr]
        if (cap == null || cap.parent !== abr) {
            cap = GlassView(abr.context).apply {
                underlay = under
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = abr.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = abr.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                }
            }
            // Directly above the band, still under every app view.
            abr.addView(cap, abr.indexOfChild(band) + 1, FrameLayout.LayoutParams(0, 0))
            folderCapsules[abr] = cap
            XposedBridge.log("[$TAG] $label capsule inserted")
        }
        // The remainder of the tint budget; band plus capsule sum to TINT_ALPHA exactly, including 0.
        cap.params.tintColor = glassTint((TINT_ALPHA - convBandAlpha()).coerceAtLeast(0))
        val inset = (CONV_PILL_INSET_DP * abr.resources.displayMetrics.density).toInt()
        val trim = (4 * abr.resources.displayMetrics.density).toInt()
        val l = folderHeaderAt[0] - folderHeaderAbrAt[0] + inset
        val t = folderHeaderAt[1] - folderHeaderAbrAt[1] + trim
        val w = toolbar.width - 2 * inset
        val h = toolbar.height - 2 * trim
        if (w < inset || h < trim) return
        if (cap.params.cornerRadius != h / 2f) cap.params.cornerRadius = h / 2f
        (cap.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (lp.width != w || lp.height != h || lp.leftMargin != l || lp.topMargin != t) {
                lp.width = w
                lp.height = h
                lp.leftMargin = l
                lp.topMargin = t
                lp.gravity = Gravity.TOP or Gravity.START
                cap.layoutParams = lp
                XposedBridge.log("[$TAG] $label capsule $l,$t ${w}x$h")
            }
        }
    }

    // The folder pages' WDSFab, treated like home's: field-poked clear, flattened, a pane behind it.
    private val folderFabAt = IntArray(2)

    private fun folderFab(fab: View, label: String) {
        if (fab.width <= 0 || fab.height <= 0) return
        val content = fab.rootView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (!canStack(content)) return
        flattenFab(fab)
        ensureWdsFabFields(fab)
        val f = wdsFabTintField ?: return
        if (runCatching { f.get(fab) }.getOrNull() !== fabTint) {
            runCatching { f.set(fab, fabTint) }
            fab.backgroundTintList = fabTint
            wdsFabIconField?.let { icon ->
                runCatching { icon.set(fab, fabIconTint) }
                (fab as? ImageView)?.imageTintList = fabIconTint
            }
            XposedBridge.log("[$TAG] $label fab cleared (${fab.width}x${fab.height})")
        }
        var glass = fabGlass[fab]
        if (glass == null || glass.parent !== content) {
            glass = GlassView(content.context).apply {
                underlay = wallpaperUnderlay(content)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = content.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = content.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTint(FAB_ALPHA)
                    cornerRadius = content.dp(FAB_RADIUS_DP)
                }
            }
            content.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
            fabGlass[fab] = glass
            bindPane(glass, fab, "$label fab pane")
            XposedBridge.log("[$TAG] $label fab pane inserted (${fab.width}x${fab.height})")
        }
        if (glass.pressSource !== fab) glass.pressSource = fab
        // Screen coords, then into the host's margin space; margins measure from the padding box.
        fab.getLocationOnScreen(folderFabAt)
        content.getLocationOnScreen(hostAt)
        val ml = folderFabAt[0] - hostAt[0] - content.paddingLeft
        val mt = folderFabAt[1] - hostAt[1] - content.paddingTop
        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != fab.width || lp.height != fab.height ||
            lp.leftMargin != ml || lp.topMargin != mt
        ) {
            lp.width = fab.width
            lp.height = fab.height
            lp.leftMargin = ml
            lp.topMargin = mt
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
        }
        val want = if (paneShouldShow(fab)) View.VISIBLE else View.GONE
        if (glass.visibility != want) glass.visibility = want
    }

    /** Mutable and reused: the provider is asked for the outline on every invalidateOutline. */
    private class CardOutline(
        private var l: Int,
        private var t: Int,
        private var r: Int,
        private var b: Int,
        private var radius: Float,
    ) : ViewOutlineProvider() {
        /** True only when something moved; an unconditional invalidateOutline from a pre-draw path schedules the next frame forever. */
        fun set(l: Int, t: Int, r: Int, b: Int, radius: Float): Boolean {
            if (this.l == l && this.t == t && this.r == r && this.b == b && this.radius == radius) {
                return false
            }
            this.l = l; this.t = t; this.r = r; this.b = b; this.radius = radius
            return true
        }

        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(l, t, r, b, radius)
        }
    }

    /** A pane behind the nav pill, in id/content; an opaque user nav colour still wins and stays pill-shaped. */
    private fun injectNavGlass() {
        val parent = contentRef?.get() ?: return
        if (!parent.isAttachedToWindow) return
        val backdrop = pagerHolderRef?.get() ?: return
        val g = navGeom ?: return
        if (navGlassRef?.get()?.parent === parent) {
            // Re-bind even when present: floatNav re-runs per nav recreation and the pane would stay tied to the old one (see [injectSearchPanel]).
            navGlassRef?.get()?.let { pane -> navHostRef?.get()?.let { bindPane(pane, it, "nav pill") } }
            return
        }

        val side = g[0]
        val h = g[1]
        val lift = g[2]

        val glass = GlassView(parent.context).apply {
            this.backdrop = backdrop
            this.underlay = wallpaperUnderlay(backdrop)
            params.apply {
                downsample = DOWNSAMPLE
                blurRadius = parent.dp(BLUR_DP)
                cornerRadius = h / 2f
                refractionEnabled = true
                bevelFraction = BEVEL_FRACTION
                depthRatio = DEPTH_RATIO
                maxDisplacePx = parent.dp(DISPLACE_DP)
                fresnelStrength = 0.5f
                tintColor = glassTintColor
            }
        }
        val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h).apply {
            leftMargin = side
            rightMargin = side
            bottomMargin = lift
            gravity = Gravity.BOTTOM
        }
        parent.addView(glass, parent.indexOfChild(backdrop) + 1, lp)
        navGlassRef = WeakReference(glass)
        // Bound to the nav container, not the parent: id/content stays on screen during search, which stranded this pane.
        navHostRef?.get()?.let { bindPane(glass, it, "nav pill") }
        XposedBridge.log("[$TAG] nav glass restored (h=$h side=$side lift=$lift, pill r=${h / 2})")
    }

    /** Lift the FABs clear of the pill with ONE group shift from the lowest button; per-button clamping collapsed them onto each other. */
    private fun applyLifts() {
        val content = contentRef?.get() ?: return
        val inset = bottomInset
        if (inset <= 0 || content.height <= 0) return
        val gap = (16 * content.resources.displayMetrics.density).toInt()
        val limit = content.height - inset - gap

        var shift = 0
        for (id in fabIds) {
            val v = content.findViewById<View>(id) ?: continue
            if (v.parent !== content || v.height <= 0) continue
            shift = maxOf(shift, v.bottom - limit)
        }
        val want = -shift.coerceAtLeast(0).toFloat()
        for (id in fabIds) {
            val v = content.findViewById<View>(id) ?: continue
            if (v.parent !== content || v.height <= 0) continue
            if (v.translationY != want) v.translationY = want
            runCatching { glassFab(v) }
                .onFailure { XposedBridge.log("[$TAG] glassFab threw: $it") }
        }
    }

    /** Fully transparent: the pane behind it is the button, not a tint on top of it. */
    private val fabTint = ColorStateList.valueOf(Color.TRANSPARENT)
    private val fabGlass = WeakHashMap<View, GlassView>()

    /** The glyph ships dark; whitened through the A04 field because WDSFab's setImageTintList ignores its argument. */
    private val fabIconTint = ColorStateList.valueOf(Color.WHITE)
    private var wdsFabTintField: Field? = null
    private var wdsFabIconField: Field? = null
    private var wdsFabElevField: Field? = null
    private var wdsFabFieldResolved = false

    /** WDSFab ignores its background and tint setters, the A03 field is the way in; stands down entirely when the user set a FAB colour. */
    private fun glassFab(v: View) {
        if (v.width <= 0 || v.height <= 0) return

        // ExtendedMiniFab is the ViewGroup; every FAB proper is an ImageView.
        if (v is ViewGroup) {
            if (miniFabColored) return
            flattenFab(v)
            clearBg(v, "extended_mini_fab")
        } else {
            if (fabColored) return
            flattenFab(v)
            ensureWdsFabFields(v)
            val f = wdsFabTintField ?: return
            if (runCatching { f.get(v) }.getOrNull() !== fabTint) {
                // setWdsFabStyle rebuilds A03 from the style; the identity check keeps this every-layout re-apply cheap.
                runCatching { f.set(v, fabTint) }
                v.backgroundTintList = fabTint
                wdsFabIconField?.let { icon ->
                    runCatching { icon.set(v, fabIconTint) }
                    (v as? ImageView)?.imageTintList = fabIconTint
                }
                XposedBridge.log("[$TAG] fab cleared (${v.width}x${v.height})")
            }
        }
        syncFabPane(v)
    }

    private val pageFabPanes = WeakHashMap<View, GlassView>()

    /** [glassFab] outside the home window: same transparent fill and white glyph, pane from [syncPageFabPane]. */
    private fun glassPageFab(v: View) {
        if (v.width <= 0 || v.height <= 0) return
        flattenFab(v)
        ensureWdsFabFields(v)
        val f = wdsFabTintField ?: return
        if (runCatching { f.get(v) }.getOrNull() !== fabTint) {
            // The field too, or setWdsFabStyle rebuilds the fill from A03 on the next style pass.
            runCatching { f.set(v, fabTint) }
            v.backgroundTintList = fabTint
            wdsFabIconField?.let { icon ->
                runCatching { icon.set(v, fabIconTint) }
                (v as? ImageView)?.imageTintList = fabIconTint
            }
            XposedBridge.log("[$TAG] page fab cleared (${v.width}x${v.height})")
        }
        syncPageFabPane(v)
    }

    /** [syncFabPane] outside the home window: the pane joins the fab's own parent and rides its bounds. */
    private fun syncPageFabPane(fab: View) {
        val parent = fab.parent as? ViewGroup ?: return
        val content = fab.rootView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        var glass = pageFabPanes[fab]
        if (glass == null || glass.parent !== parent) {
            val backdrop = backdropFor(fab, parent, wallpaperUnderlay(content)) ?: return
            glass = GlassView(parent.context).apply {
                this.backdrop = backdrop
                this.underlay = wallpaperUnderlay(content)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = parent.dp(BLUR_DP)
                    cornerRadius = parent.dp(FAB_RADIUS_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = parent.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTint(FAB_ALPHA)
                }
            }
            // Unconstrained in a ConstraintLayout parent: measured at the fab's size, placed by translation.
            parent.addView(
                glass, parent.indexOfChild(fab).coerceAtLeast(0),
                ViewGroup.LayoutParams(fab.width, fab.height),
            )
            pageFabPanes[fab] = glass
            bindPane(glass, fab, "page fab")
            XposedBridge.log("[$TAG] page fab pane inserted (${fab.width}x${fab.height})")
        }
        if (glass.pressSource !== fab) glass.pressSource = fab
        val lp = glass.layoutParams
        if (lp.width != fab.width || lp.height != fab.height) {
            lp.width = fab.width
            lp.height = fab.height
            glass.layoutParams = lp
        }
        if (glass.translationX != fab.left.toFloat()) glass.translationX = fab.left.toFloat()
        if (glass.translationY != fab.top.toFloat()) glass.translationY = fab.top.toFloat()
    }

    /** Kill the drop shadow, a smudge under a translucent button; WDSFab re-applies its A00 field, so the field is what changes. */
    private fun ensureWdsFabFields(v: View) {
        if (wdsFabFieldResolved) return
        wdsFabFieldResolved = true
        // A03/A04 get no shape fallback, both are ColorStateList and the wrong one would silently win; A00 is the lone Float and self-heals.
        wdsFabTintField = WaIds.field(
            v.javaClass, "A03", null, "glass FAB background tint",
            expect = ColorStateList::class.java,
        )
        wdsFabIconField = WaIds.field(
            v.javaClass, "A04", null, "glass FAB icon tint",
            expect = ColorStateList::class.java,
        )
        wdsFabElevField =
            WaIds.field(v.javaClass, "A00", java.lang.Float.TYPE, "glass FAB elevation")
    }

    private fun flattenFab(v: View) {
        if (v.elevation == 0f && v.translationZ == 0f) return
        v.stateListAnimator = null
        wdsFabElevField?.let { runCatching { it.setFloat(v, 0f) } }
        v.elevation = 0f
        v.translationZ = 0f
    }

    /** A real pane, not a tint: rows pass under a floating button and stayed legible; chips scroll with the content, so they may tint. */
    private fun syncFabPane(fab: View) {
        val content = contentRef?.get() ?: return
        val backdrop = pagerHolderRef?.get() ?: return
        var glass = fabGlass[fab]
        if (glass == null || glass.parent !== content) {
            glass = GlassView(content.context).apply {
                this.backdrop = backdrop
                this.underlay = wallpaperUnderlay(backdrop)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = content.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = content.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    // The one pane with its own heavier alpha, which keeps a primary control readable on the card.
                    tintColor = glassTint(FAB_ALPHA)
                }
            }
            // Immediately before the button, so the button's glyph keeps painting on top.
            content.addView(
                glass, content.indexOfChild(fab).coerceAtLeast(0),
                FrameLayout.LayoutParams(0, 0),
            )
            fabGlass[fab] = glass
            // Registered even though the visibility line below overlaps: a detached button never lays out again.
            bindPane(glass, fab, "fab pane")
            XposedBridge.log("[$TAG] fab pane inserted (${fab.width}x${fab.height})")
        }
        if (glass.pressSource !== fab) glass.pressSource = fab
        // A stadium for the Meta AI pill; the measured squircle for the compose button.
        val radius = if (fab is ViewGroup) fab.height / 2f else content.dp(FAB_RADIUS_DP)
        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != fab.width || lp.height != fab.height ||
            lp.leftMargin != fab.left || lp.topMargin != fab.top
        ) {
            lp.width = fab.width
            lp.height = fab.height
            lp.leftMargin = fab.left
            lp.topMargin = fab.top
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
        }
        if (glass.params.cornerRadius != radius) glass.params.cornerRadius = radius
        // The buttons move by translationY, so the pane must too or it stays at the un-lifted position.
        if (glass.translationY != fab.translationY) glass.translationY = fab.translationY
        // Visibility flips alone request no layout, so the sweep misses them; [paneShouldShow] keeps both writers agreeing.
        val want = if (paneShouldShow(fab)) View.VISIBLE else View.GONE
        if (glass.visibility != want) glass.visibility = want
    }

    /** Repin after our own mutations, only if the list was at top just before; onPreDraw so the wrong position is never drawn, post() shows it. */
    private fun repinToTop(list: View) {
        if (list.getTag(repinTag) != null) return          // already watching this list
        // Removed through the observer captured here: a detached view's getViewTreeObserver returns a floating one.
        val observer = list.viewTreeObserver
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            private var frames = 0
            private var pinned = false

            override fun onPreDraw(): Boolean {
                frames++
                var skipDraw = false
                // Re-checked every frame: if nothing mis-anchored, scrolling would be the bug.
                if (list.canScrollVertically(-1)) {
                    runCatching { list.scrollBy(0, -1_000_000) }   // RecyclerView clamps at its start
                        .onSuccess { pinned = true; skipDraw = true }
                }
                if (pinned || frames >= REPIN_FRAMES || !list.isAttachedToWindow) {
                    // `isAlive` because a dead observer throws on removal rather than ignoring it.
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    else list.viewTreeObserver.removeOnPreDrawListener(this)
                    list.setTag(repinTag, null)
                }
                // Cancelling the draw costs one frame and buys never showing the wrong position.
                return !skipDraw
            }
        }
        list.setTag(repinTag, listener)
        observer.addOnPreDrawListener(listener)
    }

    /** Pull the list up and hand the distance back as padding, so rows scroll under the chrome; the work is in [syncListExtension]. */
    private fun extendList(list: View) {
        if (list.getTag(doneTag) != null) return
        val parent = list.parent as? ViewGroup ?: return
        // android.R.id.list is generic; this guard fails closed, so a renamed class kills the extension silently.
        if (!WaIds.classIs(parent, "ConversationsContainer")) return
        // Archived inflates this container too; only the window that has header is home.
        if (headerIdPin != 0 && list.rootView?.findViewById<View>(headerIdPin) == null) return
        list.setTag(doneTag, true)
        listRef = WeakReference(list)
        (list as? ViewGroup)?.clipToPadding = false
        syncListCard()                  // the nav may already have floated
        list.requestLayout()
    }

    /** Re-derives from the invariant top + paddingTop on every layout; never snapshot the measurement. */
    private fun syncListExtension() {
        val v = listRef?.get() ?: return
        if (!v.isLaidOut) return
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        // The content pad is excluded here and added back below, so the recurrence is the same one that already converges.
        val pad = (CARD_CONTENT_PAD_DP * v.resources.displayMetrics.density).toInt()
        val natural = v.top + v.paddingTop - pad
        if (natural <= 0) return
        if (lp.topMargin == -natural && v.paddingTop == natural + pad) return
        // Before the mutation, or the answer is already spoilt by it.
        val wasAtTop = !v.canScrollVertically(-1)
        lp.topMargin = -natural
        v.layoutParams = lp
        v.setPadding(v.paddingLeft, natural + pad, v.paddingRight, v.paddingBottom)
        XposedBridge.log("[$TAG] list extended: topMargin=-$natural paddingTop=${natural + pad}")
        if (wasAtTop) repinToTop(v)
    }

    /** The big Chats title, child 0 of the vertical container so everything reflows; text read off the nav because string names are stripped. */
    private fun injectBigTitle(bar: View) {
        val container = bar.parent as? ViewGroup ?: return
        // my_search_bar is not home-only; same fails-closed guard as [extendList].
        if (!WaIds.classIs(container, "ConversationsContainer")) return
        if (container.getTag(titleTag) != null) return
        val barLp = bar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        container.setTag(titleTag, true)

        // The clearance the search bar was holding for the header now belongs to the title.
        val headerClearance = barLp.topMargin
        val ctx = container.context
        val tv = TextView(ctx).apply {
            text = navLabel() ?: "Chats"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, TITLE_SP)
            typeface = Typeface.create(
                Typeface.DEFAULT, Typeface.BOLD,
            )
            setTextColor(primaryTextColor(ctx))
            includeFontPadding = false
            maxLines = 1
            // 16dp lines up with the search field; the 4dp shift comes off the padding so the margin stays the header's clearance.
            setPadding(
                container.dp(16f).toInt(), container.dp(2f).toInt(),
                container.dp(16f).toInt(), container.dp(10f).toInt(),
            )
        }
        container.addView(
            tv, 0,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = headerClearance },
        )
        barLp.topMargin = container.dp(2f).toInt()
        bar.layoutParams = barLp
        XposedBridge.log("[$TAG] big title '${tv.text}' inserted (clearance=$headerClearance)")
    }

    /** "Chats", localised, read off the bottom nav's first item. */
    private fun navLabel(): CharSequence? = navLabel(0)

    /** Nav item [index]'s localised label; string resource names are stripped, and findViewById stops at the first match. */
    private fun navLabel(index: Int): CharSequence? = runCatching {
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

    /** Hide the small toolbar title on tabs with a big one; the shared bar's title is id-less, so it is matched by text against nav labels. */
    private val bigTitleTabs = listOf(1, 2, 3)   // Updates, Communities, Calls.

    private fun syncToolbarTitle() {
        // Resolved from the live tree and scoped through content: a stale toolbarRef left this pass running on a dead view.
        val toolbar = (
            contentRef?.get()?.let { c ->
                if (homeToolbarId != 0) c.findViewById<View>(homeToolbarId) else null
            } ?: toolbarRef?.get()
            ) as? ViewGroup ?: return
        if (toolbarRef?.get() !== toolbar) toolbarRef = WeakReference(toolbar)
        val wanted = bigTitleTabs.mapNotNull { navLabel(it)?.toString() }
        for (i in 0 until toolbar.childCount) {
            val tv = toolbar.getChildAt(i) as? TextView ?: continue
            // Only restore titles we hid: WhatsApp keeps a "WhatsApp" TextView deliberately hidden and it must stay that way.
            val hide = tv.text?.toString() in wanted
            if (hide) {
                if (tv.visibility != View.GONE) {
                    tv.visibility = View.GONE
                    tv.setTag(hidTitleTag, true)
                    XposedBridge.log("[$TAG] toolbar title (len=${tv.text?.length ?: 0}) -> GONE")
                }
            } else if (tv.getTag(hidTitleTag) != null && tv.visibility != View.VISIBLE) {
                tv.visibility = View.VISIBLE
                tv.setTag(hidTitleTag, null)
                XposedBridge.log("[$TAG] toolbar title (len=${tv.text?.length ?: 0}) -> restored")
            }
            // Absolute sp, not a multiplier, which would compound every layout; the typeface family survives a font swap.
            if (!hide) {
                val px = TOOLBAR_TITLE_SP * tv.resources.displayMetrics.scaledDensity
                if (abs(tv.textSize - px) > 0.5f) {
                    tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, TOOLBAR_TITLE_SP)
                    tv.setTypeface(tv.typeface, Typeface.BOLD)
                    XposedBridge.log("[$TAG] toolbar title (len=${tv.text?.length ?: 0}) -> ${TOOLBAR_TITLE_SP}sp bold")
                }
            }
        }
    }

    private fun primaryTextColor(ctx: Context): Int {
        val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val c = a.getColor(0, Color.WHITE)
        a.recycle()
        return c
    }

    private var loggedHomeSyncThrow = false

    /** Glass between pager_holder and the header, so WhatsApp's toolbar contents keep painting on top. */
    private fun injectToolbarGlass(header: View, pagerHolderId: Int) {
        val parent = header.parent as? ViewGroup ?: return
        if (parent.getTag(doneTag) != null) return

        val backdrop = parent.findViewById<View>(pagerHolderId) ?: run {
            XposedBridge.log("[$TAG] pager_holder not a sibling of header; glass skipped")
            return
        }
        parent.setTag(doneTag, true)
        contentRef = WeakReference(parent)
        pagerHolderRef = WeakReference(backdrop)
        // Armed as soon as the backdrop is known, never in the search path: that is a race with the event being intercepted.
        ensurePagerFadeHook()
        registerFadeOnHide(backdrop)
        parent.viewTreeObserver.addOnGlobalLayoutListener {
            // Guarded: this is the home window's permanent layout driver, and an escape would crash WA on every layout.
            try {
                // The sweep runs first and only from here: the search swap is the one event the anchors cannot report, being the thing removed.
                syncPaneVisibility()
                // Anchors alone cannot drive this: leaving selection mode lays none of them out, and the pane kept the old bar's width.
                syncActionsGlass()
                applyLifts()
                syncListExtension()     // before the card: the card's top is read off the list
                syncListCard()
                syncUpdatesCard()
                syncToolbarTitle()
            } catch (t: Throwable) {
                if (!loggedHomeSyncThrow) {
                    loggedHomeSyncThrow = true
                    XposedBridge.log("[$TAG] home layout sync threw (logged once): $t")
                }
            }
        }
        syncActionsGlass()
        injectNavGlass()
    }

    // ══ The search screen ══════════════════════════════════════════════════════════════════
    // A vertical LinearLayout swapped into id/content: a toolbar margin reflows everything, so the panel lives in id/content via [bindPane].

    private var searchInputId = 0
    private var searchResultListId = 0
    private var searchDividerId = 0
    private var searchToolbarRef: WeakReference<View>? = null
    private var searchPanelRef: WeakReference<View>? = null
    private var searchLowerOffset = 0
    private var searchRaised = false
    private val searchWatcherTag = tagKey("wathemer-search-watcher")
    private val searchLayoutTag = tagKey("wathemer-search-layout")
    private val homeBarLayoutTag = tagKey("wathemer-home-bar-layout")

    /** The home screen's own search bar, in `id/content`'s coordinates. See the writer for why. */
    @Volatile private var homeBarTop = -1
    @Volatile private var homeBarHeight = 0
    private var searchEntryPending = false

    /** The field's last resting place, recorded continuously: by back-out the fragment is gone with nothing left to measure. */
    private var searchFieldRestTop = -1
    private var searchFieldRestH = 0

    // ── The hand-off ───────────────────────────────────────────────────────────────────────
    // The pill starts at the home bar's rect and glides home; animate the transform, never the layout, and no post(), one frame would escape.
    private const val SEARCH_ANIM_MS = 380L
    private const val SEARCH_MOVE_MS = 260L

    /** Material's "emphasized decelerate": fast departure, long settle. Reads as weight. */
    private val searchInterp: Interpolator by lazy {
        PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    /** Resting top as a fraction of the full display, not the keyboard-shrunk window, or the field would move with the keyboard. */
    private const val SEARCH_FIELD_TOP_FRACTION = 0.29f

    /** Breathing room between the status bar and the raised search field; see [setSearchRaised]. */
    private const val SEARCH_RAISED_GAP_DP = 6f

    /** Corner radius of a single search result's glass. */
    private const val SEARCH_ROW_RADIUS_DP = 14f

    /** How far the compose pill is grown past `input_layout` so its icons are not flush. */
    private const val COMPOSE_PILL_PAD_DP = 5f

    /** Ceiling on the compose pill's corner radius: half of the single-row height (136px). */
    private const val COMPOSE_MAX_RADIUS_DP = 24f

    /** Runs on every attach of search_fragment; everything is idempotent except the two tag-guarded registrations. */
    private fun layoutSearchScreen(fragment: View) {
        val root = fragment as? ViewGroup ?: return
        val input = root.findViewById<View>(searchInputId) ?: run {
            logOnce("search: no search_input under search_fragment; layout skipped")
            return
        }
        // The toolbar by structure, not by the generic toolbar id: the direct child holding the input can only be the right row.
        val toolbar = directChildContaining(root, input) ?: run {
            logOnce("search: search_input is not inside a child of search_fragment")
            return
        }
        searchToolbarRef = WeakReference(toolbar)

        // Re-derived on every attach: it moves with the status-bar inset and rotation.
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val screenH = root.resources.displayMetrics.heightPixels
        searchLowerOffset = ((screenH * SEARCH_FIELD_TOP_FRACTION).toInt() - loc[1]).coerceAtLeast(0)

        // The 1px hairline travels down with the field and cuts across the panel; INVISIBLE, not GONE, so nothing shifts.
        searchDividerId.takeIf { it != 0 }
            ?.let { root.findViewById<View>(it) }
            ?.let { if (it.visibility != View.INVISIBLE) it.visibility = View.INVISIBLE }

        // Read the field rather than assume empty: a relaunch can restore straight into a queried search.
        val hasQuery = searchQueryLength(input) > 0
        searchRaised = !hasQuery          // force the first setSearchRaised to actually apply
        setSearchRaised(hasQuery, animate = false)
        // Only the empty-query entry gets the hand-off; a restored search had no home bar to travel from.
        searchEntryPending = !hasQuery

        if (input.getTag(searchWatcherTag) == null && input is TextView) {
            input.setTag(searchWatcherTag, true)
            input.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    setSearchRaised(visibleLength(s) > 0)
                }
            })
        }

        injectSearchPanel(toolbar)
        if (toolbar.getTag(searchLayoutTag) == null) {
            toolbar.setTag(searchLayoutTag, true)
            toolbar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                syncSearchPanel()
                // After syncSearchPanel, never before: until the panel has a size there is nothing to scale from.
                if (searchEntryPending && toolbar.height > 0) {
                    searchEntryPending = false
                    runCatching { animateSearchEntry(toolbar, root) }
                }
            }
        }
        toolbar.post { syncSearchPanel() }

        // Driven from the list's own layout: it binds long after attach and re-binds on every keystroke.
        root.findViewById<View>(searchResultListId)?.let { list ->
            if (list.getTag(searchLayoutTag) == null) {
                list.setTag(searchLayoutTag, true)
                list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncSearchFilters(root) }
                }
                // A scroll driver too: recycling re-binds opaque chips without any layout; pre-draw with an O(1) early-out on child 0's top and identity.
                (list as? ViewGroup)?.let { lv ->
                    val observer = lv.viewTreeObserver
                    observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                        private var lastTop = Int.MIN_VALUE
                        private var lastFirst = 0
                        override fun onPreDraw(): Boolean {
                            // The observer outlives the fragment: self-remove on detach and clear the tag so the next search re-registers.
                            if (!lv.isAttachedToWindow) {
                                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                                lv.setTag(searchLayoutTag, null)
                                return true
                            }
                            val first = lv.getChildAt(0)
                            val top = first?.top ?: Int.MIN_VALUE
                            val id = System.identityHashCode(first)
                            if (top != lastTop || id != lastFirst) {
                                lastTop = top
                                lastFirst = id
                                runCatching { syncSearchFilters(root) }
                            }
                            return true
                        }
                    })
                }
            }
            list.post { runCatching { syncSearchFilters(root) } }
        }
    }

    // ── The filter chips ───────────────────────────────────────────────────────────────────
    // A real Material ChipGroup: wrapping needs singleLine off plus UNSPECIFIED rewritten to AT_MOST, hooked on whichever class declares onMeasure.
    private val wrapChipGroups = WeakHashMap<View, Boolean>()
    private var chipWrapHooked = false

    private fun ensureChipWrapHook(group: View) {
        if (chipWrapHooked) return
        var c: Class<*>? = group.javaClass
        while (c != null) {
            val m = runCatching {
                c!!.getDeclaredMethod(
                    "onMeasure", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            if (m != null) {
                chipWrapHooked = true
                m.isAccessible = true
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (synchronized(wrapChipGroups) { wrapChipGroups[v] } != true) return
                        val spec = param.args[0] as Int
                        if (View.MeasureSpec.getMode(spec) != View.MeasureSpec.UNSPECIFIED) return
                        var size = View.MeasureSpec.getSize(spec)
                        // Fallbacks in trust order: spec size, parent inner width, display; a zero would collapse every chip onto its own line.
                        if (size <= 0) {
                            val p = v.parent as? View
                            if (p != null) size = p.width - p.paddingLeft - p.paddingRight
                        }
                        if (size <= 0) size = v.resources.displayMetrics.widthPixels
                        param.args[0] =
                            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.AT_MOST)
                    }
                })
                XposedBridge.log("[$TAG] chip wrap hook armed on ${c.name}.onMeasure")
                return
            }
            c = c.superclass
        }
        XposedBridge.log("[$TAG] chip wrap FAILED: no onMeasure declared above ${group.javaClass.name}")
    }

    /** Last ChipGroup [searchChipGroup] found, so the scan runs once per search screen. */
    private var searchChipGroupRef: WeakReference<ViewGroup>? = null

    /** Found by scan, not index (RecyclerView reorders children); re-scanned when the cached ref goes stale, and a miss is normal. */
    private fun searchChipGroup(list: ViewGroup): ViewGroup? {
        searchChipGroupRef?.get()?.let { if (it.isAttachedToWindow) return it }
        for (i in 0 until list.childCount) {
            val g = findChipGroup(list.getChildAt(i), 0) ?: continue
            searchChipGroupRef = WeakReference(g)
            logOnce("search filter chips: ChipGroup found (${g.childCount} chips)")
            return g
        }
        return null
    }

    /** Wrap the filters onto as many rows as they need; a single line shows five of eleven, and a mode that never toggles cannot stick. */
    private fun syncSearchFilters(root: ViewGroup) {
        val list = root.findViewById<ViewGroup>(searchResultListId) ?: return
        if (list.childCount == 0) return
        // A miss here is silent and permanent: a renamed ChipGroup or a deeper row stops frost and wrap with nothing in the log.
        val group = searchChipGroup(list) ?: return

        if (synchronized(wrapChipGroups) { wrapChipGroups[group] } != true) {
            ensureChipWrapHook(group)
            synchronized(wrapChipGroups) { wrapChipGroups[group] = true }
            // FlowLayout tests singleLine before wrapping, so the spec rewrite alone changes nothing; reflective, no Material dependency.
            runCatching { XposedHelpers.callMethod(group, "setSingleLine", false) }
                .onFailure { XposedBridge.log("[$TAG] setSingleLine failed: $it") }
            // The scrolling parent must be free to grow, or the extra rows are simply clipped.
            (group.parent as? View)?.let { p ->
                val lp = p.layoutParams
                if (lp != null && lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                    lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    p.layoutParams = lp
                }
            }
            group.requestLayout()
            XposedBridge.log("[$TAG] search filters wrapped (${group.childCount} chips)")
        }
        // Re-run every layout, unguarded: recycled chips re-bind and need painting again each time.
        for (i in 0 until group.childCount) {
            runCatching { styleSearchChip(group.getChildAt(i) ?: return@runCatching) }
        }
    }

    /** Built once and reused: the chip setters compare by reference, and a fresh equal list would invalidate every chip each layout. */
    private var chipCslFor = Int.MIN_VALUE
    private var chipFillCsl: ColorStateList? = null
    private var chipRimCsl: ColorStateList? = null

    /** A Chip drops setBackground on the floor, so [frost] cannot work; setChipBackgroundColor and the stroke pair are the honoured API. */
    private fun styleSearchChip(chip: View) {
        // No done-guard on purpose: a re-bind restores opaque colours while a done-tag would survive it.
        val key = glassTint(CHIP_ALPHA)
        if (chipFillCsl == null || chipCslFor != key) {
            chipCslFor = key
            chipFillCsl = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(glassTint(CHIP_ALPHA_SELECTED), glassTint(CHIP_ALPHA)),
            )
            chipRimCsl = ColorStateList.valueOf(glassTint(CHIP_RIM_ALPHA))
        }
        val ok = runCatching {
            XposedHelpers.callMethod(chip, "setChipBackgroundColor", chipFillCsl)
            XposedHelpers.callMethod(chip, "setChipStrokeColor", chipRimCsl)
            XposedHelpers.callMethod(chip, "setChipStrokeWidth", chip.dp(1f))
        }.isSuccess
        if (!ok) logOnce("search chip styling failed; Chip API renamed?")
    }

    /** Anchored on the class NAME, which R8 keeps; a rename kills this silently, and the missing one-time "found" log is the signal. */
    private fun findChipGroup(v: View?, depth: Int): ViewGroup? {
        if (v == null || depth > 4) return null
        if (v is ViewGroup) {
            if (v.javaClass.name.endsWith("chip.ChipGroup")) return v
            for (i in 0 until v.childCount) findChipGroup(v.getChildAt(i), depth + 1)?.let { return it }
        }
        return null
    }

    /** Visible characters, not text.length: the tokenising field keeps invisible anchor characters, so isNotEmpty lies. */
    private fun visibleLength(s: CharSequence?): Int {
        if (s.isNullOrEmpty()) return 0
        var n = 0
        for (c in s) {
            if (c.isWhitespace()) continue
            val type = Character.getType(c)
            if (type == Character.FORMAT.toInt() || type == Character.CONTROL.toInt()) continue
            n++
        }
        return n
    }

    private fun searchQueryLength(input: View): Int {
        val t = (input as? TextView)?.text
        return visibleLength(t)
    }

    // ── Cross-fading the home content ──────────────────────────────────────────────────────
    // pager_holder is hidden, never detached, so it can be faded; the ~2 janky frames are the compositing itself, not the buffer or duration.
    private const val SEARCH_FADE_MS = 200L

    private var pagerFadeHooked = false
    private val fadeOnHide = WeakHashMap<View, Boolean>()
    private val hideInProgress = WeakHashMap<View, Boolean>()
    private val applyingHide = WeakHashMap<View, Boolean>()

    /** The fade must be hook-driven: WhatsApp shows pager_holder without laying anything of ours out. */
    private fun ensurePagerFadeHook() {
        if (pagerFadeHooked) return
        pagerFadeHooked = true
        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java, "setVisibility", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        // First and cheapest: one map probe on a very hot path.
                        if (synchronized(fadeOnHide) { fadeOnHide[v] } != true) return
                        val now = v.visibility
                        param.extra.putInt("wtPrev", now)
                        val want = param.args[0] as Int
                        // A VISIBLE mid-fade must be caught here, before the edge test, which cannot see it while the deferred view still reads VISIBLE.
                        if (want == View.VISIBLE) {
                            cancelHideFade(v)
                            return
                        }
                        if (now != View.VISIBLE) return
                        // Our own re-entrant call from the end of the fade. Let it through.
                        if (synchronized(fadeOnHide) { applyingHide[v] } == true) return
                        param.result = null                    // defer the hide
                        startHideFade(v, want)
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val v = param.thisObject as? View ?: return
                        if (synchronized(fadeOnHide) { fadeOnHide[v] } != true) return
                        if ((param.args[0] as Int) != View.VISIBLE) return
                        // Only a real INVISIBLE to VISIBLE edge (previous captured in the before-hook): search opens with a redundant VISIBLE that zeroed the alpha.
                        if (param.extra.getInt("wtPrev") == View.VISIBLE) return
                        v.animate().cancel()
                        v.alpha = 0f
                        v.animate().alpha(1f).setDuration(SEARCH_FADE_MS)
                            .setInterpolator(searchInterp).start()
                    }
                },
            )
            XposedBridge.log("[$TAG] home cross-fade hook armed")
        }.onFailure { XposedBridge.log("[$TAG] cross-fade hook FAILED: $it") }
    }

    /** Defer the hide and dissolve: WhatsApp's hide lands 5ms after attach, no room to race; panes bound to [v] fade too, they inherit nothing. */
    private fun startHideFade(v: View, want: Int) {
        if (synchronized(fadeOnHide) { hideInProgress[v] } == true) return
        synchronized(fadeOnHide) { hideInProgress[v] = true }
        val panes = paneBindings.filter { it.anchor.get() === v }.mapNotNull { it.pane.get() }
        for (p in panes) {
            p.animate().cancel()
            // Glass dissolves its material; only a non-glass pane falls back to an alpha fade.
            (p as? GlassView)?.materializeTo(0f, SEARCH_FADE_MS)
                ?: p.animate().alpha(0f).setDuration(SEARCH_FADE_MS)
                    .setInterpolator(searchInterp).start()
        }
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(SEARCH_FADE_MS).setInterpolator(searchInterp)
            .withEndAction {
                synchronized(fadeOnHide) {
                    hideInProgress.remove(v)
                    applyingHide[v] = true
                }
                runCatching { v.visibility = want }
                synchronized(fadeOnHide) { applyingHide.remove(v) }
                // The panes are deliberately not reset here, they would flash one frame at full alpha; [syncPaneVisibility] resets them.
                v.alpha = 1f
            }.start()
    }

    /** All four: cancel (drops the pending write), clear hideInProgress, restore anchor and pane alpha; visibility keeps its one writer. */
    private fun cancelHideFade(v: View) {
        if (synchronized(fadeOnHide) { hideInProgress[v] } != true) return
        v.animate().cancel()
        v.alpha = 1f
        for (p in paneBindings.filter { it.anchor.get() === v }.mapNotNull { it.pane.get() }) {
            p.animate().cancel()
            p.alpha = 1f
            (p as? GlassView)?.materializeTo(1f, 0L)
        }
        synchronized(fadeOnHide) { hideInProgress.remove(v) }
        logOnce("hide fade retracted; a VISIBLE arrived while the hide was deferred")
    }

    /** Fade this view out with the home screen whenever WhatsApp hides it. */
    private fun registerFadeOnHide(v: View?) {
        if (v == null) return
        synchronized(fadeOnHide) { fadeOnHide[v] = true }
        // No forceHasOverlappingRendering(false): measured, it changed nothing; the cost is drawing two screens, not the buffer.
    }

    // Fade views are registered where they are resolved, never lazily in the search path, which races the event itself.
    // The FABs are deliberately not in this set: deferring their everyday hides by 200ms feels sticky.

    // ══ Home -> conversation ════════════════════════════════════════════════════════════════
    // A separate Activity, so no cross-window dissolve; a plain fade both ways.
    private var rowContainerId = 0

    /** The Calls tab's row. A different id from the chat list's; see the branch in [ensureBgHook]. */
    private var callRowContainerId = 0
    private var chatOpenHooked = false

    private fun ensureChatOpenTransition() {
        if (chatOpenHooked) return
        chatOpenHooked = true
        runCatching {
            // Framework fade ids; our own anim resources cannot resolve in WhatsApp's process.
            // No shared elements by design; requestFeature changes the whole process's window animations, so the removal is total.
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val a = param.thisObject as? Activity ?: return
                        if (a.javaClass.name != "com.whatsapp.Conversation") return
                        if (Build.VERSION.SDK_INT < 34) return
                        runCatching {
                            a.overrideActivityTransition(
                                Activity.OVERRIDE_TRANSITION_OPEN,
                                android.R.anim.fade_in, android.R.anim.fade_out,
                            )
                            a.overrideActivityTransition(
                                Activity.OVERRIDE_TRANSITION_CLOSE,
                                android.R.anim.fade_in, android.R.anim.fade_out,
                            )
                        }.onFailure { XposedBridge.log("[$TAG] chat fade refused: $it") }
                    }
                },
            )
            // API 31-33 have no overrideActivityTransition, so ask the old way around each edge.
            if (Build.VERSION.SDK_INT < 34) {
                XposedHelpers.findAndHookMethod(
                    Activity::class.java, "startActivityForResult",
                    Intent::class.java, Int::class.javaPrimitiveType,
                    Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val i = param.args[0] as? Intent ?: return
                            if (i.component?.className != "com.whatsapp.Conversation") return
                            val a = param.thisObject as? Activity ?: return
                            @Suppress("DEPRECATION")
                            runCatching {
                                a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                            }
                        }
                    },
                )
                XposedHelpers.findAndHookMethod(
                    Activity::class.java, "finish",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val a = param.thisObject as? Activity ?: return
                            if (a.javaClass.name != "com.whatsapp.Conversation") return
                            @Suppress("DEPRECATION")
                            runCatching {
                                a.overridePendingTransition(
                                    android.R.anim.fade_in, android.R.anim.fade_out,
                                )
                            }
                        }
                    },
                )
            }
            XposedBridge.log("[$TAG] chat transitions armed (fade both ways)")
        }.onFailure { XposedBridge.log("[$TAG] chat-open transition FAILED: $it") }
    }

    // ── Toolbar bleed-through guard ────────────────────────────────────────────────────────
    /** Toolbars we hid for a contextual action bar, each with the visibility to give back. */
    private val hiddenUnderActionBar = WeakHashMap<View, Int>()
    /** Last action-bar state we acted on, per window root, so the walk runs on change only. */
    private val actionBarState = WeakHashMap<View, Boolean>()
    private val actionBarWatchTag = tagKey("wathemer-action-bar-watch")

    /** One layout watcher per window, driving [syncActionBarCover] whenever the mode flips. */
    private fun watchActionBar(bar: View, toolbarId: Int) {
        val root = bar.rootView as? ViewGroup ?: return
        if (root.getTag(actionBarWatchTag) != null) return
        root.setTag(actionBarWatchTag, true)
        root.viewTreeObserver.addOnGlobalLayoutListener {
            runCatching { syncActionBarCover(root, bar, toolbarId) }
        }
        syncActionBarCover(root, bar, toolbarId)
    }

    /** The bar's own isShown IS the mode. Hide others INVISIBLE (GONE jumps the layout); steady state is one isShown call. */
    private fun syncActionBarCover(root: ViewGroup, bar: View, toolbarId: Int) {
        val active = bar.isShown && bar.height > 0
        if (actionBarState[root] == active) return
        actionBarState[root] = active
        if (active) {
            // Same family as the toolbar: glass owns the fill, and the band or the wallpaper behind is the material.
            if (bar.background != null) clearBg(bar, "action_mode_bar")
            // AppCompat creates the guard lazily, hence the second, delayed pass.
            val inset = runCatching {
                root.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top
            }.getOrNull() ?: 0
            runCatching { clearStatusGuard(root, inset) }
            root.postDelayed({ runCatching { clearStatusGuard(root, inset) } }, 250L)
        }
        if (active) {
            forEachWithId(root, toolbarId) { t ->
                if (t !== bar && t.visibility == View.VISIBLE && !containsView(t, bar)) {
                    hiddenUnderActionBar[t] = t.visibility
                    t.visibility = View.INVISIBLE
                }
            }
        } else {
            val each = hiddenUnderActionBar.entries.iterator()
            while (each.hasNext()) {
                val e = each.next()
                // Scoped to this window root: a blanket restore would un-hide another Activity's toolbar mid-selection.
                if (e.key.rootView !== root) continue
                e.key.visibility = e.value
                each.remove()
            }
        }
    }

    private fun forEachWithId(v: View, id: Int, action: (View) -> Unit) {
        if (v.id == id) action(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) forEachWithId(v.getChildAt(i), id, action)
    }

    /** The bar that owns a menu item; the container knows its children, a hard-coded id list cannot (WAEnhancer adds five). */
    private fun menuHolder(v: View): View = (v.parent as? ViewGroup) ?: v

    /** Specific group beats [GROUP_BOTH]: the overflow seed must not relabel a bar it shares. */
    private fun tagGroup(v: View, group: Int) {
        val cur = v.getTag(actionGroupTag) as? Int
        if (cur == null || (cur == GROUP_BOTH && group != GROUP_BOTH)) v.setTag(actionGroupTag, group)
    }

    /** Tints whatever is in the row: some icons carry generated ids that name-keyed tinting misses; yields to a user icon colour. */
    private fun whitenActionIcons(container: View) {
        if (toolbarIconsColored) return
        val group = container as? ViewGroup ?: return
        val white = ColorStateList.valueOf(Color.WHITE)
        for (i in 0 until group.childCount) {
            val a = group.getChildAt(i) ?: continue
            if (a.getTag(altIconTag) != null) continue
            a.setTag(altIconTag, true)
            when {
                a is ImageView -> a.imageTintList = white
                // ActionMenuItemView draws its icon as a compound drawable, out of imageTintList's reach.
                a is TextView -> a.compoundDrawableTintList = white
            }
        }
    }

    /** Icons found by what a child is, not by a size heuristic; falls back to the bar itself. */
    private fun iconTargets(a: View): List<View> {
        if (a !is ViewGroup) return listOf(a)
        val out = ArrayList<View>(a.childCount)
        for (i in 0 until a.childCount) {
            val c = a.getChildAt(i)
            if (!c.isShown || c.width <= 0 || c.height <= 0) continue
            if (c.isClickable || c is ImageView ||
                c.javaClass.simpleName.endsWith("ActionMenuItemView")
            ) out.add(c)
        }
        return if (out.isEmpty()) listOf(a) else out
    }

    /** Drop the id-less status guard's background (assigned once, so it holds); the match is deliberately narrow, looser blanks real views. */
    private fun clearStatusGuard(root: ViewGroup, inset: Int) {
        if (inset <= 0) return
        fun walk(v: View): Boolean {
            if (v.javaClass == View::class.java && v.id == View.NO_ID && v.tag == null) {
                val bg = v.background as? ColorDrawable
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                if (bg != null && Color.alpha(bg.color) == 255 &&
                    loc[1] <= 0 && v.height in 1..inset
                ) {
                    v.background = null
                    XposedBridge.log(
                        "[$TAG] status guard cleared (h=${v.height} was #%08x)".format(bg.color)
                    )
                    return true
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) if (walk(v.getChildAt(i))) return true
            return false
        }
        walk(root.rootView)
    }

    /** Is [maybeChild] anywhere beneath [parent]? Walks up, so it costs depth, not subtree size. */
    private fun containsView(parent: View, maybeChild: View): Boolean {
        var p = maybeChild.parent
        while (p is View) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    /** The direct child of [parent] that has [descendant] somewhere beneath it, or null. */
    private fun directChildContaining(parent: ViewGroup, descendant: View): View? {
        var v: View? = descendant
        while (v != null) {
            val p = v.parent
            if (p === parent) return v
            v = p as? View
        }
        return null
    }

    /** Raise or lower the field with a toolbar top margin; the LinearLayout reflows everything below for free. */
    private fun setSearchRaised(raised: Boolean, animate: Boolean = true) {
        if (raised == searchRaised) return
        val toolbar = searchToolbarRef?.get() ?: return
        val lp = toolbar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        searchRaised = raised
        // Raised means the status inset, not zero, which clipped the field; read live, cutouts and split-screen change it.
        val statusInset = runCatching {
            toolbar.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top
        }.getOrNull() ?: 0
        val want = if (raised) statusInset + toolbar.dp(SEARCH_RAISED_GAP_DP).toInt() else searchLowerOffset
        val from = lp.topMargin
        if (lp.topMargin != want) {
            lp.topMargin = want
            toolbar.layoutParams = lp
        }
        // Only when already on screen and actually moving; the first placement of a session is [animateSearchEntry]'s job.
        if (animate && toolbar.height > 0 && from != want) {
            glideSearchBy((from - want).toFloat(), SEARCH_MOVE_MS)
        }
        XposedBridge.log("[$TAG] search field ${if (raised) "raised" else "lowered to $want"}")
    }

    /** Move field and pill together and settle to zero; [syncSearchPanel] reads layout positions, blind to translation, so they never fight. */
    private fun glideSearchBy(delta: Float, duration: Long) {
        val toolbar = searchToolbarRef?.get() ?: return
        val panel = searchPanelRef?.get()
        toolbar.translationY = delta
        panel?.translationY = delta
        toolbar.animate().translationY(0f).setDuration(duration)
            .setInterpolator(searchInterp).start()
        panel?.animate()?.translationY(0f)?.setDuration(duration)
            ?.setInterpolator(searchInterp)?.start()
    }

    /** The entry hand-off: centres matched, not tops (the bars differ in height), and it bails when the home bar was never seen. */
    private fun animateSearchEntry(toolbar: View, root: ViewGroup) {
        if (homeBarTop < 0 || homeBarHeight <= 0 || toolbar.height <= 0) return
        val content = contentRef?.get() ?: return
        // Both sides layout-relative and blind to translation, so the delta is between resting positions, not animation frames.
        val tb = Rect(0, 0, toolbar.width, toolbar.height)
        if (!runCatching { content.offsetDescendantRectToMyCoords(toolbar, tb) }.isSuccess) return
        val delta = (homeBarTop + homeBarHeight / 2f) - (tb.top + tb.height() / 2f)
        if (abs(delta) < 1f) return
        // More than half the screen means a wrong reading; refuse with a log rather than slide in from off-screen.
        if (abs(delta) > root.resources.displayMetrics.heightPixels / 2f) {
            XposedBridge.log("[$TAG] search hand-off REFUSED: implausible delta=${delta.toInt()}px")
            return
        }

        val panel = searchPanelRef?.get()
        toolbar.translationY = delta
        panel?.let {
            it.translationY = delta
            if (it.height > 0) {
                it.scaleY = (homeBarHeight.toFloat() / it.height).coerceIn(0.4f, 1f)
            }
        }
        toolbar.animate().translationY(0f).setDuration(SEARCH_ANIM_MS)
            .setInterpolator(searchInterp).start()
        panel?.animate()?.translationY(0f)?.scaleY(1f)?.setDuration(SEARCH_ANIM_MS)
            ?.setInterpolator(searchInterp)?.start()

        // The filters rise in slightly late so the eye follows the pill first.
        root.findViewById<View>(searchResultListId)?.let { list ->
            list.alpha = 0f
            list.translationY = root.dp(20f)
            list.animate().alpha(1f).translationY(0f)
                .setStartDelay(70).setDuration(SEARCH_ANIM_MS)
                .setInterpolator(searchInterp).start()
        }
        XposedBridge.log("[$TAG] search hand-off from home bar (delta=${delta.toInt()}px)")
    }

    /** The return leg animates the INCOMING bar: the fragment dies in one frame, and an outgoing pill would glide away empty. */
    private fun animateHomeBarReturn() {
        val bar = searchBarRef?.get() ?: return
        if (bar.height <= 0 || searchFieldRestH <= 0 || homeBarTop < 0 || homeBarHeight <= 0) return
        val delta = (searchFieldRestTop + searchFieldRestH / 2f) -
            (homeBarTop + homeBarHeight / 2f)
        if (abs(delta) < 1f) return
        if (abs(delta) > bar.resources.displayMetrics.heightPixels / 2f) {
            XposedBridge.log("[$TAG] return hand-off REFUSED: implausible delta=${delta.toInt()}px")
            return
        }
        bar.animate().cancel()
        bar.translationY = delta
        bar.animate().translationY(0f).setDuration(SEARCH_ANIM_MS)
            .setInterpolator(searchInterp).start()
        XposedBridge.log("[$TAG] return hand-off to home bar (delta=${delta.toInt()}px)")
    }

    /** The search field's panel, in id/content because the fragment cannot stack children; bound to the toolbar so it leaves with search. */
    private fun injectSearchPanel(toolbar: View) {
        val content = contentRef?.get() ?: return
        if (!content.isAttachedToWindow) return
        var glass = searchPanelRef?.get()
        if (glass == null || glass.parent !== content) {
            glass = GlassView(content.context).apply {
                underlay = wallpaperUnderlay(content)
                params.apply {
                    downsample = DOWNSAMPLE
                    blurRadius = content.dp(BLUR_DP)
                    refractionEnabled = true
                    bevelFraction = BEVEL_FRACTION
                    depthRatio = DEPTH_RATIO
                    maxDisplacePx = content.dp(DISPLACE_DP)
                    fresnelStrength = 0.5f
                    tintColor = glassTintColor
                }
            }
            // Index 0, behind the fragment, so WhatsApp's field and icons keep painting on top.
            content.addView(glass, 0, FrameLayout.LayoutParams(0, 0))
            searchPanelRef = WeakReference(glass)
            XposedBridge.log("[$TAG] search panel inserted")
        }
        // bindPane on every attach, outside the creation branch: each search open brings a new toolbar, and a pane that outlives its anchor must re-bind.
        bindPane(glass, toolbar, "search panel") { animateHomeBarReturn() }

        // Reused across searches, so shed the last session's transform or a cancelled hand-off strands it at 0.6 scale.
        glass.animate().cancel()
        glass.translationY = 0f
        glass.scaleY = 1f
    }

    /** Track the field's rect. Called on every layout of the toolbar, so it follows the rise. */
    private fun syncSearchPanel() {
        val content = contentRef?.get() ?: return
        val glass = searchPanelRef?.get() as? GlassView ?: return
        val toolbar = searchToolbarRef?.get() ?: return
        if (toolbar.width <= 0 || toolbar.height <= 0) return
        val rect = Rect(0, 0, toolbar.width, toolbar.height)
        if (!runCatching { content.offsetDescendantRectToMyCoords(toolbar, rect) }.isSuccess) return
        // The resting place for the return trip; layout coordinates stay truthful mid-glide.
        searchFieldRestTop = rect.top
        searchFieldRestH = rect.height()

        val padV = content.dp(6f).toInt()
        val l = rect.left
        val t = rect.top - padV
        val r = rect.right
        val b = rect.bottom + padV
        if (r <= l || b <= t) return

        val lp = glass.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width != r - l || lp.height != b - t ||
            lp.leftMargin != l - content.paddingLeft || lp.topMargin != t - content.paddingTop
        ) {
            lp.width = r - l
            lp.height = b - t
            lp.leftMargin = l - content.paddingLeft
            lp.topMargin = t - content.paddingTop
            lp.gravity = Gravity.TOP or Gravity.START
            glass.layoutParams = lp
            glass.params.cornerRadius = (b - t) / 2f
        }
    }

}
