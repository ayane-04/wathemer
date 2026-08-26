package com.wathemer.app.settings

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.wathemer.app.settings.components.RestartWhatsAppButton
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.nav.Screen
import com.wathemer.app.settings.nav.rememberNavController
import com.wathemer.app.settings.prefs.FontLibrary
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.preview.ThemeSnapshotHost
import com.wathemer.app.settings.screens.BackdropScreen
import com.wathemer.app.settings.screens.CategoryListScreen
import com.wathemer.app.settings.screens.ChatBubbleShapesScreen
import com.wathemer.app.settings.screens.ChatBubblesScreen
import com.wathemer.app.settings.screens.ChatHeaderToolbarScreen
import com.wathemer.app.settings.screens.ChatInputBarScreen
import com.wathemer.app.settings.screens.ChatMiscScreen
import com.wathemer.app.settings.screens.ChatQuoteRepliesScreen
import com.wathemer.app.settings.screens.ChatScreen
import com.wathemer.app.settings.screens.ExtrasScreen
import com.wathemer.app.settings.screens.GlobalColorsScreen
import com.wathemer.app.settings.screens.GlobalColorsTokensScreen
import com.wathemer.app.settings.screens.GlobalColorsToolbarScreen
import com.wathemer.app.settings.screens.GlobalColorsUnreadScreen
import com.wathemer.app.settings.screens.HomescreenChatListRowsScreen
import com.wathemer.app.settings.screens.HomescreenChatListScreen
import com.wathemer.app.settings.screens.HomescreenChatListSearchScreen
import com.wathemer.app.settings.screens.HomescreenFabMainScreen
import com.wathemer.app.settings.screens.HomescreenFabMiniFabScreen
import com.wathemer.app.settings.screens.HomescreenFabScreen
import com.wathemer.app.settings.screens.HomescreenHeaderScreen
import com.wathemer.app.settings.screens.HomescreenScreen
import com.wathemer.app.settings.screens.HomescreenTabBarBarScreen
import com.wathemer.app.settings.screens.HomescreenTabBarItemsScreen
import com.wathemer.app.settings.screens.HomescreenTabBarScreen
import com.wathemer.app.settings.screens.LiquidGlassScreen
import com.wathemer.app.settings.screens.StatusBarScreen
import com.wathemer.app.settings.screens.ThemesScreen
import com.wathemer.app.settings.screens.UpdatesScreen
import com.wathemer.app.settings.screens.WallpaperScreen

/** Hosts the settings UI over an in-memory nav stack; system back pops until the root, then Android closes the activity. */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        armCropInsetFix()
        val prefs = Prefs.open(this)
        // Settings survive an uninstall (framework store, not package data); reconcile before the UI reads anything.
        prefs.reconcileFreshInstall()?.let { r ->
            if (r.deferred) {
                Log.i(
                    "WaThemer.Prefs",
                    "fresh-install check deferred: framework store not visible yet",
                )
            } else if (r.cleared > 0) {
                val msg = if (r.writeFailed) {
                    "Found ${r.cleared} settings from a previous install but could NOT clear them " +
                        "(prefs file not writable by this install)"
                } else {
                    "Fresh install: cleared ${r.cleared} settings left by the previous one"
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                Log.i(
                    "WaThemer.Prefs",
                    "fresh install: cleared=${r.cleared} writeFailed=${r.writeFailed} " +
                        "backup=${r.backup}",
                )
            }
        }
        // Must run after the fresh-install clear: running it first would migrate values about to be deleted.
        runCatching { prefs.migrateRemovedBubbleStyle4() }
            .onSuccess { n ->
                if (n != null && n > 0) {
                    Log.i("WaThemer.Prefs", "bubble-style migration: rewrote $n side(s)")
                }
            }
        runCatching { prefs.migrateCollidingTokens() }
            .onSuccess { n ->
                if (n > 0) Log.i("WaThemer.Prefs", "token-collision migration: dodged $n token(s)")
            }
        // Moves the pre-library Downloads font into the library; WhatsApp could never read it there.
        runCatching { FontLibrary.migrateLegacyDownloadsFont(this, prefs) }
            .onSuccess { e ->
                if (e != null) Log.i("WaThemer.Prefs", "legacy user font imported: ${e.name}")
            }
        setContent {
            WaThemerTheme {
                ThemeSnapshotHost(prefs) {
                    val nav = rememberNavController()
                    BackHandler(enabled = nav.canPop()) { nav.pop() }
                    AppRoot(nav, prefs) { msg ->
                        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

private const val UCROP_ACTIVITY = "com.yalantis.ucrop.UCropActivity"
private var cropInsetFixArmed = false

/** Pads UCrop's content by the system-bar insets: enforced edge-to-edge buries its toolbar, and a fitsSystemWindows theme cannot fix that. */
private fun ComponentActivity.armCropInsetFix() {
    if (cropInsetFixArmed) return
    cropInsetFixArmed = true
    application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity.javaClass.name != UCROP_ACTIVITY) return
            val content = activity.findViewById<View>(android.R.id.content) ?: return
            ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(content)
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    })
}

@Composable
private fun AppRoot(nav: NavController, prefs: Prefs, onMessage: (String) -> Unit) {
    // App chrome stays locked to AppAccent no matter which WhatsApp theme is being configured.
    Column(modifier = Modifier.fillMaxSize()) {
        // Standing banner, never a toast: with the module inactive every control silently changes nothing.
        if (!prefs.moduleStoreActive) {
            Text(
                text = "Module not active. Enable WaThemer in your Xposed manager, add WhatsApp to " +
                    "its scope, then reopen this app. Changes made now will NOT apply.",
                color = Color(0xFF1A1207),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFC46B))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            ScreenSwitch(nav, prefs, onMessage)
        }
        RestartWhatsAppButton(onMessage = onMessage)
    }
}

@Composable
private fun ScreenSwitch(nav: NavController, prefs: Prefs, onMessage: (String) -> Unit) {
    when (nav.current) {
        Screen.CategoryList            -> CategoryListScreen(nav, prefs)

        Screen.GlobalColors            -> GlobalColorsScreen(nav, prefs, onMessage)
        Screen.GlobalColorsTokens      -> GlobalColorsTokensScreen(nav, prefs)
        Screen.GlobalColorsUnread      -> GlobalColorsUnreadScreen(nav, prefs)
        Screen.GlobalColorsToolbar     -> GlobalColorsToolbarScreen(nav, prefs)
        Screen.Backdrop                -> BackdropScreen(nav, prefs)
        Screen.Wallpaper               -> WallpaperScreen(nav, prefs)
        Screen.StatusBar               -> StatusBarScreen(nav, prefs)
        Screen.LiquidGlass             -> LiquidGlassScreen(nav, prefs)

        // Homescreen tree
        Screen.Homescreen              -> HomescreenScreen(nav, prefs)
        Screen.HomescreenChatList      -> HomescreenChatListScreen(nav, prefs)
        Screen.HomescreenChatListRows  -> HomescreenChatListRowsScreen(nav, prefs)
        Screen.HomescreenChatListSearch -> HomescreenChatListSearchScreen(nav, prefs)
        Screen.HomescreenTabBar        -> HomescreenTabBarScreen(nav, prefs)
        Screen.HomescreenTabBarBar     -> HomescreenTabBarBarScreen(nav, prefs)
        Screen.HomescreenTabBarItems   -> HomescreenTabBarItemsScreen(nav, prefs)
        Screen.HomescreenHeader        -> HomescreenHeaderScreen(nav, prefs)
        Screen.HomescreenFab           -> HomescreenFabScreen(nav, prefs)
        Screen.HomescreenFabMain       -> HomescreenFabMainScreen(nav, prefs)
        Screen.HomescreenFabMiniFab    -> HomescreenFabMiniFabScreen(nav, prefs)

        // Chat tree
        Screen.Chat                    -> ChatScreen(nav, prefs)
        Screen.ChatBubbles             -> ChatBubblesScreen(nav, prefs)
        Screen.ChatBubblesCustom       -> ChatBubbleShapesScreen(nav, prefs)
        Screen.ChatInputBar            -> ChatInputBarScreen(nav, prefs)
        Screen.ChatHeaderToolbar       -> ChatHeaderToolbarScreen(nav, prefs)
        Screen.ChatQuoteReplies        -> ChatQuoteRepliesScreen(nav, prefs)
        Screen.ChatMisc                -> ChatMiscScreen(nav, prefs)

        Screen.Extras                  -> ExtrasScreen(nav, prefs, onMessage)
        Screen.Themes                  -> ThemesScreen(nav, prefs, onMessage)
        Screen.Updates                 -> UpdatesScreen(nav, prefs, onMessage)
    }
}

@Composable
private fun WaThemerTheme(content: @Composable () -> Unit) {
    // Settings-UI dressing is fixed Coral, separate from the user's WhatsApp tokens.
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFC95548),
            background = Color(0xFF0A0A0A),
            surface = Color(0xFF141414),
            onPrimary = Color(0xFF0A0A0A),
            onBackground = Color(0xFFFFFFFF),
            onSurface = Color(0xFFFFFFFF),
        ),
        content = content,
    )
}
