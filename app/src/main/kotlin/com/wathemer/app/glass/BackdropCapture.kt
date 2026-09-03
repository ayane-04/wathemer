package com.wathemer.app.glass

import android.graphics.RecordingCanvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import kotlin.math.ceil

/**
 * Captures the content behind a glass view into a [RenderNode], blurred. [source] must be a
 * sibling drawn before the host, never an ancestor: that makes capture recursion impossible.
 */
class BackdropCapture(
    private val host: View,
    private val params: GlassParams,
) {

    companion object {
        /**
         * True only while live views are being drawn into a pane's own RenderNode. A ripple drawn
         * there arms its animator against that node, and the frame's real draw then throws
         * IllegalStateException("Target already set!") from RenderNodeAnimator.setTarget.
         */
        @Volatile
        @JvmStatic
        var capturing: Boolean = false
            private set
    }

    /** The subtree to blur. Must not be an ancestor of [host]. Null disables capture. */
    var source: View? = null
        set(value) {
            assertNotAncestor(value)
            field = value
        }

    /** Views painted beneath [source] at their own screen positions; no legal backdrop contains the wallpaper. */
    var underlay: List<View> = emptyList()

    private val underlayLocation = IntArray(2)

    /** True once a capture has produced content that is safe to draw. */
    var hasContent: Boolean = false
        private set

    /**
     * [node] keeps the capture sharp: refraction can only bend detail that still exists.
     * [glassNode] redraws it at 1/[effectScale] and carries the shader, blur chained after.
     */
    val node = RenderNode("wathemer-backdrop")
    private val glassNode = RenderNode("wathemer-glass")

    private val hostLocation = IntArray(2)
    private val sourceLocation = IntArray(2)

    // Rebuilt only on input change (per-frame effects allocate); effectDirty stands in for ten uniforms.
    /** Halves the transmission's two offscreen layers on large surfaces; blur hides it. */
    private val effectScale: Float
        get() = if (host.width.toLong() * host.height > 600_000L) 2f else 1f

    private var effectRadius = -1f
    private var effectScaleUsed = -1f
    private var effectDownsample = -1f
    private var effectWidth = -1
    private var effectHeight = -1
    private var effectRefracting = false
    var effectDirty: Boolean = true

    /** This surface's own transmission shader. Never shared; see GlassShader's kdoc. */
    private val refractShader: RuntimeShader? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            GlassShader.newRefractShader()
        } else {
            null
        }
    }

    /** Capture [source] into [node] at 1/downsample, region behind [host] at the origin; true when drawable. */
    fun capture(): Boolean {
        val src = source
        verifyAncestryOnce(src)
        // Underlay-only panes are legal: for some panes every capturable subtree is an ancestor.
        if (src == null && underlay.isEmpty()) return false.also { hasContent = false }
        if (src != null && !src.isLaidOut) return false
        if (host.width <= 0 || host.height <= 0) return false

        val scale = params.downsample
        val w = ceil(host.width / scale).toInt().coerceAtLeast(1)
        val h = ceil(host.height / scale).toInt().coerceAtLeast(1)

        // Screen, not window, coordinates: a bottom sheet is its own window with its own origin.
        host.getLocationOnScreen(hostLocation)
        src?.getLocationOnScreen(sourceLocation)
        val dx = if (src == null) 0f else (hostLocation[0] - sourceLocation[0]).toFloat()
        val dy = if (src == null) 0f else (hostLocation[1] - sourceLocation[1]).toFloat()

        // Computed before the log line so it prints the current value; indexed to stay allocation-free.
        for (i in underlay.indices) {
            val u = underlay[i]
            if (u.width > 0 && u.height > 0 && u.visibility == View.VISIBLE) {
                u.getLocationOnScreen(underlayLocation)
                lastUnderlayDy = underlayLocation[1] - hostLocation[1]
                break
            }
        }

        logGeometryOnce(w, h, dx, dy, src)

        node.setPosition(0, 0, w, h)
        val canvas = node.beginRecording(w, h)
        capturing = true
        try {
            // Scale first, then translate: the offsets are in pre-downsample pixels.
            canvas.scale(1f / scale, 1f / scale)

            // In draw order: the wallpaper is an image plus a separate dim overlay; draw both.
            for (i in underlay.indices) {
                val u = underlay[i]
                if (u.width <= 0 || u.height <= 0 || u.visibility != View.VISIBLE) continue
                u.getLocationOnScreen(underlayLocation)
                // View.draw() skips the view's own RenderNode, so re-apply its alpha via a layer.
                val save = if (u.alpha < 1f) {
                    canvas.saveLayerAlpha(
                        0f, 0f, host.width.toFloat(), host.height.toFloat(),
                        (u.alpha * 255f).toInt().coerceIn(0, 255),
                    )
                } else {
                    canvas.save()
                }
                canvas.translate(
                    (underlayLocation[0] - hostLocation[0]).toFloat(),
                    (underlayLocation[1] - hostLocation[1]).toFloat(),
                )
                u.draw(canvas)
                canvas.restoreToCount(save)
            }

            if (src != null) {
                canvas.translate(-dx, -dy)
                src.draw(canvas)
            }
        } finally {
            capturing = false
            // endRecording must run even if draw() throws, or every later beginRecording throws too.
            node.endRecording()
        }

        applyEffectIfNeeded(scale, w, h)

        // Second pass at 1/effectScale: upscale sharp, refract, then blur; order is the whole point.
        val es = effectScale
        val fw = ceil(host.width / es).toInt().coerceAtLeast(1)
        val fh = ceil(host.height / es).toInt().coerceAtLeast(1)
        glassNode.setPosition(0, 0, fw, fh)
        val c = glassNode.beginRecording(fw, fh)
        try {
            c.scale(scale / es, scale / es)
            c.drawRenderNode(node)
        } finally {
            glassNode.endRecording()
        }

        hasContent = true
        return true
    }

    /** Scales only the transmitted image. A display-list property, so the effect chain is never rebuilt for it. */
    fun setContentAlpha(a: Float) {
        val v = a.coerceIn(0f, 1f)
        if (glassNode.alpha != v) glassNode.alpha = v
    }

    /** Draw [glassNode] at [host]'s size; it carries both effects, so no path may skip it. */
    fun draw(canvas: RecordingCanvas) {
        if (!hasContent) return
        val es = effectScale
        if (es == 1f) {
            canvas.drawRenderNode(glassNode)
        } else {
            val save = canvas.save()
            canvas.scale(es, es)
            canvas.drawRenderNode(glassNode)
            canvas.restoreToCount(save)
        }
    }

    fun release() {
        node.discardDisplayList()
        glassNode.discardDisplayList()
        hasContent = false
        effectRadius = -1f
        effectDirty = true
    }

    // One log line per distinct size and source; offsets move every frame of a glide and must not re-key.
    private var loggedW = -1
    private var loggedH = -1
    private var loggedHostW = -1
    private var loggedHostH = -1
    private var loggedSrcId = 0
    private var lastUnderlayDy = 0

    private fun logGeometryOnce(w: Int, h: Int, dx: Float, dy: Float, src: View?) {
        val srcId = System.identityHashCode(src)
        if (w == loggedW && h == loggedH && host.width == loggedHostW &&
            host.height == loggedHostH && srcId == loggedSrcId
        ) {
            return
        }
        loggedW = w
        loggedH = h
        loggedHostW = host.width
        loggedHostH = host.height
        loggedSrcId = srcId
        Log.i(
            "WaThemer.Capture",
            "host=${host.width}x${host.height} node=${w}x$h scale=${params.downsample} " +
                "offset=($dx,$dy) underlayDy=$lastUnderlayDy " +
                "src=${src?.width}x${src?.height} srcLaidOut=${src?.isLaidOut}",
        )
    }

    /**
     * Refract the sharp capture, then blur. Never blur first: blur removes the detail
     * refraction bends. Lighting stays out of this graph or it would be blurred too.
     */
    private fun applyEffectIfNeeded(scale: Float, w: Int, h: Int) {
        val radius = params.blurRadius
        val es = effectScale
        val refracting = params.refractionEnabled && GlassShader.supported
        val unchanged = radius == effectRadius &&
            es == effectScaleUsed &&
            scale == effectDownsample &&
            w == effectWidth &&
            h == effectHeight &&
            refracting == effectRefracting &&
            !effectDirty
        if (unchanged) return

        effectRadius = radius
        effectScaleUsed = es
        effectDownsample = scale
        effectWidth = w
        effectHeight = h
        effectRefracting = refracting
        effectDirty = false

        // Divide the radius by effectScale only, never by downsample, so the on-screen blur is exact.
        // CLAMP, not DECAL: decal fades to transparent and leaves a dark halo at the glass edge.
        val nodeRadius = if (radius <= 0f) 0f else (radius / es).coerceAtLeast(1f)
        val blur = if (nodeRadius <= 0f) {
            null
        } else {
            RenderEffect.createBlurEffect(nodeRadius, nodeRadius, Shader.TileMode.CLAMP)
        }

        // The capture node stays sharp; see the kdoc on node.
        node.setRenderEffect(null)

        val rs = refractShader
        val refractEffect =
            if (rs != null && params.refractionEnabled &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ) {
                GlassShader.buildRefractEffect(rs, params, host.width, host.height, es)
            } else {
                null
            }

        // createChainEffect(outer, inner) runs inner first: refract, then blur, never the reverse.
        glassNode.setRenderEffect(
            when {
                refractEffect != null && blur != null ->
                    RenderEffect.createChainEffect(blur, refractEffect)
                refractEffect != null -> refractEffect
                else -> blur
            },
        )
    }

    /** One-shot latch so [verifyAncestryOnce] only walks the tree on the first draw. */
    private var ancestryChecked = false

    /** The setter checked while host.parent was null; re-verify attached, log and drop rather than throw mid-draw. */
    private fun verifyAncestryOnce(candidate: View?) {
        if (ancestryChecked || candidate == null || !host.isAttachedToWindow) return
        ancestryChecked = true
        var p: ViewParent? = host.parent
        while (p is ViewGroup) {
            if (p === candidate) {
                Log.w(
                    "WaThemer.Capture",
                    "BackdropCapture: source ${candidate.javaClass.simpleName} IS an ancestor of " +
                        "this pane; capture disabled to avoid recursing into an already-recording " +
                        "node. The pane will draw its underlay only.",
                )
                source = null
                return
            }
            p = p.parent
        }
    }

    /** The core invariant: an ancestor backdrop would recurse and crash, so throw at wiring time. */
    private fun assertNotAncestor(candidate: View?) {
        if (candidate == null) return
        var p = host.parent
        while (p is ViewGroup) {
            check(p !== candidate) {
                "BackdropCapture.source must not be an ancestor of the glass view; " +
                    "capturing it would recurse. Make the backdrop a SIBLING drawn before " +
                    "the glass view instead. See the class kdoc."
            }
            p = p.parent
        }
        check(candidate !== host) { "BackdropCapture.source cannot be the glass view itself." }
    }
}
