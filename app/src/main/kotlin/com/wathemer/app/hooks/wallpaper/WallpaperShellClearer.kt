// Clears the opaque containers that would otherwise sit on top of the wallpaper.
// Three layers of reach: one-shot id walk, literal-colour catchall, then the setBackground interceptor.
package com.wathemer.app.hooks.wallpaper

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.hooks.waId
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object WallpaperShellClearer {

    private const val TAG = "WaThemer.WPShells"

    /** WA dark literals: 0xff0f1014 list items, 0xff12181c dividers/spacers, 0xff0a1014 ContactInfo cards, 0xff0b1014 page roots. Add new ones here. */
    private val LITERAL_COLORS = setOf(
        0xff0f1014.toInt(),
        0xff12181c.toInt(),
        0xff0a1014.toInt(),
        0xff0b1014.toInt(),
    )

    /** Containers cleared at inject. An id can occur at several depths (two id/content), so the walk covers the whole tree. */
    private val WA_SHELL_IDS = listOf(
        // Homescreen.
        "root_view", "main_container", "call_notification_holder_vr",
        "conversation_list_view_host", "call_notification_holder",
        "content", "pager_holder", "pager",
        "conversations_coordinator_layout", "conversation_container",
        "navigation_bar_protection",
        // Conversation screen. No conversation_screen entry: the id does not resolve on any build.
        "conversation_layout", "coordinator",
        // The opaque #ff0f1014 shells covering the homescreen wallpaper.
        "community_fragment", "calls_recyclerView",
    )

    @Volatile
    private var catchallInstalled = false

    private var pagerId = 0

    private var pagerIdTried = false

    @Volatile
    private var interceptorInstalled = false

    /** One-shot clear at inject. Must not be a layout listener, that causes a redraw loop; late shells go per-id. */
    fun clearKnownShells(activity: Activity, decor: ViewGroup) {
        val pkg = activity.packageName
        val res = activity.resources

        val waIds = WA_SHELL_IDS.mapNotNull { name ->
            res.waId(name, pkg).takeIf { it != 0 }?.let { name to it }
        }
        // Framework ids need direct lookup; waId would resolve content in WhatsApp's namespace, a different id.
        val frameworkIds = listOf(
            "android:content" to android.R.id.content,
            "android:list" to android.R.id.list,
        )
        val ids = waIds + frameworkIds

        // Two counts: one id can match several views, so a single N of M count can exceed its denominator.
        var clearedViews = 0
        var matchedIds = 0
        for ((name, id) in ids) {
            var hitThisId = false
            collectAllViewsWithId(decor, id).forEach { v ->
                if (v.background != null) {
                    v.setBackgroundColor(0)
                    clearedViews++
                    hitThisId = true
                }
            }
            if (hitThisId) matchedIds++
        }
        XposedBridge.log(
            "$TAG: clearKnownShells; cleared $clearedViews view(s) across $matchedIds of ${ids.size} known IDs"
        )
    }

    /** Net under [WA_SHELL_IDS] for no-id views with a dark literal; checks ordered by cost, it fires on every attach. */
    fun installLiteralColorCatchall() {
        if (catchallInstalled) return
        catchallInstalled = true
        ViewThemeDispatcher.onView { v ->
            if (WallpaperImage.INSTANCE?.isWallpaperActive() != true) return@onView false
            val bg = v.background as? ColorDrawable ?: return@onView false
            if (bg.color in LITERAL_COLORS) {
                v.setBackgroundColor(0)
                return@onView false
            }
            // A pager page root is a shell whatever colour it wears, and the Updates page's root carries no id.
            if (!pagerIdTried) {
                pagerIdTried = true
                pagerId = v.resources.waId("pager", v.context.packageName)
            }
            if (pagerId != 0 && (bg.color ushr 24) == 0xFF && (v.parent as? View)?.id == pagerId) {
                v.setBackgroundColor(0)
                XposedBridge.log("$TAG: pager page root cleared (#%08X)".format(bg.color))
            }
            false
        }
        XposedBridge.log("$TAG: literal-color catchall installed (${LITERAL_COLORS.size} colors)")
    }

    /** Intercepts bg assignment itself so timing stops mattering. Must stay narrow, flat opaque shell colours only, or avatar circles break. */
    fun installBackgroundInterceptor(userBgColor: Int) {
        if (interceptorInstalled) return
        interceptorInstalled = true
        val shellColors = LITERAL_COLORS + userBgColor

        fun isOpaqueShellColor(c: Int): Boolean =
            ((c ushr 24) and 0xFF) == 0xFF && c in shellColors

        // Wallpaper active, and not one of our own views.
        fun gate(view: View?): Boolean {
            if (WallpaperImage.INSTANCE?.isWallpaperActive() != true) return false
            val tag = view?.tag
            if (tag is String && tag.startsWith(WallpaperImage.TAG_PREFIX)) return false
            return true
        }

        fun shouldNeutralizeDrawable(view: View?, d: Drawable?): Boolean {
            if (!gate(view)) return false
            return when (d) {
                is ColorDrawable -> isOpaqueShellColor(d.color)
                is GradientDrawable ->
                    runCatching { d.color?.defaultColor }.getOrNull()?.let { isOpaqueShellColor(it) } ?: false
                else -> false
            }
        }

        runCatching {
            XposedHelpers.findAndHookMethod(
                View::class.java, "setBackground", Drawable::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val v = p.thisObject as? View
                        if (shouldNeutralizeDrawable(v, p.args[0] as? Drawable)) {
                            p.args[0] = ColorDrawable(0)
                        }
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                View::class.java, "setBackgroundColor", Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(p: MethodHookParam) {
                        val v = p.thisObject as? View
                        if (!gate(v)) return
                        val color = p.args[0] as Int
                        if (isOpaqueShellColor(color)) {
                            p.args[0] = 0
                        }
                    }
                },
            )
            XposedBridge.log(
                "$TAG: bg interceptor installed (shellColors=${shellColors.size}, userBg=#%08X)".format(userBgColor),
            )
        }.onFailure { XposedBridge.log("$TAG: bg interceptor install failed: ${it.message}") }
    }

    /** Every view with the id; WA reuses ids at several depths and findViewById returns only the first. */
    private fun collectAllViewsWithId(root: View, id: Int): List<View> {
        val result = mutableListOf<View>()
        fun walk(v: View) {
            if (v.id == id) result.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        walk(root)
        return result
    }
}
