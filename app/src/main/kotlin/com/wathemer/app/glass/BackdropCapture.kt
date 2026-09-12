package com.wathemer.app.glass

import android.graphics.RecordingCanvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewParent
import kotlin.math.abs
import kotlin.math.ceil

/** Captures the content behind a glass view into a [RenderNode], blurred; a source that holds this pane, directly or through another capture, is refused, or the render tree would loop. */
class BackdropCapture(
    private val host: View,
    private val params: GlassParams,
) {

    companion object {
        /** Skia caps the linear blur kernel at sigma 4 and rescales above it; stay under with margin. */
        private const val NODE_SIGMA_CAP = 3.9f

        /** Past this the transmission is too coarse to bend anything; a huge blur takes one rescale pass instead. */
        private const val MAX_EFFECT_SCALE = 8f

        /** HWUI's radius to sigma mapping for createBlurEffect, and its inverse. */
        private fun sigmaOf(radiusPx: Float): Float = if (radiusPx > 0f) 0.57735f * radiusPx + 0.5f else 0f
        private fun radiusOf(sigma: Float): Float = if (sigma > 0.5f) (sigma - 0.5f) / 0.57735f else 0f

        /** True only while live views draw into a pane's own RenderNode: a ripple drawn there arms its animator against that node, and the frame's real draw throws "Target already set!". */
        @Volatile
        @JvmStatic
        var capturing: Boolean = false
            private set

        /** Every attached capture, main thread only; the cycle guard walks it on each capture. */
        private val live = ArrayList<BackdropCapture>()
        private val stack = ArrayList<BackdropCapture>()
        private val visited = ArrayList<BackdropCapture>()

        /** Set by the hook side so a refusal reaches the module log; the engine has no Xposed. */
        @JvmStatic
        var onRefused: ((String) -> Unit)? = null

        /** A live pane with a rim captures at this divisor instead of its own, so the rim has half-resolution content to bend. */
        private const val RIM_DOWNSAMPLE = 2f

        /** Panes below this smaller side gain nothing from a rim and would pay a whole pass for it. */
        private const val RIM_MIN_SIDE_DP = 70f

        /** The live-pane rim costs an offscreen layer and a pass per pane; the hook turns it on only when asked. */
        @JvmStatic
        var liveRim: Boolean = false

    }

    /** The subtree to blur; null disables capture. Not checked here: wiring runs before the host has a parent. */
    var source: View? = null

    /** Views painted beneath [source] at their own screen positions; no legal backdrop contains the wallpaper. */
    var underlay: List<View> = emptyList()

    private val underlayLocation = IntArray(2)

    /** True once a capture has produced content that is safe to draw. */
    var hasContent: Boolean = false
        private set

    /** [node] keeps the capture sharp, since refraction can only bend detail that still exists; [glassNode] redraws it at effectScaleFor's divisor and carries the shader with the blur chained after. */
    val node = RenderNode("wathemer-backdrop")
    private val glassNode = RenderNode("wathemer-glass")

    /** The band from the sharp node for a pane whose backdrop is live content; the wallpaper-only panes take theirs from the bitmap. */
    private val rimNode = RenderNode("wathemer-rim")
    private var rimHasContent = false
    private var rimW = 0
    private var rimH = 0
    private var rimDetail = -1f
    private var rimScale = -1f
    private var rimWidth = -1
    private var rimHeight = -1

    private val rimShader: RuntimeShader? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) GlassShader.newRimShader() else null
    }

    private val hostLocation = IntArray(2)
    private val sourceLocation = IntArray(2)

    // Rebuilt only on input change (per-frame effects allocate); effectDirty stands in for ten uniforms.
    /** Transmission divisor: coarse enough that the blur's sigma stays under Skia's cap, so no rescale passes; large surfaces never below 2. */
    private fun effectScaleFor(): Float {
        val large = if (host.width.toLong() * host.height > 600_000L) 2f else 1f
        return maxOf(large, sigmaOf(params.blurRadius) / NODE_SIGMA_CAP).coerceAtMost(MAX_EFFECT_SCALE)
    }

    // The node's size, kept from the capture for the draw: the divisor is derived once, never twice.
    private var nodeW = 0
    private var nodeH = 0

    private var effectRadius = -1f
    private var effectScaleUsed = -1f
    private var effectDownsample = -1f
    private var effectWidth = -1
    private var effectHeight = -1
    private var effectRefracting = false
    private var effectLens = 1f

    /** Thins the glass while a pane assembles; this rebuilds the chain, so only an animation may move it. */
    private var lensScale = 1f
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
        val src = source?.takeIf { safeToCapture(it) }
        // Underlay-only panes are legal: for some panes every capturable subtree is an ancestor.
        if (src == null && underlay.isEmpty()) return false.also { hasContent = false }
        if (src != null && !src.isLaidOut) return false
        if (host.width <= 0 || host.height <= 0) return false

        // A live pane with a rim captures finer, so the band has something to bend; the frost path is unchanged by it.
        val rimLive = liveRim && src != null && params.detail > 0f && params.refractionEnabled &&
            minOf(host.width, host.height) >= RIM_MIN_SIDE_DP * params.density && rimShader != null
        val scale = if (rimLive) minOf(params.downsample, RIM_DOWNSAMPLE) else params.downsample
        val es = effectScaleFor()

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

        val w = ceil(host.width / scale).toInt().coerceAtLeast(1)
        val h = ceil(host.height / scale).toInt().coerceAtLeast(1)

        logGeometryOnce(w, h, dx, dy, src, es, scale)

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

        // Read before the main effect resets it: the rim effect keys on the same staleness.
        val dirty = effectDirty
        applyEffectIfNeeded(scale, w, h, es)
        applyRimEffectIfNeeded(rimLive, scale, w, h, dirty)

        // Second pass at 1/es: redraw the capture, refract, then blur; order is the whole point.
        val fw = ceil(host.width / es).toInt().coerceAtLeast(1)
        val fh = ceil(host.height / es).toInt().coerceAtLeast(1)
        nodeW = fw
        nodeH = fh
        glassNode.setPosition(0, 0, fw, fh)
        val c = glassNode.beginRecording(fw, fh)
        try {
            // Fill the node exactly: a fractional divisor would leave its last column empty for the clamp blur to smear inward.
            c.scale(fw.toFloat() / w, fh.toFloat() / h)
            c.drawRenderNode(node)
        } finally {
            glassNode.endRecording()
        }

        if (rimLive) {
            // The sharp node again, bent by the rim program at the capture's own divisor; the plateau returns nothing.
            val rw = ceil(host.width / scale).toInt().coerceAtLeast(1)
            val rh = ceil(host.height / scale).toInt().coerceAtLeast(1)
            rimW = rw
            rimH = rh
            rimNode.setPosition(0, 0, rw, rh)
            val rc = rimNode.beginRecording(rw, rh)
            try {
                rc.drawRenderNode(node)
            } finally {
                rimNode.endRecording()
            }
            rimHasContent = true
        } else {
            rimHasContent = false
        }

        hasContent = true
        return true
    }

    /** The rim node's program, rebuilt on the main effect's staleness plus a detail change. */
    private fun applyRimEffectIfNeeded(rimLive: Boolean, scale: Float, w: Int, h: Int, dirty: Boolean) {
        if (!rimLive) {
            if (rimScale >= 0f) {
                rimNode.setRenderEffect(null)
                rimScale = -1f
            }
            return
        }
        val rs = rimShader ?: return
        val detail = params.detail
        if (!dirty && scale == rimScale && w == rimWidth && h == rimHeight && detail == rimDetail) return
        rimScale = scale
        rimWidth = w
        rimHeight = h
        rimDetail = detail
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The capture carries the dim already, so the program dims nothing; the band is placed at the node's divisor.
            GlassShader.applyRimUniforms(rs, params, host.width, host.height, 0f, detail, scale)
            rimNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(rs, "content"))
        }
    }

    /** The bend grows in with the material. Unlike the alpha this rebuilds the chain, so it rides an animator. */
    fun setLensScale(v: Float) {
        val s = v.coerceIn(0.02f, 1f)
        if (s == lensScale) return
        // A step below a fiftieth is invisible and would buy a rebuild; the settled value is always taken.
        if (s != 1f && abs(s - lensScale) < 0.02f) return
        lensScale = s
        effectDirty = true
    }

    /** Scales only the transmitted image. A display-list property, so the effect chain is never rebuilt for it. */
    fun setContentAlpha(a: Float) {
        val v = a.coerceIn(0f, 1f)
        if (glassNode.alpha != v) glassNode.alpha = v
    }

    /** The rim follows materialize itself, not the content's boosted curve, so both rim paths assemble alike. */
    fun setRimAlpha(a: Float) {
        val v = a.coerceIn(0f, 1f)
        if (rimNode.alpha != v) rimNode.alpha = v
    }

    /** Draw [glassNode] at [host]'s size; it carries both effects, so no path may skip it. */
    fun draw(canvas: RecordingCanvas) {
        if (!hasContent) return
        val fw = nodeW
        val fh = nodeH
        if (fw <= 0 || fh <= 0) return
        if (fw == host.width && fh == host.height) {
            canvas.drawRenderNode(glassNode)
        } else {
            val save = canvas.save()
            // The node's own size, never the divisor: the two differ by the ceil.
            canvas.scale(host.width.toFloat() / fw, host.height.toFloat() / fh)
            canvas.drawRenderNode(glassNode)
            canvas.restoreToCount(save)
        }
        // The rim over the frost and under the light pass, at the capture's own size.
        if (rimHasContent && rimW > 0 && rimH > 0) {
            val save = canvas.save()
            canvas.scale(host.width.toFloat() / rimW, host.height.toFloat() / rimH)
            canvas.drawRenderNode(rimNode)
            canvas.restoreToCount(save)
        }
    }

    fun release() {
        node.discardDisplayList()
        glassNode.discardDisplayList()
        rimNode.discardDisplayList()
        rimHasContent = false
        rimScale = -1f
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

    private fun logGeometryOnce(w: Int, h: Int, dx: Float, dy: Float, src: View?, es: Float, scale: Float) {
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
            "host=${host.width}x${host.height} node=${w}x$h scale=$scale es=$es " +
                "offset=($dx,$dy) underlayDy=$lastUnderlayDy " +
                "src=${src?.width}x${src?.height} srcLaidOut=${src?.isLaidOut}",
        )
    }

    /** Refract the sharp capture, then blur, never the reverse: blur removes the detail refraction bends, and lighting stays out of this graph or it would be blurred too. */
    private fun applyEffectIfNeeded(scale: Float, w: Int, h: Int, es: Float) {
        val radius = params.blurRadius
        val refracting = params.refractionEnabled && GlassShader.supported
        val unchanged = lensScale == effectLens &&
            radius == effectRadius &&
            es == effectScaleUsed &&
            scale == effectDownsample &&
            w == effectWidth &&
            h == effectHeight &&
            refracting == effectRefracting &&
            !effectDirty
        if (unchanged) return

        effectLens = lensScale
        effectRadius = radius
        effectScaleUsed = es
        effectDownsample = scale
        effectWidth = w
        effectHeight = h
        effectRefracting = refracting
        effectDirty = false

        // Divide the sigma, not the radius: HWUI's mapping has a constant term, and only this keeps the on-screen blur exact at any es.
        // CLAMP, not DECAL: decal fades to transparent and leaves a dark halo at the glass edge.
        val nodeRadius = if (radius <= 0f) 0f else radiusOf(sigmaOf(radius) / es).coerceAtLeast(1f)
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
                GlassShader.buildRefractEffect(rs, params, host.width, host.height, es, lensScale)
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

    /** Register while attached and [unregister] on detach. Main thread only. */
    fun register() {
        if (!live.contains(this)) live.add(this)
    }

    fun unregister() {
        live.remove(this)
    }

    private var refusedFor: View? = null

    /** False when drawing [src] would put this pane's node inside its own capture; HWUI never stops that recursion. */
    private fun safeToCapture(src: View): Boolean {
        if (!host.isAttachedToWindow) return false
        val reason = when {
            isAncestorOf(src, host) -> "it is an ancestor of the pane"
            reachesBack(src) -> "a pane inside it captures this one"
            else -> null
        }
        if (reason == null) {
            refusedFor = null
            return true
        }
        // Once per source: the sync that re-arms it runs every layout and would otherwise flood the log.
        if (refusedFor !== src) {
            refusedFor = src
            val where = (host.parent as? View)?.let { describe(it) } ?: "?"
            onRefused?.invoke("capture of ${describe(src)} refused for the pane in $where: $reason; underlay only")
        }
        return false
    }

    /** True when some pane under [src] captures a subtree holding this pane: the chain would come back round. */
    private fun reachesBack(src: View): Boolean {
        stack.clear()
        visited.clear()
        for (c in live) if (c !== this && c.source != null && isAncestorOf(src, c.host)) stack.add(c)
        while (stack.isNotEmpty()) {
            val q = stack.removeAt(stack.size - 1)
            if (visited.contains(q)) continue
            visited.add(q)
            val qs = q.source ?: continue
            if (isAncestorOf(qs, host)) return true
            for (c in live) {
                if (c !== this && c.source != null && !visited.contains(c) && isAncestorOf(qs, c.host)) stack.add(c)
            }
        }
        return false
    }

    /** The engine's own walk; glass/ imports nothing from hooks/, so GlassCore's twin stays out of reach on purpose. */
    private fun isAncestorOf(a: View, v: View): Boolean {
        var p: ViewParent? = v.parent
        while (p != null) {
            if (p === a) return true
            p = p.parent
        }
        return false
    }

    private fun describe(v: View): String {
        val name = runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() ?: v.id.toString()
        return "${v.javaClass.simpleName}#$name"
    }
}
