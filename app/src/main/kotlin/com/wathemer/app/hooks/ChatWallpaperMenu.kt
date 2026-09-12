// Adds "Chat wallpaper" to the chat's overflow menu and hands the open chat to the settings app.
// Anchored on the host's own menu overrides, which virtual dispatch reaches; never on a framework method.
package com.wathemer.app.hooks

import android.app.Activity
import android.content.Intent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.wallpaper.ChatWallpapers
import com.wathemer.app.settings.prefs.ChatWallpaperLibrary
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object ChatWallpaperMenu {

    private const val TAG = "WaThemer.ChatMenu"
    private const val SETTINGS_ACTIVITY = "com.wathemer.app.settings.MainActivity"

    /** Clear of WhatsApp's own small item ids and of the resource id range. */
    private const val ITEM_ID = 0x7E5A0001

    private val xprefs: ModulePrefs.WtPrefs by lazy { ModulePrefs.open() }

    /** Registered unconditionally; the wallpaper switch is read when the menu builds, so a toggle needs no restart. */
    fun install(classLoader: ClassLoader) {
        val cls = WaIds.clazz(classLoader, ChatWallpapers.CONVERSATION, "chat wallpaper menu") ?: return
        val cb = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                val menu = param.args.getOrNull(0) as? Menu ?: return
                // Never touch the result: WhatsApp's return decides whether the menu shows at all.
                runCatching { ensureItem(activity, menu) }.onFailure { HookLog.fail("chat/menuItem", it) }
            }
        }
        XposedHelpers.findAndHookMethod(cls, "onCreateOptionsMenu", Menu::class.java, cb)
        XposedHelpers.findAndHookMethod(cls, "onPrepareOptionsMenu", Menu::class.java, cb)
        HookLog.arm("chat/menuItem")
    }

    private fun ensureItem(activity: Activity, menu: Menu) {
        if (!xprefs.getBoolean(Prefs.KEY_WALLPAPER_ENABLED, false)) {
            menu.removeItem(ITEM_ID)
            return
        }
        // Prepare runs on every open and add appends, so an unguarded add grows one row per open.
        if (menu.findItem(ITEM_ID) != null) return
        val item = menu.add(Menu.NONE, ITEM_ID, Menu.NONE, "Chat wallpaper")
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        // The listener is consulted before WhatsApp's own selection handler, which never sees this item.
        item.setOnMenuItemClickListener {
            runCatching { handOff(activity) }.onFailure { HookLog.fail("chat/wallpaperHandoff", it) }
            true
        }
        HookLog.hit("chat/menuItem")
    }

    /** The jid comes from the chat's own Intent, the same place the wallpaper hook reads it, so both sides agree. */
    private fun handOff(activity: Activity) {
        val jid = activity.intent?.getStringExtra("jid")
        if (jid.isNullOrBlank()) {
            XposedBridge.log("$TAG: the Conversation intent carries no jid; nothing to hand off")
            HookLog.skip("chat/wallpaperHandoff", "no jid on the intent")
            return
        }
        val nameId = activity.resources.waId("conversation_contact_name", activity.packageName)
        val name = if (nameId != 0) (activity.findViewById<View>(nameId) as? TextView)?.text?.toString() else null
        val intent = Intent()
            .setClassName(BuildConfig.APPLICATION_ID, SETTINGS_ACTIVITY)
            .putExtra(ChatWallpaperLibrary.EXTRA_JID, jid)
            .putExtra(ChatWallpaperLibrary.EXTRA_NAME, name.orEmpty())
            .putExtra(ChatWallpaperLibrary.EXTRA_SENT_AT, System.currentTimeMillis())
            // CLEAR_TOP as well: a crop screen left on top of the settings task would otherwise get a second settings Activity.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        activity.startActivity(intent)
        HookLog.hit("chat/wallpaperHandoff")
    }
}
