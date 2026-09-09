package com.wathemer.app.hooks

import android.content.res.AssetManager
import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.TypedValue
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.util.DrawableColors
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Four framework hooks that intercept every colour WhatsApp resolves, plus a relaunch refresh. Framework classes only, so no DexKit. */
object ThemeHook {

    private const val TAG = "WaThemer"

    private val xprefs: ModulePrefs.WtPrefs by lazy {
        ModulePrefs.open().also {
            XposedBridge.log("[$TAG] remote preferences opened: ${it.all.size} keys")
        }
    }

    fun install(classLoader: ClassLoader) {
        try {
            reloadColors()
            // No tokens, install nothing: unthemed WhatsApp stays bit-for-bit stock, and per-element features are independent anyway.
            if (ColorMap.isEmpty()) {
                XposedBridge.log("[$TAG] no global colour tokens set; global substitution NOT installed (WhatsApp stays stock)")
                HookLog.skip("install/ThemeHook", "no global colour tokens set")
                return
            }
            installFrameworkHooks(classLoader)
            installRefreshHook(classLoader)
            XposedBridge.log("[$TAG] Theme hooks installed. ColorMap has ${ColorMap.size()} mappings.")
        } catch (t: Throwable) {
            XposedBridge.log("[$TAG] Theme hook install FAILED: $t")
            XposedBridge.log(t)
            // The catch keeps the stack, so the ledger has to be told here or the entry arms as if nothing happened.
            HookLog.fail("install/ThemeHook", t)
        }
    }

    /** Rebuilds the map from prefs. Defaults must stay 0 = unset: a non-zero default themes the whole app. */
    private fun reloadColors() {
        xprefs.reload()
        val primary = xprefs.getInt(Prefs.KEY_PRIMARY, 0)
        val background = xprefs.getInt(Prefs.KEY_BACKGROUND, 0)
        val text = xprefs.getInt(Prefs.KEY_TEXT, 0)
        XposedBridge.log(
            "[$TAG] reloadColors: remote store ${xprefs.all.size} keys " +
            "-> primary=#%08x bg=#%08x text=#%08x".format(primary, background, text)
        )
        ColorMap.rebuild(primary, background, text)
    }

    /** hookAllMethods fails silently on no match; without the log a whole layer goes off the air unnoticed. */
    private fun hookAllOrWarn(cls: Class<*>, method: String, cb: XC_MethodHook) {
        val hooks = XposedBridge.hookAllMethods(cls, method, cb)
        if (hooks.isEmpty()) {
            XposedBridge.log(
                "[$TAG] hook UNRESOLVED: no method '$method' on ${cls.name}; that part of the " +
                    "global colour substitution is OFF."
            )
        }
    }

    private fun installFrameworkHooks(classLoader: ClassLoader) {

        // Hook 1: getResourceValue. Must stay narrowed to the four colour types or plain @integer resources get rewritten.
        hookAllOrWarn(
            AssetManager::class.java,
            "getResourceValue",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val tv = param.args.firstOrNull { it is TypedValue } as? TypedValue ?: return
                    if (tv.data == 0) return
                    if (tv.type !in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
                        return
                    }
                    tv.data = ColorMap.substitute(tv.data)
                }
            }
        )

        // Hook 2: loadDrawable catches every Drawable inflation; DrawableColors.replace rewrites the colours.
        val resImpl = XposedHelpers.findClass("android.content.res.ResourcesImpl", classLoader)
        hookAllOrWarn(
            resImpl, "loadDrawable",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val d = param.result as? Drawable ?: return
                    DrawableColors.replace(d)
                }
            }
        )

        // Hook 3: ResourcesImpl.loadColorStateList walks the state-list table.
        hookAllOrWarn(
            resImpl, "loadColorStateList",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val csl = param.result as? ColorStateList ?: return
                    val colors = XposedHelpers.getObjectField(csl, "mColors") as? IntArray ?: return
                    var changed = false
                    for (i in colors.indices) {
                        val sub = ColorMap.substitute(colors[i])
                        if (sub != colors[i]) { colors[i] = sub; changed = true }
                    }
                    // onColorsChanged() recomputes the cached mDefaultColor/mIsOpaque; without it getDefaultColor() keeps the old colour.
                    if (changed) {
                        runCatching { XposedHelpers.callMethod(csl, "onColorsChanged") }
                    }
                }
            }
        )

        // Hook 4: Paint.setColor, deliberately unnarrowed. Do not drop #ffffff from ColorSeeds.TEXT and
        // do not gate on alpha: opaque white is glyph runs, most of what the text token does.
        XposedHelpers.findAndHookMethod(
            Paint::class.java, "setColor",
            Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val before = param.args[0] as Int
                    val after = ColorMap.substitute(before)
                    // Write only on change: an unconditional write autoboxes a fresh Integer per setColor call.
                    if (after != before) param.args[0] = after
                }
            }
        )
    }

    /** Reloads colours on activity relaunch; foregrounding does not fire it, so settings changes need a force-stop. */
    private fun installRefreshHook(classLoader: ClassLoader) {
        val activityThread = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
        hookAllOrWarn(
            activityThread, "handleRelaunchActivity",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    reloadColors()
                }
            }
        )
    }
}
