// The floating buttons and the nav pill. WDSFab ignores its tint setters, so the A03/A04 fields are
// the way in; the pill is floated by a negative top margin that hands its space to id/content.
package com.wathemer.app.hooks.glass

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.wathemer.app.glass.GlassView
import com.wathemer.app.hooks.WaIds
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.util.WeakHashMap

@Volatile internal var fabIds: IntArray = IntArray(0)

private val chatFabTag = tagKey("wathemer-chat-fab")

/** The chevron and search discs float over the bubbles, so the pane captures the list live, inside the disc's own frame. */
internal fun glassChatFab(fab: View, label: String) {
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
            // Resolve, don't remember: the list can be replaced under the pane; an ancestor is refused per capture.
            if (glass.backdrop !== l) glass.backdrop = l
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

/** Float the nav as a pill. The negative top margin is what puts rows behind the pill, an accepted tab-switch cost, not a shader problem. */
internal fun floatNav(container: View) {
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

/** A pane behind the nav pill, in id/content; an opaque user nav colour still wins and stays pill-shaped. */
internal fun injectNavGlass() {
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
internal fun applyLifts() {
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

// Weak values here and below: the pane sits in the key's own window, and a strong value would pin a dead window's tree.
private val fabGlass = WeakHashMap<View, WeakReference<GlassView>>()

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

private val pageFabPanes = WeakHashMap<View, WeakReference<GlassView>>()

/** [glassFab] outside the home window: same transparent fill and white glyph, pane from [syncPageFabPane]. */
internal fun glassPageFab(v: View) {
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
    var glass = pageFabPanes[fab]?.get()
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
        pageFabPanes[fab] = WeakReference(glass)
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
    var glass = fabGlass[fab]?.get()
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
        fabGlass[fab] = WeakReference(glass)
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

// The folder pages' WDSFab, treated like home's: field-poked clear, flattened, a pane behind it.
private val folderFabAt = IntArray(2)

internal fun folderFab(fab: View, label: String) {
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
    var glass = fabGlass[fab]?.get()
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
        fabGlass[fab] = WeakReference(glass)
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

internal val folderFabTag = tagKey("wathemer-folder-fab")

internal val navRoundListenerTag = tagKey("wathemer-nav-round-listener")
