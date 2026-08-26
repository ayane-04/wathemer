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

    /** Rim stroke alpha out of 100. */
    const val RIM = 13

    /** Rim stroke width in dp. */
    const val RIM_WIDTH = 2

    /** Rim gradient angle in degrees, deciding which side of a surface lights up. */
    const val RIM_ANGLE = 90

    /** Grouped continuations flatten their top corner toward the message above. */
    const val BUBBLE_MERGE = true
}
