package com.wathemer.app.glass

import android.graphics.Color

/**
 * Every tunable of the glass in one place; mutable with a listener because a copy per touch-move
 * is garbage. Lengths are screen px unless named otherwise. density has no default on purpose.
 */
class GlassParams(var density: Float) {

    /** Notified after any property changes, so the view can invalidate itself. */
    var onChanged: (() -> Unit)? = null

    private fun <T> set(current: T, new: T, assign: (T) -> Unit): Boolean {
        if (current == new) return false
        assign(new)
        onChanged?.invoke()
        return true
    }

    /** Blur radius in screen px, exactly what you see; never divided by [downsample]. */
    var blurRadius: Float = 24f
        set(v) { set(field, v.coerceIn(0f, 400f)) { field = it } }

    /** Capture scale, a cost knob only: it sets what detail refraction can bend, never the frost look. */
    var downsample: Float = 4f
        set(v) { set(field, v.coerceIn(1f, 16f)) { field = it } }

    /** Drawn over the blurred backdrop. Alpha carries most of the glassiness. */
    var tintColor: Int = Color.argb(56, 255, 255, 255)
        set(v) { set(field, v) { field = it } }

    /**
     * Px each shader edge is pushed outside the pane, so a flush screen edge shows no rim. Push,
     * do not oversize the view: blurring the uncovered margin drags a dark gradient inward.
     */
    var edgeExpandLeft: Float = 0f
        set(v) { set(field, v) { field = it } }
    var edgeExpandTop: Float = 0f
        set(v) { set(field, v) { field = it } }
    var edgeExpandRight: Float = 0f
        set(v) { set(field, v) { field = it } }
    var edgeExpandBottom: Float = 0f
        set(v) { set(field, v) { field = it } }

    /** Bottom-edge dissolve length; both shader passes read it, so tint cannot outlive backdrop. */
    var fadeBottomPx: Float = 0f
        set(v) { set(field, v) { field = it } }

    /** Top fade in surface-local px, set per frame by [BubbleGlassPainter]; negative means the line is above. */
    var fadeTopOffsetPx: Float = 0f
        set(v) { set(field, v) { field = it } }

    var fadeTopLenPx: Float = 0f
        set(v) { set(field, v.coerceAtLeast(0f)) { field = it } }

    /** Corner radius in px. Applied via the outline provider, so it clips children too. */
    var cornerRadius: Float = 48f
        set(v) { set(field, v.coerceAtLeast(0f)) { field = it } }

    /** Per-corner radii in px (TL, TR, BR, BL); null keeps all four at [cornerRadius]. Painter-written, no notify. */
    var cornerRadii: FloatArray? = null

    /** Master switch. Off means the view draws nothing at all, for A/B measurement. */
    var enabled: Boolean = true
        set(v) { set(field, v) { field = it } }

    /** Idle refresh period, ms; a heartbeat, not a freeze: activity detection misses some invalidates. */
    var idleIntervalMs: Long = 250
        set(v) { set(field, v.coerceIn(0L, 1000L)) { field = it } }

    /** How long after the last scroll or layout event the backdrop still counts as moving. */
    var activeWindowMs: Long = 350
        set(v) { set(field, v.coerceIn(0L, 2000L)) { field = it } }

    // ── Refraction tier (API 33+) ──────────────────────────────────────────────────
    // Live glass at downsample 4 leaves headroom to bend the source.

    /** Off falls back to the frosted tier even on API 33+, for A/B against the shader. */
    var refractionEnabled: Boolean = true
        set(v) { set(field, v) { field = it } }

    /** Bevel band px: the optics live there, the interior stays flat. Normally derived from [bevelFraction]. */
    var bevelThickness: Float = 40f
        set(v) { set(field, v.coerceIn(1f, 2000f)) { field = it } }

    /** Band as a fraction of the smaller dimension; 0 leaves [bevelThickness] alone. Fixed dp misscales. */
    var bevelFraction: Float = 0.25f
        set(v) { set(field, v.coerceIn(0f, 0.5f)) { field = it } }

    /** Thickness as a multiple of [bevelThickness]; the ray is walked, so the flat interior displaces zero. */
    var depthRatio: Float = 0.35f
        set(v) { set(field, v.coerceIn(0f, 8f)) { field = it } }

    /** Soft ceiling on refraction displacement in px; unbounded it smears on a large panel. */
    var maxDisplacePx: Float = 45f
        set(v) { set(field, v.coerceIn(0f, 400f)) { field = it } }

    /** Index of refraction. 1.0 = no bending; real glass is ~1.5, water ~1.33. */
    var ior: Float = 1.45f
        set(v) { set(field, v.coerceIn(1f, 2.5f)) { field = it } }

    /** Per-channel refraction spread, the spectral fringe at the rim. Gated by edge weight. */
    var dispersion: Float = 0.06f
        set(v) { set(field, v.coerceIn(0f, 0.5f)) { field = it } }

    /** Centre magnification, applied after the ceiling; [maxDisplacePx] does not bound it. Default 0 runs. */
    var magnify: Float = 0f
        set(v) { set(field, v.coerceIn(0f, 0.3f)) { field = it } }

    // ── Directional light ──────────────────────────────────────────────────────────
    // Two opposed thin arcs; Fresnel alone is rotationally uniform and can only draw an even outline.

    var specStrength: Float = 0.34f
        set(v) { set(field, v.coerceIn(0f, 4f)) { field = it } }

    /** Primary lobe exponent. 90-120 reads as a thin arc; below ~30 it becomes a wash. */
    var specPower: Float = 96f
        set(v) { set(field, v.coerceIn(2f, 256f)) { field = it } }

    /** Secondary (opposed) lobe exponent, softer and weaker. */
    var specPower2: Float = 44f
        set(v) { set(field, v.coerceIn(2f, 256f)) { field = it } }

    /** Primary light direction, xy; +z is implied. Upper-left origin, so -y is up. */
    var lightX: Float = 0.45f
        set(v) { set(field, v.coerceIn(-4f, 4f)) { field = it } }
    var lightY: Float = -0.72f
        set(v) { set(field, v.coerceIn(-4f, 4f)) { field = it } }

    /** Secondary light, deliberately opposed to the primary. */
    var light2X: Float = -0.34f
        set(v) { set(field, v.coerceIn(-4f, 4f)) { field = it } }
    var light2Y: Float = 0.56f
        set(v) { set(field, v.coerceIn(-4f, 4f)) { field = it } }

    /** Schlick Fresnel strength: the rim brightening, and the signature of the material. */
    var fresnelStrength: Float = 0.5f
        set(v) { set(field, v.coerceIn(0f, 3f)) { field = it } }

    /** Schlick exponent. 5 is the textbook value; lower spreads the rim inward. */
    var fresnelPower: Float = 5f
        set(v) { set(field, v.coerceIn(1f, 12f)) { field = it } }

    // ── Dynamic response (light pass only) ─────────────────────────────────────────
    // Gains for GlassView's motion envelope. Never write per frame: every setter rebuilds the refraction chain.

    /** Specular brightening at full motion, as a fraction of [specStrength]; 0.7 takes 0.34 to ~0.58. */
    var motionSpecGain: Float = 0.7f
        set(v) { set(field, v.coerceIn(0f, 4f)) { field = it } }

    /** Same for [fresnelStrength], deliberately smaller: the whole-perimeter rim reads as lighting up. */
    var motionFresnelGain: Float = 0.25f
        set(v) { set(field, v.coerceIn(0f, 4f)) { field = it } }

    /** Motion decay in ms; GlassView clamps it to [activeWindowMs], or the decay freezes and snaps. */
    var motionDecayMs: Long = 220
        set(v) { set(field, v.coerceIn(0L, 2000L)) { field = it } }

    // ── Micro distortion (light pass only) ─────────────────────────────────────────
    // Perturbs the normal only; the window must stay 4t(1-t), zero at both band ends (1-t outlines the rim).

    /** Normal-tilt amplitude; off by default. Rim outlining is the window's fault, not the amplitude's. */
    var microAmp: Float = 0f
        set(v) { set(field, v.coerceIn(0f, 0.3f)) { field = it } }

    /** Noise period in dp on purpose: raw px freezes the grain to one device; smaller aliases, larger dents. */
    var microScaleDp: Float = 3.5f
        set(v) { set(field, v.coerceIn(0.25f, 80f)) { field = it } }

    // ── Multi-layer highlights (light pass) ────────────────────────────────────────
    // Invented gradients, not physics; both must vanish on the plateau to keep flatInteriorColor() exact.

    /** Dark band inside the bright rim; 0.10 reads as a second pill, 0.04 is the most that stays shading. */
    var innerShadow: Float = 0f
        set(v) { set(field, v.coerceIn(0f, 1f)) { field = it } }

    /** A third, deliberately broad specular lobe; with the thin arcs it reads as a lit surface. */
    var sheenStrength: Float = 0.18f
        set(v) { set(field, v.coerceIn(0f, 2f)) { field = it } }

    /** Sheen exponent. 6-12 is a broad wash; above ~30 it stops being a layer and duplicates. */
    var sheenPower: Float = 8f
        set(v) { set(field, v.coerceIn(1f, 64f)) { field = it } }

    /** Sheen direction, deliberately not either existing light, or it adds nothing new. */
    var light3X: Float = -0.62f
        set(v) { set(field, v.coerceIn(-4f, 4f)) { field = it } }
    var light3Y: Float = -0.38f
        set(v) { set(field, v.coerceIn(-4f, 4f)) { field = it } }

    /** Gamma on the transmitted backdrop; a contrast multiply pivots mid-grey, wrong for a dark backdrop. */
    var transGamma: Float = defaultTransGamma
        set(v) { set(field, v.coerceIn(0.3f, 1f)) { field = it } }

    // ── Rim stroke ─────────────────────────────────────────────────────────────────
    // A hard line on the silhouette. The light pass only ever makes a wide soft lobe.

    /** Stroke width in px; 0 is off and costs nothing. */
    var rimStrokePx: Float = defaultRimStrokePx
        set(v) { set(field, v.coerceIn(0f, 24f)) { field = it } }

    /** Gradient axis in degrees, clockwise from +x. Decides which side of the rim lights up. */
    var rimStrokeAngle: Float = defaultRimStrokeAngle
        set(v) { set(field, v) { field = it } }

    /** Off for full-bleed panels, whose bounds are the screen's. */
    var rimEnabled: Boolean = true
        set(v) { set(field, v) { field = it } }

    /** Bright stop of the rim gradient; the opposite stop is the same hue at zero alpha. */
    var rimStrokeColor: Int = defaultRimStrokeColor
        set(v) { set(field, v) { field = it } }

    /** Install-time globals, so the hook sets them once rather than at every construction site. */
    companion object {
        // Mirrors GlassDefaults by hand; the engine does not import settings.
        @JvmStatic var defaultTransGamma: Float = 0.75f
        @JvmStatic var defaultRimStrokePx: Float = 0f
        @JvmStatic var defaultRimStrokeAngle: Float = 90f
        @JvmStatic var defaultRimStrokeColor: Int = Color.WHITE
    }
}
