// Read receipts (ticks) and clickable-link colours.
// Links hook the span base's updateDrawState via DexKit: WA's span overwrites setLinkTextColor.
package com.wathemer.app.hooks

import android.app.Application
import android.content.res.ColorStateList
import android.graphics.PorterDuff
import android.text.TextPaint
import android.widget.ImageView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.dexkit.Deobfuscator
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

private const val TAG = "WaThemer.TickLink"

// WhatsApp's stock seen-blue palette. An incoming color matching any of these exactly is "seen".
private val KNOWN_BLUES = setOf(-11289109, -13322255, -10569232, -9910027)

/** Luma heuristic: blue channel dominant + high. Catches custom-theme variants of WA's blue. */
private fun isBlueish(color: Int): Boolean {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return b > 150 && b > r * 1.3 && b > g
}

object TickAndLinkColors {

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

        val tickSeen   = xprefs.getInt(Prefs.TICK_SEEN_COLOR, 0)
        val tickUnseen = xprefs.getInt(Prefs.TICK_UNSEEN_COLOR, 0)
        val linkColor  = xprefs.getInt(Prefs.LINK_COLOR, 0)

        if ((tickSeen or tickUnseen or linkColor) == 0) {
            XposedBridge.log("$TAG: no tokens set; skipping all hooks")
            HookLog.skip("install/TickAndLinkColors", "no tokens set")
            return
        }

        // ── Tick hooks ──
        if (tickSeen != 0 || tickUnseen != 0) {
            val statusId = res.waId("status", pkg)
            val statusIndicatorId = res.waId("status_indicator", pkg)
            if (statusId == 0 && statusIndicatorId == 0) {
                XposedBridge.log("$TAG: no status/status_indicator id; tick hooks NOT installed")
                HookLog.skip("TickAndLinkColors/ticks", "no status/status_indicator id")
            } else {
                installTickHooks(statusId, statusIndicatorId, tickSeen, tickUnseen)
            }
        }

        // ── Link color ──
        if (linkColor != 0) {
            installLinkColorHook(app, linkColor, classLoader)
        }
    }

    private fun installTickHooks(statusId: Int, statusIndicatorId: Int, tickSeen: Int, tickUnseen: Int) {
        fun isTarget(id: Int): Boolean =
            (statusId != 0 && id == statusId) || (statusIndicatorId != 0 && id == statusIndicatorId)

        fun classify(incoming: Int): Int? = when {
            KNOWN_BLUES.contains(incoming) || isBlueish(incoming) ->
                if (tickSeen != 0) tickSeen else null
            else ->
                if (tickUnseen != 0) tickUnseen else null
        }

        // setImageTintList(ColorStateList)
        runCatching {
            XposedHelpers.findAndHookMethod(
                ImageView::class.java, "setImageTintList", ColorStateList::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val v = p.thisObject as? ImageView ?: return
                        if (!isTarget(v.id)) return
                        val csl = p.args[0] as? ColorStateList ?: return
                        val replacement = classify(csl.defaultColor) ?: return
                        p.args[0] = ColorStateList.valueOf(replacement)
                    }
                },
            )
        }.onFailure { XposedBridge.log("$TAG: setImageTintList hook FAILED: $it") }

        // The 1-arg overload delegates here. Do not add a 1-arg hook: no coverage, only a second classify pass.
        runCatching {
            XposedHelpers.findAndHookMethod(
                ImageView::class.java, "setColorFilter",
                Int::class.javaPrimitiveType, PorterDuff.Mode::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val v = p.thisObject as? ImageView ?: return
                        if (!isTarget(v.id)) return
                        val incoming = p.args[0] as? Int ?: return
                        val replacement = classify(incoming) ?: return
                        p.args[0] = replacement
                    }
                },
            )
        }.onFailure { XposedBridge.log("$TAG: setColorFilter(Int,Mode) hook FAILED: $it") }

        XposedBridge.log("$TAG: tick hooks armed (status=0x${statusId.toString(16)} statusIndicator=0x${statusIndicatorId.toString(16)})")
    }

    /** Hook the span base's updateDrawState, never setLinkTextColor: WA's span overwrites what that writes. */
    private fun installLinkColorHook(app: Application, linkColor: Int, classLoader: ClassLoader) {
        val method = Deobfuscator.loadLinkSpanDrawStateMethod(app, classLoader)
        if (method == null) {
            XposedBridge.log("$TAG: link color chokepoint UNRESOLVED; link colour is off")
            return
        }
        runCatching {
            XposedBridge.hookMethod(
                method,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        val tp = p.args.getOrNull(0) as? TextPaint ?: return
                        tp.color = linkColor
                    }
                },
            )
            XposedBridge.log(
                "$TAG: link color armed via ${method.declaringClass.name}.${method.name} (#%08x)".format(linkColor)
            )
        }.onFailure {
            XposedBridge.log("$TAG: ${method.declaringClass.name}.${method.name} hook FAILED: $it")
        }
        Deobfuscator.saveCache()
    }
}
