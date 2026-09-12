// Bubble theming. Layer 1 tints WA's bubble drawables, layer 2 kills the WDS outline foregrounds
// that leak against translucent colours, layer 3 restores the rounded clip layer 2 removed.
package com.wathemer.app.hooks

import android.app.Application
import android.graphics.Outline
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.TextView
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.hooks.dispatch.ForegroundKillDispatcher
import com.wathemer.app.hooks.dispatch.TextColorDispatcher
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method

private const val TAG = "WaThemer.BubbleColors"
private const val MAX_PARENT_WALK = 15

/** Prefix every diagnostic line with TAG; routed to the LSPosed module log. */
private fun dlog(m: String) { XposedBridge.log("$TAG: $m") }

object BubbleColors {

    private val xprefs: ModulePrefs.WtPrefs by lazy { ModulePrefs.open() }

    /** Wire bubble theming into the WA process. Called from ModernEntry at app create. */
    fun install(app: Application, classLoader: ClassLoader) {
        xprefs.reload()
        val leftBg = xprefs.getInt(Prefs.BUBBLE_LEFT_BG, 0)
        val rightBg = xprefs.getInt(Prefs.BUBBLE_RIGHT_BG, 0)
        val leftText = xprefs.getInt(Prefs.BUBBLE_LEFT_TEXT, 0)
        val rightText = xprefs.getInt(Prefs.BUBBLE_RIGHT_TEXT, 0)
        val leftDate = xprefs.getInt(Prefs.BUBBLE_LEFT_DATE, 0)
        val rightDate = xprefs.getInt(Prefs.BUBBLE_RIGHT_DATE, 0)

        // When a side has a custom shape, BubbleShapes owns that side's appearance and tinting.
        val shapeIn = xprefs.getInt(Prefs.BUBBLE_STYLE_INCOMING, 0)
        val shapeOut = xprefs.getInt(Prefs.BUBBLE_STYLE_OUTGOING, 0)

        val bgActive = leftBg != 0 || rightBg != 0
        val textActive = leftText != 0 || rightText != 0 || leftDate != 0 || rightDate != 0

        if (!bgActive && !textActive) {
            XposedBridge.log("$TAG: no bubble colours set; skipping all hooks")
            HookLog.skip("install/BubbleColors", "no bubble colours set")
            return
        }

        // ── Bg tint hooks gate: DexKit + the provider's 3 Drawable methods + edge-leak fixes ───
        if (bgActive) {
            runCatching {
                if (Deobfuscator.ensureBridge(app)) {
                    // Only the main bubble factory defers to shapes; date pill and border keep their tint unconditionally.
                    hookBubbleMethod(
                        Deobfuscator.loadBubbleDrawableMethod(classLoader), leftBg, rightBg, 0,
                        shapeIncoming = shapeIn, shapeOutgoing = shapeOut,
                    )
                    hookBubbleMethod(Deobfuscator.loadBalloonDateDrawableMethod(classLoader), leftBg, rightBg, 0)
                    hookBubbleMethod(Deobfuscator.loadBalloonBorderDrawableMethod(classLoader), leftBg, rightBg, 1)
                    Deobfuscator.saveCache()
                } else {
                    dlog("DexKit init failed; bubble tint will not work")
                }
            }.onFailure { dlog("tint hook setup FAILED: ${it.stackTraceToString()}") }

            runCatching { installForegroundKill(app) }
                .onFailure { XposedBridge.log("$TAG: foreground-kill FAILED: ${it.stackTraceToString()}") }

            runCatching { installRoundedOutlines(app) }
                .onFailure { XposedBridge.log("$TAG: outline restore FAILED: ${it.stackTraceToString()}") }
        }

        // ── Text + timestamp hooks gate independently ──
        if (textActive) {
            runCatching {
                hookBubbleTextColors(app, leftText, rightText, leftDate, rightDate)
            }.onFailure { XposedBridge.log("$TAG: text/date hooks FAILED: ${it.stackTraceToString()}") }
        }
    }

    // ── Layer 1: tint the bubble Drawable methods ────────────────────────────────────────────

    /** Per-side bg tint. mutate() before the filter is mandatory: shared ConstantState bleeds tint across rows. */
    private fun hookBubbleMethod(
        method: Method?,
        leftColor: Int,
        rightColor: Int,
        directionArgIndex: Int,
        shapeIncoming: Int = 0,
        shapeOutgoing: Int = 0,
    ) {
        if (method == null) {
            dlog("skip; method null (dirArg=$directionArgIndex)")
            return
        }
        XposedBridge.hookMethod(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val drawable = param.result as? Drawable ?: return
                val position = param.args.getOrNull(directionArgIndex) as? Int ?: return
                // Do not shorten to position == 3: that folds CENTERED into INCOMING. BubbleSide is the only decoder.
                val side = BubbleSide.of(position)
                // When a shape owns this side, do not tint: SRC_IN flattens artwork styles BubbleShapes leaves untinted on purpose.
                val shape = when (side) {
                    BubbleSide.OUTGOING -> shapeOutgoing
                    BubbleSide.INCOMING -> shapeIncoming
                    BubbleSide.CENTERED -> 0        // never shaped, so never deferred for
                }
                if (shape != 0) return
                // CENTERED gets no bubble colour: system notices are covered by the global background token.
                val color = when (side) {
                    BubbleSide.OUTGOING -> rightColor
                    BubbleSide.INCOMING -> leftColor
                    BubbleSide.CENTERED -> 0
                }
                if (color == 0) return
                drawable.mutate()
                drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
            }
        })
        dlog("hooked ${method.toGenericString()} (dirArg=$directionArgIndex" +
            (if (shapeIncoming != 0 || shapeOutgoing != 0) ", deferring to shapes in=$shapeIncoming out=$shapeOutgoing" else "") + ")")
    }

    // ── Layer 2: kill WDS rounded-outline foregrounds on bubble surfaces ─────────────────────

    private fun installForegroundKill(app: Application) {
        val pkg = app.packageName
        val res = app.resources
        val candidateNames = arrayOf(
            // Confirmed leak surfaces (fg=NinePatchDrawable in live dumps)
            "quoted_message_frame",
            "location_bubble_frame",
            "link_preview_frame",
            "thumb_0", "thumb_1", "thumb_2", "thumb_3",
            // Present in dumps + matches the same WA bubble engine pattern
            "media_container_wrapper",
            "media_container",
            "document_frame",
            "preview",
            "picture_frame",
            "view_once_media_container_small",
            "view_once_media_container_large",
            "conversation_row_audio_player_view",
            "live_location_info_holder",
            // The album extras, also leak-prone.
            "media_grid",
            "conversation_row_image_background_shadow",
            "conversation_row_image_foreground_shadow",
            "conversation_row_video_background_shadow",
            "conversation_row_video_foreground_shadow",
        )
        val resolved = candidateNames
            .map { name -> name to res.waId(name, pkg) }
            .filter { it.second != 0 }
        if (resolved.isEmpty()) {
            XposedBridge.log("$TAG: foreground-kill; no candidate ids resolved; skipping")
            HookLog.skip("BubbleColors/foregroundKill", "no candidate ids resolved")
            return
        }
        val idSet = resolved.map { it.second }.toHashSet()
        XposedBridge.log("$TAG: foreground-kill armed (${resolved.size} ids)")

        // Shared dispatcher, not our own hook: quoted_message_frame is claimed by two other features too.
        for (id in idSet) ForegroundKillDispatcher.kill(id)

        // XML-set foregrounds bypass the method hook; the attach clear catches those, the post catches the adapter race.
        for ((_, id) in resolved) {
            ViewThemeDispatcher.onId(id) { v ->
                if (v.foreground != null) v.foreground = null
                v.post {
                    if (v.foreground != null) v.foreground = null
                }
            }
        }
    }

    // ── Layer 3: restore rounded clip where Layer 2 removed the mask ─────────────────────────

    private fun installRoundedOutlines(app: Application) {
        applyRoundedOutline(app, "preview", topOnly = true)              // PDF preview = top corners only
        applyRoundedOutline(app, "document_frame", topOnly = false)       // File/PDF bubble shape
        applyRoundedOutline(app, "media_container_wrapper", topOnly = false)
        applyRoundedOutline(app, "media_container", topOnly = false)
        applyRoundedOutline(app, "thumb_0", topOnly = false)
        applyRoundedOutline(app, "thumb_1", topOnly = false)
        applyRoundedOutline(app, "thumb_2", topOnly = false)
        applyRoundedOutline(app, "thumb_3", topOnly = false)
        // Maps: clipping the location_bubble_frame overlay does not round the visible map, so clip map_holder and map_frame.
        applyRoundedOutline(app, "map_frame", topOnly = false)               // static-location map outer (FrameLayout); skipped if WDSRoundedFrameLayout (live)
        applyRoundedOutline(app, "map_holder", topOnly = false)              // map widget itself (WaMapView)
        applyRoundedOutline(app, "link_preview_frame", topOnly = false)      // link preview card (WebPagePreviewView, fg=NinePatch confirmed)
        applyRoundedOutline(app, "live_location_info_holder", topOnly = false) // live location info row (LinearLayout/FrameLayout per layout XML)
    }

    // ── Text + timestamp colours, per side. ──────────────────────────────────────────────────

    /** Text and date colours via TextColorDispatcher plus attach re-apply; side detection in [isOutgoingBubble]. */
    private fun hookBubbleTextColors(
        app: Application,
        leftText: Int, rightText: Int,
        leftDate: Int, rightDate: Int,
    ) {
        val pkg = app.packageName
        val res = app.resources
        val messageTextId = res.waId("message_text", pkg)
        val dateId = res.waId("date", pkg)
        val mainLayoutId = res.waId("main_layout", pkg)
        // The delivery tick: present only in outgoing rows, the side signal this file uses.
        statusTickId = res.waId("status", pkg)

        if (messageTextId == 0 && dateId == 0) {
            XposedBridge.log("$TAG: message_text + date ids both missing; text/date hooks skipped")
            HookLog.skip("BubbleColors/text", "message_text and date ids both missing")
            return
        }
        XposedBridge.log(
            "$TAG: text/date hooks armed (msg=$messageTextId date=$dateId main=$mainLayoutId " +
                "status=$statusTickId)"
        )
        if (statusTickId == 0) {
            XposedBridge.log(
                "$TAG: 'status' UNRESOLVED; side cannot be determined, so per-side text/date " +
                    "colours will ABSTAIN rather than guess. Both sides keep WhatsApp's colour."
            )
        }

        TextColorDispatcher.addHandler { tv ->
            resolveBubbleTextColor(
                tv, messageTextId, dateId, mainLayoutId,
                leftText, rightText, leftDate, rightDate,
            )
        }
        val paint = { tv: TextView ->
            val c = resolveBubbleTextColor(tv, messageTextId, dateId, mainLayoutId,
                leftText, rightText, leftDate, rightDate)
            if (c != 0) tv.setTextColor(c)
        }
        for (id in listOf(messageTextId, dateId)) {
            if (id == 0) continue
            ViewThemeDispatcher.onId(id) { v ->
                val tv = v as? TextView ?: return@onId
                paint(tv)                       // idempotent paint, every attach
                armDateSideReassert(tv, paint)  // tag-guarded listener only
            }
        }
    }

    /** Returns 0 to abstain (TextColorDispatcher tries the next handler). */
    private fun resolveBubbleTextColor(
        tv: TextView,
        messageTextId: Int, dateId: Int, mainLayoutId: Int,
        leftText: Int, rightText: Int, leftDate: Int, rightDate: Int,
    ): Int {
        val id = tv.id
        if (id == View.NO_ID) return 0
        val isText = messageTextId != 0 && id == messageTextId
        val isDate = dateId != 0 && id == dateId
        if (!isText && !isDate) return 0
        // null = side unknown. Abstain rather than guess: a wrong side is a visibly wrong colour.
        val outgoing = isOutgoingBubble(tv, mainLayoutId) ?: return 0
        return when {
            isText && outgoing -> rightText
            isText             -> leftText
            isDate && outgoing -> rightDate
            else               -> leftDate
        }
    }

    /** `app:id/status`, the delivery tick. Resolved in [hookBubbleTextColors]. */
    private var statusTickId = 0

    /** Side via the status tick, inflated only in outgoing rows. Do not use gravity: it is child arrangement, not side. */
    private fun isOutgoingBubble(view: View, mainLayoutId: Int): Boolean? {
        if (mainLayoutId == 0 || statusTickId == 0) return null
        var node: View? = view.parent as? View
        var i = 0
        while (node != null && i < MAX_PARENT_WALK) {
            if (node.id == mainLayoutId) {
                return containsId(node, statusTickId, 0)
            }
            node = node.parent as? View
            i++
        }
        return null
    }

    private const val DATE_SIDE_LISTENER_TAG = -1167196176

    /** Re-decide side after layout, when the recycled row is settled. Tag-guard the listener, never the paint. */
    private fun armDateSideReassert(tv: TextView, paint: (TextView) -> Unit) {
        if (tv.getTag(DATE_SIDE_LISTENER_TAG) != null) return
        tv.setTag(DATE_SIDE_LISTENER_TAG, true)
        tv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            (v as? TextView)?.let { runCatching { paint(it) } }
        }
    }

    /** Depth-bounded search for [id] anywhere under [v]. */
    private fun containsId(v: View, id: Int, depth: Int): Boolean {
        if (v.id == id) return true
        if (depth >= 6 || v !is ViewGroup) return false
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i) ?: continue
            if (containsId(c, id, depth + 1)) return true
        }
        return false
    }

    private fun applyRoundedOutline(app: Application, idName: String, topOnly: Boolean) {
        val id = app.resources.waId(idName, app.packageName)
        if (id == 0) return
        val density = app.resources.displayMetrics.density
        val cornerRadius = 12f * density   // ≈ WA's bubble corner radius
        ViewThemeDispatcher.onId(id) { v ->
            // WDSRoundedFrameLayout self-clips; adding our outline would double-clip.
            if (v.javaClass.name.endsWith(".WDSRoundedFrameLayout")) return@onId
            // clipChildren=false is WhatsApp saying content overflows this box; a sticker draws past it on purpose.
            if ((v as? ViewGroup)?.clipChildren == false) return@onId
            v.clipToOutline = true
            v.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val w = view.width; val h = view.height
                    if (w <= 0 || h <= 0) return
                    // Clip at the padding inset: map_frame pads its map, and clipping at the outer bounds leaves it rectangular.
                    val l = view.paddingLeft
                    val t = view.paddingTop
                    val r = w - view.paddingRight
                    val b = h - view.paddingBottom
                    if (r - l <= 0 || b - t <= 0) return
                    if (topOnly) {
                        // A path outline does not clip (MODE_PATH cannot), and preview looks right anyway.
                        // Do not "fix" this with setRoundRect: it rounds all four corners and starts clipping.
                        val cr = cornerRadius
                        val path = Path().apply {
                            addRoundRect(
                                RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat()),
                                floatArrayOf(cr, cr, cr, cr, 0f, 0f, 0f, 0f),
                                Path.Direction.CW,
                            )
                        }
                        outline.setPath(path)
                    } else {
                        outline.setRoundRect(l, t, r, b, cornerRadius)
                    }
                }
            }
            // Outlines only update on layout; invalidate in case the view is already laid out.
            v.invalidateOutline()
        }
    }
}
