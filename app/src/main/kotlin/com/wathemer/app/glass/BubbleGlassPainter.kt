package com.wathemer.app.glass

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build

/**
 * One rounded rect of bubble glass, shared by GlassBubblePane and GlassBubbleDrawable so the
 * material cannot drift. Never cache a position here: a stale position is behind every artefact.
 */
class BubbleGlassPainter(private val density: Float) {

    private val light: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassShader.newLightShader() else null
    private val refract: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassShader.newRefractShader() else null

    private val lightPaint = Paint()
    private val refractPaint = Paint()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val local = Matrix()
    private val matrix = Matrix()

    private var content: BitmapShader? = null
    private var contentOf: Bitmap? = null

    /** Each draw op snapshots the shader, so per-bubble re-pushes are safe; the guard saves ~20 jni calls. */
    private var uniformW = 0f
    private var uniformH = 0f
    private var uniformTint = 0
    private var uniformFade = 0f
    private var uniformFadeLen = 0f
    private var hasRadii = false
    private val lastRadii = FloatArray(4)
    private val pathRadii = FloatArray(8)

    private fun pushUniforms(params: GlassParams, w: Float, h: Float, tint: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        params.tintColor = tint
        // A bubble's params are built by the hook, which has no view to ask; the painter does.
        params.density = density
        // Same rule as GlassView.deriveBevel: a fixed dp band is a rim on small surfaces, a hairline on large.
        val frac = params.bevelFraction
        if (frac > 0f) params.bevelThickness = (minOf(w, h) * frac).coerceAtLeast(1f)
        val iw = w.toInt().coerceAtLeast(1)
        val ih = h.toInt().coerceAtLeast(1)
        light?.let { GlassShader.applyLightUniforms(it, params, iw, ih, density = density) }
        refract?.let { GlassShader.applyRefractUniforms(it, params, iw, ih) }
    }

    /** Paint one bubble; screenX/screenY are [clip]'s screen position, passed per draw and never cached. */
    fun paint(
        canvas: Canvas,
        clip: RectF,
        params: GlassParams,
        tint: Int,
        bmp: Bitmap?,
        place: Matrix?,
        screenX: Float,
        screenY: Float,
        dim: Float,
        rimColor: Int,
        rimWidth: Float,
        /** Screen y where the dissolve begins, plus length; the list's fading edge cannot reach the pane. */
        fadeLineScreenY: Float = 0f,
        fadeLen: Float = 0f,
        /** Per-corner radii (TL, TR, BR, BL) in px; null keeps the uniform radius. Caller-owned scratch, copied here. */
        radii: FloatArray? = null,
    ) {
        if (clip.width() <= 0f || clip.height() <= 0f) return
        val rcap = minOf(clip.height() / 2f, clip.width() / 2f)
        val r = minOf(params.cornerRadius, rcap)
        path.reset()
        if (radii == null) {
            path.addRoundRect(clip, r, r, Path.Direction.CW)
        } else {
            for (i in 0 until 4) {
                val c = minOf(radii[i], rcap)
                pathRadii[i * 2] = c
                pathRadii[i * 2 + 1] = c
            }
            path.addRoundRect(clip, pathRadii, Path.Direction.CW)
        }

        val radiiChanged = if (radii == null) {
            hasRadii
        } else {
            !hasRadii || radii[0] != lastRadii[0] || radii[1] != lastRadii[1] ||
                radii[2] != lastRadii[2] || radii[3] != lastRadii[3]
        }
        // The fade offset moves with the bubble, so it belongs in the change test, not a per-size push.
        val fadeOffset = if (fadeLen > 0f) fadeLineScreenY - screenY else 0f
        if (clip.width() != uniformW || clip.height() != uniformH || tint != uniformTint ||
            fadeOffset != uniformFade || fadeLen != uniformFadeLen || radiiChanged
        ) {
            uniformW = clip.width()
            uniformH = clip.height()
            uniformTint = tint
            uniformFade = fadeOffset
            uniformFadeLen = fadeLen
            if (radii == null) {
                hasRadii = false
                params.cornerRadii = null
            } else {
                hasRadii = true
                radii.copyInto(lastRadii)
                params.cornerRadii = lastRadii
            }
            tintPaint.color = tint
            params.fadeTopOffsetPx = fadeOffset
            params.fadeTopLenPx = fadeLen
            pushUniforms(params, uniformW, uniformH, tint)
        }
        // Translate the shader, not the canvas: moving the canvas would move the clip path too.
        local.setTranslate(clip.left, clip.top)
        light?.setLocalMatrix(local)
        refract?.setLocalMatrix(local)

        val save = canvas.save()
        canvas.clipPath(path)

        var transmitted = false
        if (bmp != null && place != null && bmp.width > 0 && bmp.height > 0) {
            // Bitmap to shader space: place maps bitmap to screen; subtract the bubble's screen origin.
            matrix.set(place)
            matrix.postTranslate(-screenX, -screenY)
            val rs = refract
            if (rs != null) {
                var cs = content
                if (cs == null || contentOf !== bmp) {
                    // One BitmapShader per bitmap, not per draw: the bitmap is built once per process.
                    cs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    content = cs
                    contentOf = bmp
                }
                cs.setLocalMatrix(matrix)
                // Rebind every draw, after setLocalMatrix: it discards the native instance the binding stored.
                rs.setInputShader("content", cs)
                // Re-assigned every draw: the Paint must snapshot this bubble's uniforms, not the last one's.
                refractPaint.shader = rs
                canvas.drawRect(clip, refractPaint)
            } else {
                matrix.postTranslate(clip.left, clip.top)
                canvas.drawBitmap(bmp, matrix, bmpPaint)
            }
            if (dim > 0f) {
                dimPaint.color = ((dim.coerceIn(0f, 1f) * 255f).toInt() shl 24)
                if (radii == null) canvas.drawRoundRect(clip, r, r, dimPaint) else canvas.drawPath(path, dimPaint)
            }
            transmitted = true
        }
        // Light and tint on top, as GlassView.onDraw does: the light program reads no image.
        val ls = light
        if (ls != null) {
            lightPaint.shader = ls
            canvas.drawRect(clip, lightPaint)
        } else if (tint ushr 24 != 0) {
            if (radii == null) canvas.drawRoundRect(clip, r, r, tintPaint) else canvas.drawPath(path, tintPaint)
        }
        canvas.restoreToCount(save)

        // Fallback rim only: a stroke over the light program's own Fresnel edge reads as an outline.
        // Uniform corners on purpose: this path exists only below Tiramisu, where merging is not worth a path stroke.
        if (ls == null && !transmitted && rimWidth > 0f && rimColor ushr 24 != 0) {
            tintPaint.color = rimColor
            tintPaint.style = Paint.Style.STROKE
            tintPaint.strokeWidth = rimWidth
            val h = rimWidth / 2f
            clip.inset(h, h)
            canvas.drawRoundRect(clip, r - h, r - h, tintPaint)
            clip.inset(-h, -h)
            tintPaint.style = Paint.Style.FILL
            // Restore the colour too: a matching-key bubble skips the guarded set and would fill rim-coloured.
            tintPaint.color = tint
        }
    }
}
