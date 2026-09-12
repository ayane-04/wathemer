// The shipped Liquid Glass values, in one place: the getter default, the install fallback and Restore all read them.
package com.wathemer.app.settings.prefs

object GlassDefaults {

    /** Backdrop blur in dp. */
    const val BLUR = 9

    /** Tint alpha out of 255, applied to the channel the wallpaper's luma picks. */
    const val TINT = 8

    /** Refraction ceiling in dp. */
    const val DISPLACE = 25

    /** Bevel band as a percent of the surface's smaller side. */
    const val BEVEL = 20

    /** Card corner radius in dp; also the ceiling on the lensing band. */
    const val RADIUS = 20

    /** Transmitted-backdrop gamma as a percent; below 100 lifts the darks. */
    const val GAMMA = 75

    /** Transmitted-backdrop saturation as a percent; 100 is off. */
    const val SATURATION = 110

    /** Rim stroke alpha out of 100. */
    const val RIM = 13

    /** Rim stroke width in dp. */
    const val RIM_WIDTH = 2

    /** Rim gradient angle in degrees, deciding which side of a surface lights up. */
    const val RIM_ANGLE = 90

    /** Grouped continuations flatten their top corner toward the message above. */
    const val BUBBLE_MERGE = true

    /** The tint carries a whisper of the wallpaper's dominant hue; off is the neutral grey. */
    const val HUED_TINT = true

    /** The wallpaper copies are shrunk and blurred in linear light, so bright detail spreads as light rather than darkening. */
    const val LINEAR_COPY = true

    /** Darkening on the very edge under the highlight, as a percent; 0 is off. */
    const val EDGE_SHADOW = 15

    /** How strongly bright backdrop detail glows through the glass, as a percent; 0 is off. */
    const val GLOW = 50

    /** How much sharp, bent backdrop shows at the very edge of a surface over the frost, as a percent; 0 is off. */
    const val EDGE_CLARITY = 25

    /** Edge clarity on the panes that show live content too; it costs a pass per pane, so it is asked for. */
    const val LIVE_CLARITY = false

    /** The active tab's pill flows between tabs instead of blinking across. */
    const val NAV_DROPLET = true

    /** Surfaces assemble their lens as they appear. */
    const val ASSEMBLE = true

    /** The wallpaper copies behind bubbles, pills and menus follow the Backdrop blur slider like the panes. */
    const val ONE_BLUR = true

    /** The small pills carry the bubbles' rim light and lens instead of a flat film. */
    const val SMALL_OPTICS = true

    /** A menu's glass rises out of the button that opened it and sinks back when it closes. */
    const val POPUP_MORPH = true

    /** The selected chat row carries the bubbles' rim light and lens instead of a flat film. */
    const val ROW_OPTICS = true
}
