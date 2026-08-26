// Selected rows and messages, and the voice-lock pill. The state lives on the view that DREW the
// fill, never on its row: a display list is not rebuilt because the one above it was.
package com.wathemer.app.hooks.glass

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.AbsListView
import android.widget.FrameLayout
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.RectList
import com.wathemer.app.hooks.dispatch.ForegroundKillDispatcher
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

private val rowPopTag = tagKey("wathemer-row-pop")

private val rowScrollTag = tagKey("wathemer-row-scroll")

/** WhatsApp selects a row by swapping in an opaque ColorDrawable; its colour is reused so the user's theme stays in charge. */
private fun rowSelectionPane(v: View, source: ColorDrawable): Drawable {
    val c = source.color
    return SelectionPane(
        host = v,
        insetX = v.dp(ROW_SELECT_INSET_X_DP),
        insetY = v.dp(ROW_SELECT_INSET_Y_DP),
        radius = v.dp(ROW_SELECT_RADIUS_DP),
        fill = Color.argb(ROW_SELECT_ALPHA, Color.red(c), Color.green(c), Color.blue(c)),
        lift = glassTint(ROW_SELECT_LIFT),
        rim = glassTint(ROW_SELECT_RIM_ALPHA),
        rimWidth = v.dp(1f),
        sharp = { selectionBackdrop(v) },
        placement = { selectionWpPlacement },
    )
}

/** A transform, not an inset, so the content rises too; the same setBackground signal resets recycled rows. */
private fun popRow(v: View, up: Boolean) {
    val target = if (up) ROW_SELECT_SCALE else 1f
    (v.getTag(rowPopTag) as? ValueAnimator)?.cancel()
    if (v.scaleX == target) return
    // Detached means recycled: snap, or the next row inherits a half-finished scale.
    if (!v.isAttachedToWindow) {
        v.scaleX = target
        v.scaleY = target
        return
    }
    val anim = ValueAnimator.ofFloat(v.scaleX, target).apply {
        duration = if (up) 220L else 140L
        interpolator = if (up) {
            OvershootInterpolator(2.4f)
        } else {
            DecelerateInterpolator()
        }
        addUpdateListener { a ->
            val s = a.animatedValue as Float
            v.scaleX = s
            v.scaleY = s
        }
    }
    v.setTag(rowPopTag, anim)
    anim.start()
}

private var quoteMaskHookInstalled = false

/** Kills the quote's green corner mask per bind; abstains while a quote colour is set, QuoteAndLabelColors owns that case. */
internal fun installQuoteMaskKill(frameId: Int) {
    if (quoteMaskHookInstalled) return
    quoteMaskHookInstalled = true
    // Through the shared dispatcher, this setter had three interceptors; the gate must be evaluated per call.
    ForegroundKillDispatcher.kill(frameId) { !quoteColored }
    logOnce("quote corner-mask kill armed")
}

/** MSG_SELECT_ALPHA survives only for the no-pane fallback, which reshapes WhatsApp's own fill in place. */
private const val MSG_SELECT_ALPHA = 62

private const val MSG_SELECT_RIM_ALPHA = 70

/** Measured: the pill is a 159x501 stadium, so half its width, 29dp; the pane clamps anyway. */
private const val LOCK_PILL_RADIUS_DP = 29f

private const val MSG_SELECT_INSET_X_DP = 8f

private const val MSG_SELECT_INSET_Y_DP = 1f

/** Widened only where a bubble would overhang the base inset, so common rows keep one width. */
private const val MSG_SELECT_BUBBLE_PAD_DP = 2f

private const val MSG_SELECT_RADIUS_DP = 18f

private var msgSelectHooked = false

private val msgSelectRect = RectF()

private val msgSelectPaint = Paint(Paint.ANTI_ALIAS_FLAG)

/** Intercepted at BaseRecordingCanvas, a Canvas hook never fires; matched by shape, never by WhatsApp's theme colour. */
internal fun installMessageSelectionShape() {
    if (msgSelectHooked) return
    msgSelectHooked = true
    val canvasCls = runCatching { Class.forName("android.graphics.BaseRecordingCanvas") }
        .getOrNull() ?: Canvas::class.java
    // All three overloads: the plain-Canvas fallback has no delegation, and p.result = null stops double handling.
    var hooked = 0
    for (m in canvasCls.declaredMethods) {
        if (m.name != "drawRect") continue
        val types = m.parameterTypes
        if (types.isEmpty() || types.last() != Paint::class.java) continue
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    // Cheap gate first: this fires for every rect in the process.
                    if (lockPillDrawing && dropsLockFill(p)) {
                        p.result = null
                        return
                    }
                    val row0 = currentRow
                    val paint = p.args.last() as? Paint ?: return
                    val col = paint.color
                    val a = Color.alpha(col)
                    val row = row0 ?: return
                    if (a == 0 || a > 200 || paint.shader != null) return

                    val l: Float; val t: Float; val r: Float; val b: Float
                    when (val first = p.args[0]) {
                        is Float -> {
                            l = first; t = p.args[1] as Float
                            r = p.args[2] as Float; b = p.args[3] as Float
                        }
                        is RectF -> {
                            l = first.left; t = first.top; r = first.right; b = first.bottom
                        }
                        is Rect -> {
                            l = first.left.toFloat(); t = first.top.toFloat()
                            r = first.right.toFloat(); b = first.bottom.toFloat()
                        }
                        else -> return
                    }
                    if (row.width <= 0 || (r - l) < row.width * 0.98f) return
                    if (b - t < 1f) return
                    val canvas = p.thisObject as? Canvas ?: return

                    // The fill is the whole signal: WhatsApp draws it from the row's own onDraw, nothing else carries the state.
                    msgSelDrewThisRecord = true
                    if (msgSelectPaneActive()) {
                        // Nothing position-dependent may be recorded into a row (see GlassBubblePane); just drop the fill.
                        p.result = null
                        return
                    }

                    val ix = row.dp(MSG_SELECT_INSET_X_DP)
                    val iy = row.dp(MSG_SELECT_INSET_Y_DP)
                    msgSelectRect.set(l + ix, t + iy, r - ix, b - iy)
                    if (msgSelectRect.width() <= 0f || msgSelectRect.height() <= 0f) return
                    val rad = minOf(row.dp(MSG_SELECT_RADIUS_DP), msgSelectRect.height() / 2f)
                    msgSelectPaint.color =
                        Color.argb(MSG_SELECT_ALPHA, Color.red(col), Color.green(col), Color.blue(col))
                    canvas.drawRoundRect(msgSelectRect, rad, rad, msgSelectPaint)
                    // Skip the original: for a void method this is how the flat slab is dropped.
                    p.result = null
                    logOnce("message selection reshaped in-row, no pane (was %08x)".format(col))
                }
            })
            hooked++
        }
    }
    XposedBridge.log("[$TAG] message selection: $hooked drawRect overloads hooked")

    // drawRect is already covered above, never two hooks on one hot method; a stadium needs the two shape calls too.
    var shapes = 0
    for (m in canvasCls.declaredMethods) {
        if (m.name != "drawRoundRect" && m.name != "drawPath") continue
        if (m.parameterTypes.lastOrNull() != Paint::class.java) continue
        runCatching {
            XposedBridge.hookMethod(m, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!lockPillDrawing) return
                    if (dropsLockFill(p)) p.result = null
                }
            })
            shapes++
        }
    }
    XposedBridge.log("[$TAG] lock pill: $shapes fill shapes hooked")
}

private val msgSelFillTag = tagKey("wathemer-msg-fill-owner")

private val msgSelOwnerTag = tagKey("wathemer-msg-owner-ref")

private var convMsgSelectPaneRef: WeakReference<GlassBubblePane>? = null

private val msgSelectAt = IntArray(2)

/** True once the selection pane is in the tree, i.e. once it is the one painting the panel. */
private fun msgSelectPaneActive(): Boolean =
    convMsgSelectPaneRef?.get()?.let { it.parent != null } == true

/** Runs after ensureBubblePane and both insert at 0, so the panel lands beneath the bubbles; do not reorder. */
internal fun ensureMsgSelectPane(listHost: ViewGroup, list: AbsListView) {
    val existing = convMsgSelectPaneRef?.get()
    if (existing != null && existing.parent === listHost) return
    if (listHost !is FrameLayout) {
        logOnce("message selection pane skipped: ${listHost.javaClass.simpleName} does not stack")
        return
    }
    val d = listHost.resources.displayMetrics.density
    val pane = GlassBubblePane(listHost.context)
    pane.params = GlassParams(d).apply {
        blurRadius = d * BLUR_DP
        cornerRadius = d * MSG_SELECT_RADIUS_DP
        refractionEnabled = true
        bevelFraction = BEVEL_FRACTION
        depthRatio = DEPTH_RATIO
        maxDisplacePx = d * DISPLACE_DP
        fresnelStrength = 0.5f
        tintColor = glassTintColor
    }
    pane.tint = { glassTintColor }
    // Same wallpaper, mapping and dirty flag as the bubbles: one material that cannot drift.
    pane.backdrop = { bubbleBackdrop(pane) }
    pane.placement = { bubbleWpPlacement }
    pane.dim = { 0f }
    pane.rimColor = glassTint(MSG_SELECT_RIM_ALPHA)
    pane.rimWidth = d
    pane.collect = { out -> collectMsgSelectRects(out) }
    pane.onGeometryChanged = { markWallpaperGeometryDirty() }
    listHost.addView(
        pane, 0,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    convMsgSelectPaneRef = WeakReference(pane)
    // Rows already on screen reshaped their own fill; re-record so they stop and latch their tag.
    for (i in 0 until list.childCount) list.getChildAt(i)?.invalidate()
    XposedBridge.log("[$TAG] message selection pane inserted under the bubble pane")
}

/** The mark lives on the view that drew the fill; only that owner re-recording without one counts as deselection. */
internal fun latchMsgSelection(v: View, row: View, drew: Boolean) {
    val owns = v.getTag(msgSelFillTag) != null
    if (drew == owns) return
    v.setTag(msgSelFillTag, if (drew) true else null)
    // The row only ever caches a handle to the owner. It is not the state; see [msgSelectOwnerOf].
    if (drew) row.setTag(msgSelOwnerTag, WeakReference(v))
    // Ask for another frame: this traversal's pre-draw has already run and nothing else is scheduled.
    convMsgSelectPaneRef?.get()?.postInvalidateOnAnimation()
}

/** The state lives on the owner, never the row; every check below matters. */
private fun msgSelectOwnerOf(row: View): View? {
    val owner = (row.getTag(msgSelOwnerTag) as? WeakReference<*>)?.get() as? View
        ?: return null
    if (owner.getTag(msgSelFillTag) == null) return null
    if (!owner.isAttachedToWindow) return null
    // Visibility is the deselection signal: WhatsApp hides the owner, and invalidate() cannot reach a hidden view.
    if (!owner.isShown) return null
    var a: View? = owner
    var hop = 0
    while (a != null && a !== row && hop < 6) {
        a = a.parent as? View
        hop++
    }
    return if (a === row) owner else null
}

/** Rebuilt per frame; a row whose height changed since it recorded was re-bound, so its mark is skipped. */
private fun collectMsgSelectRects(out: RectList) {
    val list = convListRef?.get() ?: return
    val insetX = list.dp(MSG_SELECT_INSET_X_DP)
    val insetY = list.dp(MSG_SELECT_INSET_Y_DP)
    for (i in 0 until list.childCount) {
        val row = list.getChildAt(i) ?: continue
        if (row.width <= 0 || row.height <= 0 || row.visibility != View.VISIBLE) continue
        if (msgSelectOwnerOf(row) == null) continue
        row.getLocationOnScreen(msgSelectAt)
        var l = insetX
        var r = row.width - insetX
        // Widen to the bubble where it is wider; a stale-height mark means re-bound, so it is not trusted (BubbleMark).
        val mark = bubbleBoundsByRow[row]
        if (mark != null && mark.rowH == row.height) {
            val pad = list.dp(MSG_SELECT_BUBBLE_PAD_DP)
            l = minOf(l, mark.rect.left - pad)
            r = maxOf(r, mark.rect.right + pad)
        }
        l = l.coerceAtLeast(0f)
        r = r.coerceAtMost(row.width.toFloat())
        val t = insetY
        val b = row.height - insetY
        if (r <= l || b <= t) continue
        out.add(msgSelectAt[0] + l, msgSelectAt[1] + t, msgSelectAt[0] + r, msgSelectAt[1] + b)
    }
}

/** Opaque and big, both needed: the glyphs use the same primitives, and only the fill spans the pill. */
private fun dropsLockFill(p: XC_MethodHook.MethodHookParam): Boolean {
    if (lockPillW <= 0 || lockPillH <= 0) return false
    val paint = p.args.lastOrNull() as? Paint ?: return false
    if (Color.alpha(paint.color) < 200) return false
    val a0 = p.args.getOrNull(0)
    val w: Float
    val h: Float
    when (a0) {
        is RectF -> { w = a0.width(); h = a0.height() }
        is Rect -> { w = a0.width().toFloat(); h = a0.height().toFloat() }
        is Path -> {
            val b = RectF()
            a0.computeBounds(b, true)
            w = b.width(); h = b.height()
        }
        is Float -> {
            val l = a0
            val t = p.args.getOrNull(1) as? Float ?: return false
            val r = p.args.getOrNull(2) as? Float ?: return false
            val bo = p.args.getOrNull(3) as? Float ?: return false
            w = r - l; h = bo - t
        }
        else -> return false
    }
    return w >= lockPillW * 0.8f && h >= lockPillH * 0.8f
}

private var lockPaneRef: WeakReference<GlassBubblePane>? = null

internal var lockHostRef: WeakReference<ViewGroup>? = null

private val lockAt = IntArray(2)

/** Set only while the lock pill records; it paints its own fill in onDraw, so clearBg cannot reach it. */
internal var lockPillDrawing = false

internal var lockPillW = 0

internal var lockPillH = 0

/** The full-screen container is the host, never a frost target; the pill is resolved per frame, not captured. */
internal fun ensureLockPane(container: View) {
    val host = container as? FrameLayout ?: run {
        logOnce("lock pane skipped: ${container.javaClass.simpleName} does not stack")
        return
    }
    val existing = lockPaneRef?.get()
    if (existing != null && existing.parent === host) return
    val d = host.resources.displayMetrics.density
    val pane = GlassBubblePane(host.context)
    pane.params = GlassParams(d).apply {
        blurRadius = d * BLUR_DP
        // The pane clamps to half the shorter side, so an oversize radius just means fully round.
        cornerRadius = d * LOCK_PILL_RADIUS_DP
        refractionEnabled = true
        bevelFraction = BEVEL_FRACTION
        depthRatio = DEPTH_RATIO
        maxDisplacePx = d * DISPLACE_DP
        fresnelStrength = 0.5f
        tintColor = glassTintColor
    }
    pane.tint = { glassTintColor }
    pane.backdrop = { bubbleBackdrop(pane) }
    pane.placement = { bubbleWpPlacement }
    pane.dim = { 0f }
    pane.rimColor = glassTint(BUBBLE_RIM_ALPHA)
    pane.rimWidth = d
    pane.collect = { out -> collectLockRect(host, out) }
    pane.onGeometryChanged = { markWallpaperGeometryDirty() }
    host.addView(
        pane, 0,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    lockPaneRef = WeakReference(pane)
    lockHostRef = WeakReference(host)
    XposedBridge.log("[$TAG] lock pill pane inserted behind voice_note_lock_container")
}

/** The lock pill's rect in screen pixels, or nothing while no recording is in progress. */
private fun collectLockRect(host: FrameLayout, out: RectList) {
    for (i in 0 until host.childCount) {
        val c = host.getChildAt(i) ?: continue
        if (c is GlassBubblePane) continue
        if (!c.isShown || c.width <= 0 || c.height <= 0) continue
        c.getLocationOnScreen(lockAt)
        out.add(
            lockAt[0].toFloat(), lockAt[1].toFloat(),
            (lockAt[0] + c.width).toFloat(), (lockAt[1] + c.height).toFloat(),
        )
    }
}

// ══ Home -> conversation ════════════════════════════════════════════════════════════════
// A separate Activity, so no cross-window dissolve; a plain fade both ways.
internal var rowContainerId = 0

/** The Calls tab's row. A different id from the chat list's; see the branch in [ensureBgHook]. */
internal var callRowContainerId = 0

private var bgHookInstalled = false

@Synchronized
internal fun ensureBgHook() {
    if (bgHookInstalled) return
    bgHookInstalled = true
    runCatching {
        XposedHelpers.findAndHookMethod(
            View::class.java, "setBackground", Drawable::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    val incoming = param.args[0] as? Drawable

                    // Selection rows must see null too (WhatsApp clears them with it) and stay in this hook; a second interceptor started the foreground fights.
                    // Two ids: the Calls tab's rows are call_row_container, not the chat list's. Selected message rows are deliberately not handled.
                    if ((rowContainerId != 0 && v.id == rowContainerId) ||
                        (callRowContainerId != 0 && v.id == callRowContainerId)
                    ) {
                        if (incoming is ColorDrawable) {
                            param.args[0] = rowSelectionPane(v, incoming)
                            popRow(v, up = true)
                            if (v.getTag(rowScrollTag) == null) {
                                val w = RowScrollWatch(v)
                                v.setTag(rowScrollTag, w)
                                w.arm()
                            }
                        } else if (incoming !is SelectionPane) {
                            popRow(v, up = false)
                        }
                        return
                    }

                    if (incoming == null) return
                    val want = synchronized(forcedBg) { forcedBg[v] } ?: return
                    if (incoming === want) return
                    val label = synchronized(forcedBgLabels) { forcedBgLabels[v] } ?: "view"
                    param.args[0] = want
                    logOnce("re-asserted background on $label")
                }
            },
        )
    }.onFailure { XposedBridge.log("[$TAG] setBackground hook failed: $it") }

    // setBackgroundColor mutates an existing ColorDrawable in place and never reaches setBackground, so both setters are hooked.
    runCatching {
        XposedHelpers.findAndHookMethod(
            View::class.java, "setBackgroundColor", Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args[0] == Color.TRANSPARENT) return
                    val v = param.thisObject as? View ?: return
                    val label = synchronized(forcedBgLabels) { forcedBgLabels[v] } ?: return
                    param.args[0] = Color.TRANSPARENT
                    logOnce("suppressed a re-applied bg COLOR on $label")
                }
            },
        )
    }.onFailure { XposedBridge.log("[$TAG] setBackgroundColor hook failed: $it") }
}
