// Frost: a background drawable rather than a pane, for surfaces that cannot host a child.
// The paint always re-runs and only the listener registration is tag-guarded; recycled rows re-bind.
package com.wathemer.app.hooks.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.view.View
import android.view.ViewTreeObserver
import android.widget.TextView
import com.wathemer.app.glass.FrostDrawable
import com.wathemer.app.glass.GlassParams

/** Frost one view from the wallpaper; idempotent, recycled chips come back bound to a different chip. */
internal fun frost(
    v: View,
    tintOverride: Int? = null,
    allowSquare: Boolean = false,
    ignorePadding: Boolean = false,
    /** Force the radius: half the height suits a pill, but an inner corner rounder than its outer one reads as a mistake. */
    radiusOverride: Float? = null,
    /** Named, the frost is re-asserted by the setBackgroundDrawable interceptor; for views the app repaints without a layout. */
    forceLabel: String? = null,
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
    if (forceLabel != null) forceBg(v, d, forceLabel) else v.background = d
}

internal val frostListenerTag = tagKey("wathemer-frost-listener")

/** [frostOnLayout] with an explicit tint and radius; see the reply-quote call site. */
internal fun frostOnLayoutWith(v: View, tint: Int, radius: Float?, ignorePadding: Boolean = false) {
    val apply = Runnable {
        runCatching {
            frost(v, tintOverride = tint, radiusOverride = radius, ignorePadding = ignorePadding)
        }
    }
    apply.run()
    if (v.getTag(frostListenerTag) == null) {
        v.setTag(frostListenerTag, true)
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
    }
}

private val frostMoveTag = tagKey("wathemer-frost-move")

/** A pill's band as a fraction of its smaller side; the plan's figure, not the slider's, which is for the cards. */
private const val PILL_BEVEL_FRACTION = 0.15f

/** The pane recipe for a small pill, fresh per drawable: the painter writes the band and the tint into it. */
internal fun pillOptics(v: View): GlassParams = GlassParams(v.resources.displayMetrics.density).apply {
    refractionEnabled = true
    bevelFraction = PILL_BEVEL_FRACTION
    depthRatio = DEPTH_RATIO
    maxDisplacePx = DISPLACE_DP * density
    fresnelStrength = 0.5f
    // No fringe on a pre-blurred copy, as the bubbles have it.
    dispersion = 0f
}

/** offsetTopAndBottom moves rows without a redraw and a draw-time wallpaper patch freezes; invalidate on any screen move. */
internal fun watchFrostPosition(v: View) {
    if (v.getTag(frostMoveTag) != null) return
    v.setTag(frostMoveTag, true)
    FrostMoveWatch(v).arm()
}

/** Blurred-wallpaper fill kept on layout; the film variant lets whatever is underneath bleed through. */
internal fun liquidFrostOnLayout(
    v: View,
    radiusDp: Float? = null,
    tintAlpha: Int = CHIP_ALPHA,
    /** For a wrap_content pill whose width comes from the stock drawable's own padding. */
    keepPadding: Boolean = false,
    /** Named, the frost goes through forceBg; needed on a view the interceptor already keeps cleared. */
    forceLabel: String? = null,
    /** A small pill: run the bubble program over the copy when the switch is on. */
    optics: Boolean = false,
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
                v, { host -> wallpaperRecordOf(host) }, radius, glassTint(tintAlpha),
                strokeWidth = v.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
                ignorePadding = true,
                optics = if (optics && SMALL_OPTICS) pillOptics(v) else null,
            )
            v.setTag(frostTag, d)
            if (forceLabel != null) forceBg(v, d, forceLabel) else v.background = d
        }
    }
    apply.run()
    if (v.getTag(frostListenerTag) == null) {
        v.setTag(frostListenerTag, true)
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
    }
}

/** [frostOnLayout] for square views (the Calls disc, the tile pills); the aspect gate would drop them, a disc is the point here. */
internal fun frostCircleOnLayout(v: View) {
    val apply = Runnable { runCatching { frost(v, allowSquare = true, ignorePadding = true) } }
    apply.run()
    if (v.getTag(frostListenerTag) == null) {
        v.setTag(frostListenerTag, true)
        v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
    }
}

/** Chip treatment kept on layout: guard the registration, never the paint; recycled rows re-bind without a fresh attach. */
internal fun frostOnLayout(v: View, forceLabel: String? = null) {
    runCatching { frost(v, forceLabel = forceLabel) }
    if (v.getTag(frostListenerTag) == null) {
        v.setTag(frostListenerTag, true)
        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            runCatching { frost(view, forceLabel = forceLabel) }
        }
    }
}

/** Frost an accent button with its own colour at partial alpha: the dark label needs the contrast, and WDSButton ignores setTextColor. */
internal fun frostAccent(v: View) {
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
        // Scale the canvas, keep the view's real bounds: an icon-sized box makes the rounded rect degenerate and it renders nothing.
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

/** Recolours the shape inside WDSButton's own ripple; the mask layer keeps its colour or the ripple area shrinks. */
internal fun tintWdsShape(d: Drawable?, color: Int): Boolean {
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

private val stockPadById = HashMap<Int, Rect>()

/** WhatsApp's own band padding, restated on the label because the band it came from is cleared. */
private const val UNREAD_PILL_PAD_DP = 6f

/** The unread label ships unbacked and unpadded, its pill and air being the band's, which glass clears; the font padding goes too, or the text sits low in its own pill. */
internal fun padUnreadPill(v: View) {
    val tv = v as? TextView ?: return
    if (tv.includeFontPadding) tv.includeFontPadding = false
    val want = v.dp(UNREAD_PILL_PAD_DP).toInt()
    // Only on a mismatch: setPadding asks for layout, and this runs from a layout callback.
    if (v.paddingTop == want && v.paddingBottom == want) return
    v.setPadding(v.paddingLeft, want, v.paddingRight, want)
}

/** Our fill reports no padding, so replacing a padded drawable remeasures a wrap_content host narrower; learn the stock inset once per id and hold the view at it. */
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

/** The label was measured against the old inset, so this frame would show it clipped; returning false from pre-draw cancels the traversal instead of presenting it. */
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
