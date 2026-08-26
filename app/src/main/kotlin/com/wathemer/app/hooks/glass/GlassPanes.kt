// Pane lifetime. A pane that is not a child of the view it decorates outlives it, so it is bound to
// an anchor and this is the ONLY writer of a bound pane's visibility; ask paneShouldShow instead.
package com.wathemer.app.hooks.glass

import android.view.View
import com.wathemer.app.glass.GlassView
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference

// ── Pane lifetime: a pane must not outlive the thing it decorates ──────────────────────
// Any pane that is not a child of the view it decorates must be bindPane'd to it, or it orphans on fragment swaps.
// One writer only: nothing but syncPaneVisibility may set a bound pane's visibility; ask paneShouldShow instead.
internal class PaneBinding(
    val pane: WeakReference<View>,
    val anchor: WeakReference<View>,
    val what: String,
    /** Runs once as this pane is hidden because its anchor left; nothing else in the process reports that moment. */
    val onHidden: (() -> Unit)? = null,
)

internal val paneBindings = mutableListOf<PaneBinding>()

/** isShown, not visibility, deliberately: visibility still reads VISIBLE on a detached view. */
internal fun paneShouldShow(anchor: View?): Boolean =
    anchor != null && anchor.isShown && anchor.width > 0 && anchor.height > 0

/** Tie [pane]'s visibility to [anchor]'s. See the contract above. */
internal fun bindPane(pane: View, anchor: View, what: String, onHidden: (() -> Unit)? = null) {
    paneBindings.removeAll { val p = it.pane.get(); p == null || p === pane }
    paneBindings.add(
        PaneBinding(
            WeakReference(pane), WeakReference(anchor),
            what, onHidden,
        )
    )
    syncPaneVisibility()
}

/** Hide bound panes whose anchor left, restore returners, in the layout phase so a stale pane is never drawn. */
internal fun syncPaneVisibility() {
    val bindings = paneBindings.iterator()
    while (bindings.hasNext()) {
        val b = bindings.next()
        val pane = b.pane.get()
        if (pane == null || pane.parent == null) { bindings.remove(); continue }
        val anchor = b.anchor.get()
        // A collected anchor must be pruned, not obeyed: left in place it re-hides its pane on every sweep, for ever.
        if (anchor == null) {
            if (pane.visibility != View.GONE) {
                pane.visibility = View.GONE
                XposedBridge.log("[$TAG] pane '${b.what}' unbound; anchor garbage-collected")
            }
            bindings.remove()
            continue
        }
        val want = if (paneShouldShow(anchor)) View.VISIBLE else View.GONE
        if (pane.visibility == want) continue
        // startHideFade left the material at 0 deliberately; a restore assembles it instead of fading a decal.
        if (want == View.VISIBLE) {
            val g = pane as? GlassView
            if (g != null) {
                if (g.materialized < 1f || pane.alpha < 1f) {
                    pane.animate().cancel()
                    pane.alpha = 1f
                    g.materializeIn(SEARCH_FADE_MS)
                }
            } else if (pane.alpha < 1f) {
                pane.animate().cancel()
                pane.alpha = 0f
                pane.animate().alpha(1f).setDuration(SEARCH_FADE_MS)
                    .setInterpolator(searchInterp).start()
            }
        }
        pane.visibility = want
        XposedBridge.log(
            "[$TAG] pane '${b.what}' ${if (want == View.VISIBLE) "restored" else "hidden"}" +
                "; anchor ${anchorState(anchor)}"
        )
        // After the write, and guarded: a throwing callback must never leave a pane stranded visible.
        if (want == View.GONE) runCatching { b.onHidden?.invoke() }
    }
}

/** Why [syncPaneVisibility] decided what it decided, for the log line. */
private fun anchorState(a: View?): String = when {
    a == null -> "garbage-collected"
    a.parent == null -> "detached from its parent"
    !a.isShown -> "GONE or under a GONE ancestor"
    a.width <= 0 || a.height <= 0 -> "laid out at zero size"
    else -> "on screen"
}
