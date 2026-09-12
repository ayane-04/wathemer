package com.wathemer.app.util

import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import com.wathemer.app.hooks.ColorMap

/** Walks a Drawable tree rewriting [ColorMap] seeds. Must stay idempotent: it runs on every loadDrawable and on shared ConstantStates. */
object DrawableColors {

    fun replace(drawable: Drawable?) {
        if (drawable == null) return
        when (drawable) {
            is ColorDrawable -> handleColor(drawable)
            is GradientDrawable -> handleGradient(drawable)
            is LayerDrawable -> handleLayer(drawable)
            is InsetDrawable -> drawable.drawable?.let { replace(it) }
            // Other types (BitmapDrawable, VectorDrawable and so on) are skipped.
        }
    }

    private fun handleColor(d: ColorDrawable) {
        val mapped = ColorMap.substitute(d.color)
        if (mapped != d.color) d.color = mapped
    }

    private fun handleGradient(d: GradientDrawable) {
        // Gradient stops only. Do not reach solid fills: contact avatars are GradientDrawables carrying per-contact colours.
        runCatching {
            val colors = d.colors
            if (colors != null) {
                var changed = false
                val out = IntArray(colors.size)
                for (i in colors.indices) {
                    out[i] = ColorMap.substitute(colors[i])
                    if (out[i] != colors[i]) changed = true
                }
                if (changed) d.colors = out
            }
        }
    }

    private fun handleLayer(d: LayerDrawable) {
        for (i in 0 until d.numberOfLayers) {
            replace(d.getDrawable(i))
        }
    }
}
