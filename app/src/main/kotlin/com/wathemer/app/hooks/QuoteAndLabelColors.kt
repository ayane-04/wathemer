// Quote/reply theming plus message labels (forwarded label, media caption).
// Quote bg also kills quoted_message_frame's WDS foreground, which shows corner edges over a replaced bg.
package com.wathemer.app.hooks

import android.app.Application
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.widget.TextView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.dispatch.ForegroundKillDispatcher
import com.wathemer.app.hooks.dispatch.TextColorDispatcher
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

private const val TAG = "WaThemer.QuoteLabel"

object QuoteAndLabelColors {

    private val xprefs: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE).apply {
            makeWorldReadable()
            reload()
        }
    }

    fun install(app: Application, classLoader: ClassLoader) {
        xprefs.reload()
        val pkg = app.packageName
        val res = app.resources

        val quoteBar       = xprefs.getInt(Prefs.QUOTE_BAR_COLOR, 0)
        val quoteBg        = xprefs.getInt(Prefs.QUOTE_BG_COLOR, 0)
        val quoteText      = xprefs.getInt(Prefs.QUOTE_TEXT_COLOR, 0)
        val forwardedLabel = xprefs.getInt(Prefs.FORWARDED_LABEL_COLOR, 0)
        val mediaCaption   = xprefs.getInt(Prefs.MEDIA_CAPTION_COLOR, 0)

        if ((quoteBar or quoteBg or quoteText or forwardedLabel or mediaCaption) == 0) {
            XposedBridge.log("$TAG: no tokens set; skipping all hooks")
            return
        }

        // ─── Quote bar (small vertical accent stripe at left edge of a quoted reply) ───
        if (quoteBar != 0) {
            val id = res.waId("quoted_color", pkg)
            if (id != 0) {
                val filter = PorterDuffColorFilter(quoteBar, PorterDuff.Mode.SRC_IN)
                // mutate() first: a shared ConstantState tints everything else drawn from it.
                ViewThemeDispatcher.onId(id) { v -> v.background?.mutate()?.colorFilter = filter }
                XposedBridge.log("$TAG: quote bar armed (id=0x${id.toString(16)})")
            }
        }

        // ─── Frame foreground kill (suppresses WDS outline creating corner edges) ───
        if (quoteBg != 0) {
            installFrameForegroundKill(pkg, res)
        }

        // ─── Quote bg color (replace link_preview_background drawables) ───
        if (quoteBg != 0) {
            installLinkPreviewReplacement(pkg, res, quoteBg, classLoader)
        }

        if (quoteText != 0) {
            for (name in listOf("quoted_text", "quoted_sub_text")) {
                val id = res.waId(name, pkg)
                if (id != 0) {
                    TextColorDispatcher.mapColor(id, quoteText)
                    ViewThemeDispatcher.onId(id) { v -> (v as? TextView)?.setTextColor(quoteText) }
                }
            }
        }

        if (forwardedLabel != 0) {
            val id = res.waId("conversation_row_top_text_attribute", pkg)
            if (id != 0) {
                TextColorDispatcher.mapColor(id, forwardedLabel)
                ViewThemeDispatcher.onId(id) { v -> (v as? TextView)?.setTextColor(forwardedLabel) }
            }
        }

        if (mediaCaption != 0) {
            val id = res.waId("caption", pkg)
            if (id != 0) {
                TextColorDispatcher.mapColor(id, mediaCaption)
                ViewThemeDispatcher.onId(id) { v -> (v as? TextView)?.setTextColor(mediaCaption) }
            }
        }
    }

    private fun installFrameForegroundKill(pkg: String, res: android.content.res.Resources) {
        val frameId = res.waId("quoted_message_frame", pkg)
        if (frameId == 0) return
        // Shared dispatcher: GlassHook and BubbleColors also claim quoted_message_frame.
        ForegroundKillDispatcher.kill(frameId)
    }

    /** Replace the 3 link_preview_background variants with a GradientDrawable, keeping WA's corner radii. */
    private fun installLinkPreviewReplacement(
        pkg: String,
        res: android.content.res.Resources,
        quoteBg: Int,
        classLoader: ClassLoader,
    ) {
        val targetIds = listOf(
            "link_preview_background",
            "link_preview_background_rounded",
            "link_preview_background_wds",
        ).mapNotNull { name ->
            val id = res.getIdentifier(name, "drawable", pkg)
            if (id != 0) name to id else null
        }
        if (targetIds.isEmpty()) {
            // Drawable lookups bypass waId's miss log, so log the rename case here or the feature dies silently.
            XposedBridge.log(
                "$TAG: link preview UNRESOLVED; none of link_preview_background / _rounded / _wds " +
                    "is a drawable in $pkg. Quote/link-preview theming is OFF."
            )
            return
        }
        val idSet = targetIds.map { it.second }.toHashSet()
        val density = res.displayMetrics.density
        val fallbackRadius = 12f * density

        runCatching {
            val cls = XposedHelpers.findClass("android.content.res.ResourcesImpl", classLoader)
            XposedBridge.hookAllMethods(cls, "loadDrawable", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    var matched = false
                    for (a in p.args) {
                        if (a is Int && idSet.contains(a)) { matched = true; break }
                    }
                    if (!matched) return
                    val radii = extractCornerRadii(p.result as? Drawable, fallbackRadius)
                    p.result = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadii = radii
                        setColor(quoteBg)
                    }
                }
            })
        }.onFailure {
            XposedBridge.log("$TAG: link_preview loadDrawable hook FAILED: ${it.stackTraceToString()}")
        }
    }

    private fun extractCornerRadii(d: Drawable?, fallbackRadius: Float): FloatArray {
        val defaults = FloatArray(8) { fallbackRadius }
        if (d !is RippleDrawable || d.numberOfLayers < 2) return defaults

        val inner = d.getDrawable(1) ?: return defaults
        val firstGd: GradientDrawable = when (inner) {
            is GradientDrawable -> inner
            is StateListDrawable -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    (0 until inner.stateCount)
                        .asSequence()
                        .mapNotNull { runCatching { inner.getStateDrawable(it) }.getOrNull() }
                        .filterIsInstance<GradientDrawable>()
                        .firstOrNull()
                } else {
                    inner.current as? GradientDrawable
                }
            }
            else -> null
        } ?: return defaults

        val radii = firstGd.cornerRadii
        if (radii != null && radii.size == 8) return radii.copyOf()
        val uniform = runCatching { firstGd.cornerRadius }.getOrDefault(fallbackRadius)
        return FloatArray(8) { uniform }
    }
}
