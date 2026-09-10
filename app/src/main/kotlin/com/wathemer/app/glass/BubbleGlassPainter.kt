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

    /** Transmission, dim and light in one pass; null falls back to the panes' two programs below. */
    private val fused: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassShader.newBubbleShader() else null

    // Compiled only when a bubble has no bitmap to transmit or the fused program failed.
    private val light: RuntimeShader? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassShader.newLightShader() else null
    }
    private val refract: RuntimeShader? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassShader.newRefractShader() else null
    }

    private val fusedPaint = Paint()
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

    /** Each draw op snapshots the shader, so per-bubble re-pushes are safe; the guard skips them when nothing changed. */
    private var uniformW = 0f
    // Identity too: a painter shared across drawables must re-push when it is handed another params object.
    private var lastParams: GlassParams? = null
    private var uniformH = 0f
    private var uniformTint = 0
    private var uniformFade = 0f
    private var uniformFadeLen = 0f
    private var uniformDim = -1f
    /** The radii the shader last got, clamped to the half size exactly as the clip path always was. */
    private val lastRadii = FloatArray(4)
    private val curRadii = FloatArray(4)
    private val pathRadii = FloatArray(8)
    private var pushedOnce = false
    // Which programs hold the current uniforms; a bubble may switch paths between draws with nothing else changed.
    private var fusedPushed = false
    private var lightPushed = false
    private var refractPushed = false

    /** The per-draw geometry the uniforms derive from; the programs themselves are pushed on demand. */
    private fun prepareParams(params: GlassParams, w: Float, h: Float, tint: Int) {
        params.tintColor = tint
        // A bubble's params are built by the hook, which has no view to ask; the painter does.
        params.density = density
        // Same rule as GlassView.deriveBevel: a fixed dp band is a rim on small surfaces, a hairline on large.
        val frac = params.bevelFraction
        if (frac > 0f) params.bevelThickness = (minOf(w, h) * frac).coerceAtLeast(1f)
    }

    /** The one BitmapShader for this bitmap, linear because a RuntimeShader child never sees the paint's filter flag. */
    private fun contentShaderFor(bmp: Bitmap): BitmapShader {
        var cs = content
        if (cs == null || contentOf !== bmp) {
            cs = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) cs.filterMode = BitmapShader.FILTER_MODE_LINEAR
            content = cs
            contentOf = bmp
        }
        return cs
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
        // The shader's shape is its radii, so they are clamped like the path's; unclamped, a short bubble bulges past its clip.
        if (radii == null) {
            curRadii[0] = r
            curRadii[1] = r
            curRadii[2] = r
            curRadii[3] = r
        } else {
            for (i in 0 until 4) curRadii[i] = minOf(radii[i], rcap)
        }
        val radiiChanged = !pushedOnce || curRadii[0] != lastRadii[0] || curRadii[1] != lastRadii[1] ||
            curRadii[2] != lastRadii[2] || curRadii[3] != lastRadii[3]
        // The fade offset moves with the bubble, so it belongs in the change test, not a per-size push.
        val fadeOffset = if (fadeLen > 0f) fadeLineScreenY - screenY else 0f
        val dimNow = dim.coerceIn(0f, 1f)
        if (params !== lastParams || clip.width() != uniformW || clip.height() != uniformH || tint != uniformTint ||
            fadeOffset != uniformFade || fadeLen != uniformFadeLen || radiiChanged || dimNow != uniformDim
        ) {
            lastParams = params
            uniformW = clip.width()
            uniformH = clip.height()
            uniformTint = tint
            uniformFade = fadeOffset
            uniformFadeLen = fadeLen
            uniformDim = dimNow
            curRadii.copyInto(lastRadii)
            pushedOnce = true
            params.cornerRadii = lastRadii
            tintPaint.color = tint
            params.fadeTopOffsetPx = fadeOffset
            params.fadeTopLenPx = fadeLen
            prepareParams(params, uniformW, uniformH, tint)
            fusedPushed = false
            lightPushed = false
            refractPushed = false
        }
        val iw = uniformW.toInt().coerceAtLeast(1)
        val ih = uniformH.toInt().coerceAtLeast(1)
        // Translate the shader, not the canvas: on the fallback path moving the canvas would move the clip path too.
        local.setTranslate(clip.left, clip.top)

        val fs = fused
        if (fs != null && bmp != null && place != null && bmp.width > 0 && bmp.height > 0) {
            if (!fusedPushed) {
                GlassShader.applyBubbleUniforms(fs, params, iw, ih, density, dimNow)
                fusedPushed = true
            }
            fs.setLocalMatrix(local)
            // Bitmap to shader space: place maps bitmap to screen; subtract the bubble's screen origin.
            matrix.set(place)
            matrix.postTranslate(-screenX, -screenY)
            val cs = contentShaderFor(bmp)
            cs.setLocalMatrix(matrix)
            // Rebind every draw, after setLocalMatrix: it discards the native instance the binding stored.
            fs.setInputShader("content", cs)
            // Re-assigned every draw: the Paint must snapshot this bubble's uniforms, not the last one's.
            fusedPaint.shader = fs
            // One pixel past the rect, so edge pixels whose centres fall outside still get their coverage; the shader returns 0 there.
            canvas.drawRect(clip.left - 1f, clip.top - 1f, clip.right + 1f, clip.bottom + 1f, fusedPaint)
            return
        }

        // Two-program path: a bubble with nothing to transmit, or a device where the fused program failed.
        val ls = light
        val rs = refract
        if (ls != null && !lightPushed) {
            GlassShader.applyLightUniforms(ls, params, iw, ih, density = density)
            lightPushed = true
        }
        if (rs != null && !refractPushed) {
            GlassShader.applyRefractUniforms(rs, params, iw, ih)
            refractPushed = true
        }
        path.reset()
        if (radii == null) {
            path.addRoundRect(clip, r, r, Path.Direction.CW)
        } else {
            for (i in 0 until 4) {
                pathRadii[i * 2] = curRadii[i]
                pathRadii[i * 2 + 1] = curRadii[i]
            }
            path.addRoundRect(clip, pathRadii, Path.Direction.CW)
        }
        ls?.setLocalMatrix(local)
        rs?.setLocalMatrix(local)

        val save = canvas.save()
        canvas.clipPath(path)

        var transmitted = false
        if (bmp != null && place != null && bmp.width > 0 && bmp.height > 0) {
            matrix.set(place)
            matrix.postTranslate(-screenX, -screenY)
            if (rs != null) {
                val cs = contentShaderFor(bmp)
                cs.setLocalMatrix(matrix)
                rs.setInputShader("content", cs)
                refractPaint.shader = rs
                canvas.drawRect(clip, refractPaint)
            } else {
                matrix.postTranslate(clip.left, clip.top)
                canvas.drawBitmap(bmp, matrix, bmpPaint)
            }
            if (dimNow > 0f) {
                dimPaint.color = ((dimNow * 255f).toInt() shl 24)
                if (radii == null) canvas.drawRoundRect(clip, r, r, dimPaint) else canvas.drawPath(path, dimPaint)
            }
            transmitted = true
        }
        // Light and tint on top, as GlassView.onDraw does: the light program reads no image.
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
