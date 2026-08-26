// Chat bubbles. Rows carry no id and host no child, so the bubble drawable is replaced and one pane
// behind the list draws them all; nothing recorded inside a row may depend on its screen position.
package com.wathemer.app.hooks.glass

import android.app.Application
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ListView
import com.wathemer.app.BuildConfig
import com.wathemer.app.glass.GlassBubbleDrawable
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.RectList
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.settings.prefs.GlassDefaults
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Bubble corner radius. Independent of the card radius slider; bubbles want their own. */
private const val BUBBLE_RADIUS_DP = 16f

/** Merged continuations keep this much corner; the shader's own corner smoothing softens it further. */
private const val BUBBLE_FLAT_RADIUS_DP = 4f

private var bubbleMergeOn = false

/** Follows the slider so the user's control still works; 5 heavier because a bubble transmits only wallpaper. */
private const val BUBBLE_TINT_BOOST = 5

/** The bubble tint: the slider's value plus [BUBBLE_TINT_BOOST], clamped to a legal alpha. */
private val bubbleTintColor: Int
    get() = glassTint((TINT_ALPHA + BUBBLE_TINT_BOOST).coerceIn(0, 255))

/** Fallback rim, drawn only when AGSL is unavailable; otherwise the light pass draws the edge. */
internal const val BUBBLE_RIM_ALPHA = 46

/** The panes' recipe, field for field; fresh per drawable, a shared GlassParams is last-writer-wins on bevelThickness. */
private fun bubbleParams(density: Float) = GlassParams(density).apply {
    refractionEnabled = true
    bevelFraction = BEVEL_FRACTION
    depthRatio = DEPTH_RATIO
    maxDisplacePx = DISPLACE_DP * density
    fresnelStrength = 0.5f
    cornerRadius = BUBBLE_RADIUS_DP * density
    // tintColor deliberately unset: it resolves after WhatsApp asks for this drawable, so a provider supplies it.
}

/** PRIORITY_HIGHEST so this result wins over BubbleShapes' hook; the user's bubble colours are deliberately ignored while glass is on. */
internal fun installBubbleGlass(app: Application) {
    val cl = app.classLoader
    // Open the bridge ourselves: never rely on BubbleColors or BubbleShapes to have opened it.
    val dex = Deobfuscator
    if (!dex.ensureBridge(app)) {
        XposedBridge.log("[$TAG] DexKit bridge unavailable; no bubble glass")
        return
    }
    val method = dex.loadBubbleDrawableMethod(cl) ?: run {
        XposedBridge.log("[$TAG] bubble drawable method unresolved; no bubble glass")
        return
    }
    // Persist the lookup so the next launch is a cache hit.
    runCatching { dex.saveCache() }
    var left = 0
    var right = 0
    runCatching {
        val p = XSharedPreferences(
            BuildConfig.APPLICATION_ID,
            Prefs.FILE,
        )
        p.reload()
        left = p.getInt(Prefs.BUBBLE_LEFT_BG, 0)
        right = p.getInt(Prefs.BUBBLE_RIGHT_BG, 0)
        bubbleMergeOn = p.getBoolean(Prefs.KEY_GLASS_BUBBLE_MERGE, GlassDefaults.BUBBLE_MERGE)
    }
    // Logged, not used: both sides share one tint, and this line answers "the bubbles are not my colour".
    XposedBridge.log(
        "[$TAG] bubble glass arming on ${method.name} " +
            "(prefs left=%08x right=%08x; deliberately ignored, one glass tint; merge=%b)"
                .format(left, right, bubbleMergeOn),
    )
    XposedBridge.hookMethod(
        method,
        object : XC_MethodHook(PRIORITY_HIGHEST) {
            override fun afterHookedMethod(param: MethodHookParam) {
                // WA convention, from BubbleColors: position 3 is outgoing, anything else incoming.
                val position = param.args.getOrNull(0) as? Int ?: return
                val density = app.resources.displayMetrics.density
                // Arg 1 is WA's own collapse state, named by its error string; 2 and 3 are the tail-less continuations.
                val collapse = param.args.getOrNull(1) as? Int ?: -1
                val flag = if (bubbleMergeOn && (position == 2 || position == 3) && (collapse == 2 || collapse == 3)) {
                    GlassBubblePane.FLAG_EXT or (if (position == 3) GlassBubblePane.FLAG_OUTGOING else 0)
                } else {
                    0
                }
                // One tint for both sides; sender is carried by alignment, per-side colour belongs behind an explicit opt-in.
                val bp = bubbleParams(density)
                param.result = GlassBubbleDrawable(
                    params = bp,
                    // A lambda, not a baked colour: the channel resolves after WhatsApp asks for this drawable.
                    tint = { bubbleTintColor },
                    density = density,
                    rimColor = glassTint(BUBBLE_RIM_ALPHA),
                    rimWidth = density,
                    // 0 when the dim is already inside the bitmap, which is the normal case.
                    dim = if (bubbleDimFolded) 0f else bubbleDim,
                    backdrop = { host -> bubbleBackdrop(host) },
                    placement = { bubbleWpPlacement },
                    rowProvider = { currentRow },
                    // A copy is stored: bounds is the drawable's live Rect and WhatsApp mutates it for the next row.
                    report = { row, bounds, f ->
                        // Stored at rest: the swipe offset is subtracted here and collectBubbleRects adds the live one back.
                        val dx = rowDisplacement(row).toInt()
                        val cur = bubbleBoundsByRow[row]
                        if (cur == null) {
                            bubbleBoundsByRow[row] =
                                BubbleMark(
                                    Rect(bounds).also { it.offset(-dx, 0) },
                                    row.height,
                                    f,
                                )
                        } else {
                            if (cur.rect.left != bounds.left - dx || cur.rect.right != bounds.right - dx ||
                                cur.rect.top != bounds.top || cur.rect.bottom != bounds.bottom
                            ) {
                                cur.rect.set(bounds)
                                cur.rect.offset(-dx, 0)
                            }
                            cur.rowH = row.height
                            cur.flag = f
                        }
                    },
                    paneActive = { bubblePaneActive() },
                    flag = flag,
                    flatRadiusPx = if (bubbleMergeOn) BUBBLE_FLAT_RADIUS_DP * density else 0f,
                )
                logOnce("bubble glass applied (position=$position)")
            }
        },
    )
}

private val innerRoundTag = tagKey("wathemer-inner-round")

/** Inner corner slightly tighter than the bubble's so the arcs look parallel; rows recycle, so the paint is idempotent. */
internal fun roundInnerSurface(v: View, what: String) {
    // clipChildren=false is WhatsApp saying content overflows this box; an outline clip cuts it off instead.
    if ((v as? ViewGroup)?.clipChildren == false) return
    val r = (BUBBLE_RADIUS_DP - 4f).coerceAtLeast(2f) * v.resources.displayMetrics.density
    if (v.outlineProvider !is InnerRound) {
        v.outlineProvider = InnerRound(r)
        v.clipToOutline = true
        logOnce("inner surface rounded: $what")
    }
    v.invalidateOutline()
    if (v.getTag(innerRoundTag) == null) {
        v.setTag(innerRoundTag, true)
        v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> view.invalidateOutline() }
    }
}

/** Weak keys, rows recycle; only the row-relative rect is cached, the screen position is re-read every frame. */
internal val bubbleBoundsByRow = WeakHashMap<View, BubbleMark>()

/** rowH is a staleness check: a re-bound row re-reports, so a height mismatch means do not trust the rect. */
internal class BubbleMark(val rect: Rect, var rowH: Int, var flag: Int = 0)

/** Roots whose rows carry no bubble: a sticker draws bare, a video note draws a circle. */
internal var bubblelessRootIds = IntArray(0)

private val bubblelessRowCache = WeakHashMap<View, Boolean>()

/** Painting a bubble for these puts a slab where WhatsApp shows none. */
private fun isBubblelessRow(row: View): Boolean {
    if (bubblelessRootIds.isEmpty()) return false
    bubblelessRowCache[row]?.let { return it }
    val found = bubblelessRootIds.any { row.findViewById<View>(it) != null }
    bubblelessRowCache[row] = found
    return found
}

internal var convBubblePaneRef: WeakReference<GlassBubblePane>? = null

private val bubbleRowAt = IntArray(2)

private val bubbleRectScratch = RectF()

private fun bubblePaneActive(): Boolean =
    convBubblePaneRef?.get()?.let { it.parent != null } == true

/** One pane behind the list draws every bubble's glass; in-row glass freezes on scroll, see GlassBubblePane. */
internal fun ensureBubblePane(listHost: ViewGroup, list: AbsListView) {
    val existing = convBubblePaneRef?.get()
    if (existing != null && existing.parent === listHost) return
    if (listHost !is FrameLayout) {
        logOnce("bubble pane skipped: ${listHost.javaClass.simpleName} does not stack children")
        return
    }
    val density = listHost.resources.displayMetrics.density
    val pane = GlassBubblePane(listHost.context)
    pane.params = bubbleParams(density)
    pane.tint = { bubbleTintColor }
    // The pane is its own backdrop host; convFooterRef can be null and depends on discovery order.
    pane.backdrop = { bubbleBackdrop(pane) }
    pane.placement = { bubbleWpPlacement }
    pane.dim = { if (bubbleDimFolded) 0f else bubbleDim }
    pane.rimColor = glassTint(BUBBLE_RIM_ALPHA)
    pane.rimWidth = density
    pane.collect = { out -> collectBubbleRects(out) }
    pane.flatRadiusPx = if (bubbleMergeOn) BUBBLE_FLAT_RADIUS_DP * density else 0f
    // Rotation, split screen and folds resize this pane; the bitmap survives, the mapping does not.
    pane.onGeometryChanged = { markWallpaperGeometryDirty() }
    listHost.addView(
        pane, 0,
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ),
    )
    convBubblePaneRef = WeakReference(pane)
    // One forced re-record: pre-hook rows never reported, and pre-pane rows painted their own glass.
    for (i in 0 until list.childCount) list.getChildAt(i)?.invalidate()
    XposedBridge.log("[$TAG] bubble pane inserted behind the conversation list")
}

/** The row never moves during a swipe; a descendant carries the translation, first non-zero wins. */
private fun rowDisplacement(v: View, depth: Int = 0): Float {
    if (v.translationX != 0f) return v.translationX
    if (v is ViewGroup && depth < 3) {
        for (c in 0 until v.childCount) {
            val r = rowDisplacement(v.getChildAt(c) ?: continue, depth + 1)
            if (r != 0f) return r
        }
    }
    return 0f
}

/** Rebuilt per frame, no allocation; a row with no mark has not drawn since the hook armed and is skipped. */
private fun collectBubbleRects(out: RectList) {
    val list = convListRef?.get() ?: return
    val density = list.resources.displayMetrics.density
    val insetX = density
    val insetY = 2f * density
    for (i in 0 until list.childCount) {
        val row = list.getChildAt(i) ?: continue
        if (row.height <= 0 || row.visibility != View.VISIBLE) continue
        val mark = bubbleBoundsByRow[row] ?: continue
        // See BubbleMark: a height mismatch means re-bound and not yet re-reported, so the rect is not trusted.
        if (mark.rowH != row.height) continue
        if (isBubblelessRow(row)) continue
        GlassBubbleDrawable.clamp(mark.rect, row.height, insetX, insetY, bubbleRectScratch)
        if (bubbleRectScratch.width() <= 0f || bubbleRectScratch.height() <= 0f) continue
        row.getLocationOnScreen(bubbleRowAt)
        // The mark is at rest; adding the live descendant translation back gives the exact screen position.
        val swipeDx = rowDisplacement(row)
        out.add(
            bubbleRowAt[0] + bubbleRectScratch.left + swipeDx,
            bubbleRowAt[1] + bubbleRectScratch.top,
            bubbleRowAt[0] + bubbleRectScratch.right + swipeDx,
            bubbleRowAt[1] + bubbleRectScratch.bottom,
            mark.flag,
        )
    }
    // Deliberately no frame driver: report stores rest, swipeDx re-adds the offset; do not re-add a per-frame invalidate.
}

/** From onPreDraw, returning false so the bad position is never presented; post() starves behind startup. */
internal fun repinToBottom(list: AbsListView) {
    // Remove through the observer captured at registration; a detached list hands back a floating observer.
    // No registration guard, deliberately: the listener self-removes and the repin is idempotent.
    val observer = list.viewTreeObserver
    val listener = object : ViewTreeObserver.OnPreDrawListener {
        private var frames = 0
        private fun drop() {
            runCatching {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                else list.viewTreeObserver.removeOnPreDrawListener(this)
            }
        }
        override fun onPreDraw(): Boolean {
            frames++
            // Pinned already, or the list went away: nothing to correct.
            if (!list.isAttachedToWindow || list.count == 0 || !list.canScrollVertically(1)) {
                drop()
                return true
            }
            list.setSelection(list.count - 1)
            if (frames >= 6) {
                drop()
                return true
            }
            return false
        }
    }
    observer.addOnPreDrawListener(listener)
}

/** The row being drawn; plain field, written and read on the UI thread inside one draw pass. */
internal var currentRow: View? = null

/** A bubble cannot learn its own position (null callback, identity matrix); the row hooks record it instead. */
private var rowHookInstalled = false

/** ListView overrides drawChild, hook that exact method; onDraw runs before child dispatch, so the recorded row is right. */
@Synchronized
internal fun ensureRowHook() {
    if (rowHookInstalled) return
    rowHookInstalled = true
    // One runCatching per hook: a single guard silently took three features down with one failed resolve.
    runCatching {
        XposedHelpers.findAndHookMethod(
            ListView::class.java, "drawChild",
            Canvas::class.java, View::class.java, Long::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.thisObject !== convListRef?.get()) return
                    currentRow = param.args[1] as? View
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.thisObject !== convListRef?.get()) return
                    currentRow = null
                }
            },
        )
        XposedBridge.log("[$TAG] row hook installed on ListView.drawChild")
    }.onFailure { XposedBridge.log("[$TAG] row hook (drawChild) failed: $it") }

    runCatching {
        // The path that skips drawChild; View.draw, not updateDisplayListIfDirty, whose fast path never re-runs onDraw.
        XposedHelpers.findAndHookMethod(
            View::class.java, "draw", Canvas::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    if (v.parent === lockHostRef?.get()) {
                        lockPillDrawing = true
                        lockPillW = v.width
                        lockPillH = v.height
                    }
                    if (v.parent === convListRef?.get()) currentRow = v
                    if (currentRow == null) return
                    // Each nested draw gets its own flag, so a fill is attributed to the view that drew it, not an ancestor.
                    if (msgSelDepth < msgSelDrewStack.size) {
                        msgSelDrewStack[msgSelDepth] = msgSelDrewThisRecord
                    }
                    msgSelDepth++
                    msgSelDrewThisRecord = false
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    val row = currentRow
                    if (row != null) {
                        val drew = msgSelDrewThisRecord
                        msgSelDepth--
                        msgSelDrewThisRecord =
                            if (msgSelDepth in msgSelDrewStack.indices) {
                                msgSelDrewStack[msgSelDepth]
                            } else {
                                false
                            }
                        latchMsgSelection(v, row, drew)
                    }
                    if (v.parent === convListRef?.get()) currentRow = null
                    if (v.parent === lockHostRef?.get()) lockPillDrawing = false
                }
            },
        )
        XposedBridge.log("[$TAG] row hook installed on View.draw(Canvas)")
    }.onFailure { XposedBridge.log("[$TAG] row hook (View.draw) failed: $it") }

    runCatching {
        // A separate job from View.draw, and neither hook covers the other; collapsing them regressed the second-selection slab.
        XposedHelpers.findAndHookMethod(
            View::class.java, "updateDisplayListIfDirty",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    if (v.parent !== convListRef?.get()) return
                    currentRow = v
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    val v = param.thisObject as? View ?: return
                    if (v.parent !== convListRef?.get()) return
                    currentRow = null
                }
            },
        )
        XposedBridge.log("[$TAG] row hook installed on View.updateDisplayListIfDirty")
    }.onFailure { XposedBridge.log("[$TAG] row hook (updateDisplayListIfDirty) failed: $it") }

    installMessageSelectionShape()
}

/** Reset per record and latched at its end, so it describes this record only. */
internal var msgSelDrewThisRecord = false

/** One flag per nested draw, so a fill lands on the view that drew it. Depth is bounded by ART. */
private val msgSelDrewStack = BooleanArray(64)

private var msgSelDepth = 0
