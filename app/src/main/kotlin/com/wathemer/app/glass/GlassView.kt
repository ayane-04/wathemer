package com.wathemer.app.glass

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RecordingCanvas
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import java.lang.ref.WeakReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * A pane of glass: draws a blurred, tinted copy of [backdrop] behind its own children. Point it
 * at a sibling drawn before it, never an ancestor; on API 33+ the refraction pass runs too.
 */
class GlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    val params = GlassParams(resources.displayMetrics.density)

    private val capture = BackdropCapture(this, params)

    /** The subtree to blur. Must be a sibling (or otherwise not an ancestor of this view). */
    var backdrop: View?
        get() = capture.source
        set(value) {
            capture.source = value
            invalidate()
        }

    /** Painted beneath [backdrop]; see [BackdropCapture.underlay]. */
    var underlay: List<View>
        get() = capture.underlay
        set(value) {
            capture.underlay = value
            invalidate()
        }

    /**
     * Capture in pre-draw; the capture-invalidate loop is deliberate. Removing the invalidate
     * freezes the glass, so throttle the idle case, never stop it.
     */
    private val preDrawListener = ViewTreeObserver.OnPreDrawListener {
        // Guarded whole: capture() draws WhatsApp's own views, and an escape here crashes the UI thread every frame.
        try {
            if (params.enabled && isAttachedToWindow) {
                val now = SystemClock.uptimeMillis()
                // A move is activity: a pane can move without resizing, and the capture is placed by position.
                getLocationOnScreen(screenLoc)
                if (screenLoc[0] != lastScreenX || screenLoc[1] != lastScreenY) {
                    lastScreenX = screenLoc[0]
                    lastScreenY = screenLoc[1]
                    lastActivityMs = now
                }
                val moving = now - lastActivityMs < params.activeWindowMs
                updateMotion(now)
                pollPressSource()
                // Gate the capture, not just the invalidate: re-recording a live node schedules a frame itself.
                val minGap = if (moving) 0L else params.idleIntervalMs
                if (now - lastCaptureMs >= minGap) {
                    lastCaptureMs = now
                    if (capture.capture()) {
                        if (moving) invalidate() else postInvalidateDelayed(params.idleIntervalMs)
                    }
                }
            }
        } catch (t: Throwable) {
            if (!loggedPreDrawThrow) {
                loggedPreDrawThrow = true
                Log.w("WaThemer.GlassView", "pre-draw capture threw, frame skipped", t)
            }
        }
        true // never cancel the host's frame
    }

    private var loggedPreDrawThrow = false

    private var lastCaptureMs = 0L

    /** Scratch buffer for getLocationOnScreen, so the move check allocates nothing per frame. */
    private val screenLoc = IntArray(2)

    /** Last screen position we captured at. See the move check in [preDrawListener]. */
    private var lastScreenX = Int.MIN_VALUE
    private var lastScreenY = Int.MIN_VALUE

    // ── Idle throttling ────────────────────────────────────────────────────────────────
    // Idle over-renders; slow it, never stop it, and the heartbeat covers unseen invalidates.

    private var lastActivityMs = 0L

    // Scroll only: a global-layout listener never lets the throttle engage; the heartbeat covers the rest.
    private val activityListener = ViewTreeObserver.OnScrollChangedListener {
        val now = SystemClock.uptimeMillis()
        lastActivityMs = now
        lastScrollMs = now
    }

    /** Scroll only, a separate clock: attach and resize must not read as motion or panes appear bright. */
    private var lastScrollMs = 0L

    init {
        // A ViewGroup skips onDraw unless told; the backdrop draws there to land under our children.
        setWillNotDraw(false)

        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, params.cornerRadius)
            }
        }
        // GPU clip to the rounded outline; a clipPath in onDraw would allocate on the draw path.
        clipToOutline = true

        params.onChanged = {
            // Comparing every uniform costs more than the rebuild it avoids; just flag stale.
            capture.effectDirty = true
            invalidateLight()
            invalidateOutline()
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnPreDrawListener(preDrawListener)
        viewTreeObserver.addOnScrollChangedListener(activityListener)
        // Attach counts as activity, so the first frames are live rather than a heartbeat late.
        lastActivityMs = SystemClock.uptimeMillis()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDrawListener)
        viewTreeObserver.removeOnScrollChangedListener(activityListener)
        materializeAnim?.cancel()
        pressAnim?.cancel()
        capture.release()
        super.onDetachedFromWindow()
    }

    /** The light program on a Paint, drawn at full resolution after the blur; the highlight must not blur. */
    private val lightPaint = Paint()
    private var lightValid = false

    /** This view's own light shader, never shared: uniforms live on it, so sharing means last writer wins. */
    private val lightShader: RuntimeShader? by lazy {
        if (GlassShader.supported) GlassShader.newLightShader() else null
    }

    private fun invalidateLight() { lightValid = false }

    // ── Dynamic response ───────────────────────────────────────────────────────────────
    // Held here, not in params, whose setters rebuild the refraction chain; wall-clock because frames are uneven.
    private var motion = 0f

    /** Decay is clamped to activeWindowMs: a longer decay stalls part-way, then snaps at a heartbeat. */
    private fun updateMotion(now: Long) {
        val decay = minOf(params.motionDecayMs, params.activeWindowMs)
        val off = params.motionSpecGain <= 0f && params.motionFresnelGain <= 0f
        val target = if (decay <= 0L || off || lastScrollMs == 0L) {
            0f    // lastScrollMs 0 means nothing has scrolled yet: at rest, not at full motion
        } else {
            // Squared: fast fall while the eye is on the motion, flat landing, and the 0.01 gate stops the pushes sooner.
            val lin = (1f - (now - lastScrollMs).toFloat() / decay.toFloat()).coerceIn(0f, 1f)
            lin * lin
        }
        // A threshold; exact equality would re-push 13 uniforms every frame forever.
        if (abs(target - motion) < 0.01f) return
        motion = target
        invalidateLight()
        invalidate()
    }

    // ── Materialize ─────────────────────────────────────────────────────────────────────
    /** 0 no material, 1 settled. Drives transmission, tint and lighting; never View.alpha, which flattens the material into a decal. */
    private var materialize = 1f
    private var materializeAnim: ValueAnimator? = null

    /** Armed by [materializeIn]; the lighting rig swings home while the material assembles. */
    private var appearSweep = false

    /** Exposed so the two halves of a fade handshake test the same variable. */
    val materialized: Float get() = materialize

    fun setMaterialize(p: Float) {
        val v = p.coerceIn(0f, 1f)
        if (v == materialize) return
        materialize = v
        if (v >= 1f) appearSweep = false
        // The transmission leads the lighting: the backdrop swells in first, the rim strikes last.
        capture.setContentAlpha((v * 1.4f).coerceAtMost(1f))
        invalidateLight()
        invalidate()
    }

    /** Its own clock: the pre-draw poll idles at the heartbeat, far below animation rate. */
    fun materializeTo(target: Float, durationMs: Long) {
        materializeAnim?.cancel()
        if (durationMs <= 0L) { setMaterialize(target); return }
        materializeAnim = ValueAnimator.ofFloat(materialize, target).apply {
            duration = durationMs
            addUpdateListener { setMaterialize(it.animatedValue as Float) }
            start()
        }
    }

    /** Appear by assembling rather than fading: Apple names alpha-fade as the wrong answer. */
    fun materializeIn(durationMs: Long) {
        appearSweep = true
        setMaterialize(0f)
        materializeTo(1f, durationMs)
    }

    /** One definition each for shader and [flatInteriorColor]; disagreeing on Fresnel steps the band edge. */
    private fun effSpecStrength(): Float =
        params.specStrength * (1f + params.motionSpecGain * motion) * materialize * materialize

    private fun effFresnelStrength(): Float =
        params.fresnelStrength * (1f + params.motionFresnelGain * motion) * materialize * materialize

    private fun ensureLight(): Boolean {
        val rs = lightShader ?: return false
        if (lightValid) return true
        if (width <= 0 || height <= 0) return false
        lightValid = true
        // The appear sweep rotates both spec lights home as the rim strikes; rimMask keeps the plateau exact.
        var l1x = params.lightX
        var l1y = params.lightY
        var l2x = params.light2X
        var l2y = params.light2Y
        if (appearSweep && materialize < 1f) {
            val a = (1f - materialize) * SWEEP_RADIANS
            val c = cos(a)
            val s = sin(a)
            l1x = params.lightX * c - params.lightY * s
            l1y = params.lightX * s + params.lightY * c
            l2x = params.light2X * c - params.light2Y * s
            l2y = params.light2X * s + params.light2Y * c
        }
        GlassShader.applyLightUniforms(
            rs, params, width, height,
            specStrength = effSpecStrength(),
            fresnelStrength = effFresnelStrength(),
            density = resources.displayMetrics.density,
            tintScale = materialize,
            light1X = l1x,
            light1Y = l1y,
            light2X = l2x,
            light2Y = l2y,
            pressX = pressX,
            pressY = pressY,
            pressAmp = pressAmp,
            pressRadiusPx = PRESS_RADIUS_DP * resources.displayMetrics.density,
        )
        lightPaint.shader = rs
        return true
    }

    // ── Press response ─────────────────────────────────────────────────────────────────
    // Additive on the light scalar only; a normal bump hits the pow(1-n.z,5) rim exactly as microAmp did.
    private var pressAmp = 0f
    private var pressX = 0f
    private var pressY = 0f
    private var pressAnim: ValueAnimator? = null
    private var pressSourceRef: WeakReference<View>? = null
    private var pressWasDown = false
    private val pressLoc = IntArray(2)

    /** Polled in pre-draw: while this view's isPressed is set, light pools at its centre. */
    var pressSource: View?
        get() = pressSourceRef?.get()
        set(value) {
            pressSourceRef = if (value == null) null else WeakReference(value)
        }

    private fun setPressAmp(a: Float) {
        if (a == pressAmp) return
        pressAmp = a
        invalidateLight()
        invalidate()
    }

    private fun animatePress(target: Float, durationMs: Long) {
        pressAnim?.cancel()
        pressAnim = ValueAnimator.ofFloat(pressAmp, target).apply {
            duration = durationMs
            addUpdateListener { setPressAmp(it.animatedValue as Float) }
            start()
        }
    }

    /** Its own invalidate; the moving flag would make a press re-record the backdrop. */
    fun pressAt(x: Float, y: Float) {
        pressX = x
        pressY = y
        animatePress(PRESS_AMP, PRESS_IN_MS)
    }

    fun releasePress() {
        if (pressAmp <= 0f && pressAnim == null) return
        animatePress(0f, PRESS_OUT_MS)
    }

    /** Edge-detects the source's pressed state; two field reads at rest. */
    private fun pollPressSource() {
        val s = pressSourceRef?.get() ?: return
        val down = s.isPressed
        if (down == pressWasDown) return
        pressWasDown = down
        if (down) {
            s.getLocationOnScreen(pressLoc)
            val sx = pressLoc[0] + s.width / 2f
            val sy = pressLoc[1] + s.height / 2f
            getLocationOnScreen(pressLoc)
            pressAt(sx - pressLoc[0], sy - pressLoc[1])
        } else {
            releasePress()
        }
    }

    private val flatPaint = Paint()

    /** Shade only the bevel band; the plateau gets the exact constant, sparing a whole-surface shader pass. */
    private fun drawLight(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Ceil plus 2px slack: fractional edges leave a 1px unshaded boundary row.
        // The rects abut exactly; both are translucent, so any overlap double-tints its row.
        val band = ceil(GlassShader.effectiveBevel(params, width, height) + 2f)

        // A fading or expanded surface cannot use the shortcut: a flat rect cannot ramp its alpha.
        val fading = params.fadeBottomPx > 0f || params.fadeTopLenPx > 0f ||
            params.edgeExpandLeft > 0f || params.edgeExpandTop > 0f ||
            params.edgeExpandRight > 0f || params.edgeExpandBottom > 0f
        // Not worth splitting when the band covers most of the surface, the normal case for pills.
        // A pressed surface runs the program everywhere too: the pool crosses the plateau, which the flat rect cannot show.
        if (fading || pressAmp > 0f || band * 2f + 4f >= minOf(w, h)) {
            canvas.drawRect(0f, 0f, w, h, lightPaint)
            return
        }
        canvas.drawRect(0f, 0f, w, band, lightPaint)                 // top
        canvas.drawRect(0f, h - band, w, h, lightPaint)              // bottom
        canvas.drawRect(0f, band, band, h - band, lightPaint)        // left
        canvas.drawRect(w - band, band, w, h - band, lightPaint)     // right

        val c = flatInteriorColor()
        if (c ushr 24 != 0) {
            flatPaint.color = c
            // Abutting again; see the note on band above.
            canvas.drawRect(band, band, w - band, h - band, flatPaint)
        }
    }

    /**
     * The exact plateau colour the light program returns: f0 survives there, the premultiplied rgb
     * must be un-premultiplied for a Paint, and it must read [effFresnelStrength] or the edge steps.
     */
    private fun flatInteriorColor(): Int {
        // The same tint scale the shader gets, or the band edge steps while a pane materializes.
        val ta = ((params.tintColor ushr 24) and 0xFF) / 255f * materialize
        val tr = ((params.tintColor ushr 16) and 0xFF) / 255f
        val tg = ((params.tintColor ushr 8) and 0xFF) / 255f
        val tb = (params.tintColor and 0xFF) / 255f
        val light = (0.04f * effFresnelStrength()).coerceIn(0f, 1f)
        val a = (ta + light).coerceIn(0f, 1f)
        if (a <= 0f) return 0
        fun ch(t: Float): Int {
            val pm = (t * ta + light).coerceIn(0f, 1f)
            return ((pm / a).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        }
        return ((a * 255f + 0.5f).toInt() shl 24) or (ch(tr) shl 16) or (ch(tg) shl 8) or ch(tb)
    }

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private var rimW = -1
    private var rimH = -1
    private var rimAngle = Float.NaN
    private var rimColor = 0
    private var rimWidth = -1f

    /** A gradient-shaded line on the silhouette. Rebuilt only when size, angle, colour or width move. */
    private fun drawRim(canvas: Canvas) {
        if (!params.rimEnabled) return
        val px = params.rimStrokePx
        if (px <= 0f) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        if (rimW != width || rimH != height || rimAngle != params.rimStrokeAngle ||
            rimColor != params.rimStrokeColor || rimWidth != px
        ) {
            rimW = width
            rimH = height
            rimAngle = params.rimStrokeAngle
            rimColor = params.rimStrokeColor
            rimWidth = px
            rimPaint.strokeWidth = px
            val rad = Math.toRadians(rimAngle.toDouble())
            // Half the box projected onto the axis, so the ramp spans the surface whatever the angle.
            val ext = (abs(cos(rad)) * w + abs(sin(rad)) * h) / 2.0
            val dx = (cos(rad) * ext).toFloat()
            val dy = (sin(rad) * ext).toFloat()
            // Lit at both ends of the axis, clear across the middle.
            rimPaint.shader = LinearGradient(
                w / 2f - dx, h / 2f - dy, w / 2f + dx, h / 2f + dy,
                intArrayOf(rimColor, rimColor and 0x00FFFFFF, rimColor),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        // Scaled by materialize, or the rim arrives at full strength while the pane fades up.
        rimPaint.alpha = (255f * materialize).toInt().coerceIn(0, 255)
        // Inset half the width; clipToOutline eats the outer half otherwise.
        val inset = px / 2f
        val r = (params.cornerRadius - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, rimPaint)
    }

    private fun deriveBevel(w: Int, h: Int) {
        val frac = params.bevelFraction
        if (frac <= 0f || w <= 0 || h <= 0) return
        params.bevelThickness = (minOf(w, h) * frac).coerceAtLeast(1f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        deriveBevel(w, h)
        invalidateLight()
        // A resize must not wait for a heartbeat: anything already recorded is the wrong size.
        lastActivityMs = SystemClock.uptimeMillis()
        capture.effectDirty = true
        // Without this the outline clips at the old size and the pane draws cut short after a resize.
        invalidateOutline()
        invalidate()
    }

    private companion object {
        /** Half a turn (pi): the arcs start opposite their homes and sweep in as the rim strikes. */
        const val SWEEP_RADIANS = 3.1415927f
        /** The light added at the touch point; chosen by eye. */
        const val PRESS_AMP = 0.12f
        const val PRESS_IN_MS = 80L
        const val PRESS_OUT_MS = 240L
        /** Finger-sized pool: buttons sit inside it whole, cards get a local glow. */
        const val PRESS_RADIUS_DP = 64f
    }

    override fun onDraw(canvas: Canvas) {
        if (!params.enabled) return

        // drawRenderNode needs a RecordingCanvas; on a software canvas degrade to tint instead of crashing.
        val recording = canvas as? RecordingCanvas
        if (recording != null) capture.draw(recording)

        // Full-res light over the blur; the RuntimeShader Paint needs the hardware-canvas check too.
        if (recording != null && ensureLight()) {
            drawLight(canvas)
        } else if (params.tintColor ushr 24 != 0) {
            canvas.drawColor(params.tintColor)
        }
        // Over the light pass, and plain Canvas so the software path keeps it.
        drawRim(canvas)
    }
}
