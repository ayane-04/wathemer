// Chat bubbles. Rows carry no id and host no child, so the bubble drawable is replaced and the list's own
// background draws them all; nothing recorded inside a row may depend on its screen position.
package com.wathemer.app.hooks.glass

import android.app.Application
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.ListView
import com.wathemer.app.glass.BubbleMaskFields
import com.wathemer.app.glass.GlassBubbleBackground
import com.wathemer.app.glass.GlassBubbleDrawable
import com.wathemer.app.glass.GlassBubblePane
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.GlassShader
import com.wathemer.app.glass.MaskRef
import com.wathemer.app.glass.RectList
import com.wathemer.app.hooks.BubbleShapes
import com.wathemer.app.hooks.ModulePrefs
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.settings.prefs.GlassDefaults
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Bubble corner radius. Independent of the card radius slider; bubbles want their own. */
private const val BUBBLE_RADIUS_DP = 16f

/** Merged continuations keep this much corner; the shader's own corner smoothing softens it further. */
private const val BUBBLE_FLAT_RADIUS_DP = 4f

private var bubbleMergeOn = false

/** One rasterising copy per side and variant, indexed side times two plus continuation; a shared base must not have its bounds moved. */
private val maskCopies = arrayOfNulls<Drawable>(4)

/** Follows the slider so the user's control still works; 5 heavier because a bubble transmits only wallpaper. */
private const val BUBBLE_TINT_BOOST = 5

/** The bubble tint: the slider's value plus [BUBBLE_TINT_BOOST], clamped to a legal alpha. */
private val bubbleTintColor: Int
    get() = glassTint((TINT_ALPHA + BUBBLE_TINT_BOOST).coerceIn(0, 255))

/** Fallback rim, drawn only when AGSL is unavailable; otherwise the light pass draws the edge. */
internal const val BUBBLE_RIM_ALPHA = 46

/** The panes' recipe, field for field but the fringe; fresh per drawable, a shared GlassParams is last-writer-wins on bevelThickness. */
private fun bubbleParams(density: Float) = GlassParams(density).apply {
    refractionEnabled = true
    bevelFraction = BEVEL_FRACTION
    depthRatio = DEPTH_RATIO
    maxDisplacePx = DISPLACE_DP * density
    fresnelStrength = 0.5f
    cornerRadius = BUBBLE_RADIUS_DP * density
    // No colour fringe on a bubble: its source is a pre-blurred copy, and the fringe costs two reads per pixel.
    dispersion = 0f
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
    // The switch: a mask pack shapes the glass. Colour art is handed back as itself either way.
    var shapedOn = GlassDefaults.SHAPED_BUBBLES
    runCatching {
        val p = ModulePrefs.open()
        p.reload()
        left = p.getInt(Prefs.BUBBLE_LEFT_BG, 0)
        right = p.getInt(Prefs.BUBBLE_RIGHT_BG, 0)
        bubbleMergeOn = p.getBoolean(Prefs.KEY_GLASS_BUBBLE_MERGE, GlassDefaults.BUBBLE_MERGE)
        shapedOn = p.getBoolean(Prefs.KEY_GLASS_SHAPED_BUBBLES, GlassDefaults.SHAPED_BUBBLES)
    }
    // The packs load under glass too: colour art is drawn as itself and, where the fused program runs, a mask side lends the glass its outline.
    val packs = BubbleShapes.installUnderGlass(app, cl, shapedOn && GlassShader.supported)
    XposedBridge.log(
        "[$TAG] bubble packs under glass: " +
            (if (packs) "in=${BubbleShapes.glassKind(2)} out=${BubbleShapes.glassKind(3)}" else "none") +
            " (shaped switch=$shapedOn)",
    )
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
                val continuation = collapse == 2 || collapse == 3
                // A colour art side keeps the pack's own drawable with no glass over it; BubbleColors leaves shaped sides untinted.
                if (BubbleShapes.glassKind(position) == BubbleShapes.GlassKind.ART) {
                    val art = BubbleShapes.packCopy(position, continuation)
                    if (art != null) {
                        param.result = art
                        logOnce("bubble art kept under glass (position=$position)")
                        return
                    }
                }
                val shaped = BubbleShapes.glassKind(position) == BubbleShapes.GlassKind.MASK
                var flag = if (bubbleMergeOn && (position == 2 || position == 3) && continuation) {
                    GlassBubblePane.FLAG_EXT or (if (position == 3) GlassBubblePane.FLAG_OUTGOING else 0)
                } else {
                    0
                }
                if (position == 3) flag = flag or GlassBubblePane.FLAG_SIDE_OUT
                if (continuation) flag = flag or GlassBubblePane.FLAG_CONT
                if (shaped) flag = flag or GlassBubblePane.FLAG_SHAPED
                // One tint for both sides; sender is carried by alignment, per-side colour belongs behind an explicit opt-in.
                val bp = bubbleParams(density)
                param.result = GlassBubbleDrawable(
                    params = bp,
                    // A lambda, not a baked colour: the channel resolves after WhatsApp asks for this drawable.
                    tint = { bubbleTintColor },
                    density = density,
                    rimColor = glassTint(BUBBLE_RIM_ALPHA),
                    rimWidth = density,
                    // Resolved through the row's own window: a chat with its own wallpaper must not sample another's.
                    source = { host -> wallpaperRecordOf(host) },
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
                    listDraws = { bubbleBackgroundActive() },
                    flag = flag,
                    flatRadiusPx = if (bubbleMergeOn) BUBBLE_FLAT_RADIUS_DP * density else 0f,
                    maskFor = if (shaped) ::bubbleMaskFor else null,
                )
                logOnce("bubble glass applied (position=$position${if (shaped) ", shaped" else ""})")
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
internal class BubbleMark(val rect: Rect, var rowH: Int, var flag: Int) {
    /** The pack silhouette for the current size and variant, and the stamp it was built for; a match returns it, null included. */
    var mask: MaskRef? = null
    var maskStamp = -1L
}

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

/** The conversation list's bubble background. */
internal var convBubbleBgRef: WeakReference<GlassBubbleBackground>? = null

private val bubbleRowAt = IntArray(2)

private val bubbleRectScratch = RectF()

private fun bubbleBackgroundActive(): Boolean = convBubbleBgRef?.get()?.active == true

/** The list's own background draws every bubble's glass: in-row glass freezes on scroll, and a pane beside the list misses the overscroll stretch. */
internal fun ensureBubbleBackground(list: AbsListView) {
    val existing = convBubbleBgRef?.get()
    if (existing != null && existing.serves(list) && existing.active) return
    // Whatever came before is superseded: released, or its pre-draw would keep collecting this list's rows into the old chat.
    existing?.release()
    val density = list.resources.displayMetrics.density
    val bg = GlassBubbleBackground(list)
    bg.params = bubbleParams(density)
    bg.tint = { bubbleTintColor }
    // The list is its own backdrop host; convFooterRef can be null and depends on discovery order.
    bg.backdrop = { bubbleBackdrop(list) }
    bg.placement = { bubblePlacement(list) }
    bg.dim = { bubbleDimOf(list) }
    bg.rimColor = glassTint(BUBBLE_RIM_ALPHA)
    bg.rimWidth = density
    bg.collect = { out -> collectBubbleRects(out) }
    bg.flatRadiusPx = if (bubbleMergeOn) BUBBLE_FLAT_RADIUS_DP * density else 0f
    // Rotation, split screen and folds resize the list; the bitmap survives, the mapping does not.
    bg.onGeometryChanged = { markWallpaperGeometryDirty() }
    bg.install()
    convBubbleBgRef = WeakReference(bg)
    // One forced re-record: pre-hook rows never reported, and rows before this painted their own glass.
    for (i in 0 until list.childCount) list.getChildAt(i)?.invalidate()
    XposedBridge.log("[$TAG] bubble glass installed as the conversation list's background")
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
        // A shaped bubble keeps its whole box: WhatsApp draws a pack's overhang past the row, so this must too.
        val shaped = (mark.flag and GlassBubblePane.FLAG_SHAPED) != 0
        if (shaped) {
            bubbleRectScratch.set(mark.rect)
        } else {
            GlassBubbleDrawable.clamp(mark.rect, row.height, insetX, insetY, bubbleRectScratch)
        }
        if (bubbleRectScratch.width() <= 0f || bubbleRectScratch.height() <= 0f) continue
        val ref = if (shaped) maskOf(mark) else null
        row.getLocationOnScreen(bubbleRowAt)
        // The mark is at rest; adding the live descendant translation back gives the exact screen position.
        val swipeDx = rowDisplacement(row)
        out.add(
            bubbleRowAt[0] + bubbleRectScratch.left + swipeDx,
            bubbleRowAt[1] + bubbleRectScratch.top,
            bubbleRowAt[0] + bubbleRectScratch.right + swipeDx,
            bubbleRowAt[1] + bubbleRectScratch.bottom,
            mark.flag,
            ref,
        )
    }
    // Deliberately no frame driver: report stores rest, swipeDx re-adds the offset; do not re-add a per-frame invalidate.
}

/** The mark's field for its size and variant; a recycled row changes variant at the same size, so the stamp carries both. */
private fun maskOf(mark: BubbleMark): MaskRef? {
    val w = mark.rect.width()
    val h = mark.rect.height()
    val variant = mark.flag and (GlassBubblePane.FLAG_SIDE_OUT or GlassBubblePane.FLAG_CONT)
    val stamp = (w.toLong() shl 40) or (h.toLong() shl 20) or variant.toLong()
    if (mark.maskStamp == stamp) return mark.mask
    mark.maskStamp = stamp
    mark.mask = bubbleMaskFor(mark.flag, w, h)
    return mark.mask
}

/** The pack silhouette for one bubble: side and variant pick the nine-patch, the size picks the texture. */
private fun bubbleMaskFor(flag: Int, w: Int, h: Int): MaskRef? {
    val dir = if ((flag and GlassBubblePane.FLAG_SIDE_OUT) != 0) 3 else 2
    val cont = (flag and GlassBubblePane.FLAG_CONT) != 0
    val idx = (if (dir == 3) 2 else 0) + (if (cont) 1 else 0)
    val nine = maskCopies[idx] ?: BubbleShapes.packCopy(dir, cont)?.also { maskCopies[idx] = it } ?: return null
    val field = BubbleMaskFields.fieldFor(idx, nine, w, h) ?: return null
    return MaskRef(field, w, h)
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
