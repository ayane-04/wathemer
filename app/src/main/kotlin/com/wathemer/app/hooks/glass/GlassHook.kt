// The entry point, and the table of which WhatsApp id gets which treatment; the surfaces themselves
// live in the sibling Glass*.kt files. Only install and the three getters below are called from outside.
package com.wathemer.app.hooks.glass

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewOverlay
import android.view.ViewStub
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.wathemer.app.glass.BackdropCapture
import com.wathemer.app.glass.FrostDrawable
import com.wathemer.app.glass.GlassParams
import com.wathemer.app.glass.GlassShader
import com.wathemer.app.glass.GlassView
import com.wathemer.app.glass.WallpaperLook
import com.wathemer.app.hooks.ActivityLifecycle
import com.wathemer.app.hooks.HookLog
import com.wathemer.app.hooks.ModulePrefs
import com.wathemer.app.hooks.WaIds
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.hooks.waId
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference

object GlassHook {


    private val frostHostListenerTag = tagKey("wathemer-frost-host-listener")
    private val tilePillTag = tagKey("wathemer-tile-pill")


    fun install(app: Application) {
        if (!loadGlassPrefs()) {
            XposedBridge.log("[$TAG] disabled by preference")
            HookLog.skip("install/GlassHook", "glass_enabled is off")
            return
        }
        // Before any pane exists: every capture draws live views, and a ripple in one crashes the frame.
        ensureSelectorGuard()
        // A refused capture must reach the module log; the engine's own Log.w never does.
        BackdropCapture.onRefused = { msg ->
            XposedBridge.log("[$TAG] $msg")
            HookLog.hit("guard/captureCycle", msg)
        }
        HookLog.arm("guard/captureCycle")
        // A program that fails to compile falls back silently; the ledger has to say so.
        GlassShader.onCompileFailure = { what, t -> HookLog.fail("glass/agsl/$what", t) }
        GlassShader.onCompiled = { what -> HookLog.hit("glass/agsl/$what") }
        val res = app.resources
        // Density is only available here, so the dp to px conversion cannot live in loadGlassPrefs.
        GlassParams.defaultRimStrokePx =
            if (RIM_ALPHA > 0) RIM_WIDTH_DP * res.displayMetrics.density else 0f
        GlassParams.defaultRimStrokeColor = ((RIM_ALPHA * 255 / 100) shl 24) or 0xFFFFFF
        XposedBridge.log(
            "[$TAG] blur=${BLUR_DP}dp tint=$TINT_ALPHA displace=${DISPLACE_DP}dp " +
                "bevel=$BEVEL_FRACTION radius=${CARD_RADIUS_DP}dp " +
                "gamma=${GlassParams.defaultTransGamma} rim=$RIM_ALPHA/${RIM_WIDTH_DP}dp " +
                "rimAngle=${GlassParams.defaultRimStrokeAngle}",
        )
        val pkg = app.packageName

        val headerId = res.waId("header", pkg)
        headerIdPin = headerId
        homeHeaderId = headerId
        val toolbarId = res.waId("toolbar", pkg)
        homeToolbarId = toolbarId
        actionBarRootId = res.waId("action_bar_root", pkg)
        val toolbarContainerId = res.waId("toolbar_container", pkg)
        val searchBarId = res.waId("my_search_bar", pkg)
        val pagerHolderId = res.waId("pager_holder", pkg)
        val navContainerId = res.waId("bottom_nav_container", pkg)
        navContainerIdPin = navContainerId

        if (headerId == 0 || pagerHolderId == 0) {
            XposedBridge.log("[$TAG] header/pager_holder id missing; glass not armed")
            HookLog.skip("install/GlassHook", "header or pager_holder id missing")
            return
        }

        // @android:id/list is a framework id getIdentifier cannot resolve; ViewThemeDispatcher matches raw ints.
        ViewThemeDispatcher.onId(android.R.id.list) { v -> runCatching { extendList(v) } }

        // ── The chat-list card's host ──────────────────────────────────────────────────
        // conversations_coordinator_layout stacks like a FrameLayout and pages with the content, unlike id/content.
        val coordId = res.waId("conversations_coordinator_layout", pkg)
        if (coordId != 0) ViewThemeDispatcher.onId(coordId) { v ->
            val vg = v as? ViewGroup ?: return@onId
            // Not home-only: Archived inherits this same layout, and home is the window that has header.
            // Anywhere else takes its own card, or cardHostRef follows the user off home onto a foreign host.
            if (headerIdPin != 0 && vg.rootView?.findViewById<View>(headerIdPin) == null) {
                runCatching { injectFolderCard(vg) }
                    .onFailure { XposedBridge.log("[$TAG] injectFolderCard threw: $it") }
                return@onId
            }
            cardHostRef = WeakReference(vg)
            runCatching { injectListPanel(vg) }
                .onFailure { XposedBridge.log("[$TAG] injectListPanel threw: $it") }
        }

        // ── List pages with no stackable root, hosted by android.R.id.content ──────────
        // Their ActionMenuView measures zero width, nothing for a trailing pane to wrap; the card is the whole treatment.
        for ((name, label) in listOf(
            "linked_device_recycler_view" to "linked devices",
            "broadcast_lists_recycler_view" to "broadcast lists",
        )) {
            val listId = res.waId(name, pkg)
            if (listId != 0) ViewThemeDispatcher.onId(listId) { v ->
                runCatching { injectContentCard(v, label) }
                    .onFailure { XposedBridge.log("[$TAG] $label card threw: $it") }
            }
        }
        // The stub declares no inflatedId, so the inflated stats view attaches carrying the stub's id.
        val counterId = res.waId("broadcast_counter_view_stub", pkg)
        if (counterId != 0) ViewThemeDispatcher.onId(counterId) { v ->
            if (v is ViewStub) return@onId
            runCatching { injectBlockCard(v, "broadcast stats") }
                .onFailure { XposedBridge.log("[$TAG] broadcast stats panel threw: $it") }
        }
        val broadcastFabId = res.waId("create_new_broadcast_button", pkg)
        if (broadcastFabId != 0) ViewThemeDispatcher.onId(broadcastFabId) { v ->
            // Attach precedes layout, so the treatment runs from the button's own layout passes.
            if (v.getTag(folderFabTag) == null) {
                v.setTag(folderFabTag, true)
                v.addOnLayoutChangeListener { b, _, _, _, _, _, _, _, _ ->
                    runCatching { folderFab(b, "broadcast") }
                        .onFailure { XposedBridge.log("[$TAG] broadcast fab threw: $it") }
                }
            }
        }
        // Settings is the same shape with a ScrollView; content taller than the viewport clamps the card to it.
        // An A/B flag swaps in a me-tab layout that scrolls a different id; hooking both covers either.
        for (n in listOf("settings_scroll_view", "settings_nested_scroll_view")) {
            val sid2 = res.waId(n, pkg)
            if (sid2 != 0) ViewThemeDispatcher.onId(sid2) { v ->
                runCatching { injectContentCard(v, "settings") }
                    .onFailure { XposedBridge.log("[$TAG] settings card threw: $it") }
            }
        }
        // Sub-pages have few stable list ids, but their Activity names are manifest names and never obfuscate.
        // Rides the lifecycle callbacks, never Activity.onPostCreate; the card machinery is idempotent and a treated scroller skips by tag.
        ActivityLifecycle.onCreated("glassCards") { a ->
            val name = a.javaClass.name
            if (CARDED_ACTIVITY_PREFIXES.none { name.startsWith(it) }) return@onCreated
            // Sheet activities already carry the sheet pane; a card under a sheet is buried work.
            if (a.javaClass.simpleName.endsWith("BottomSheetActivity")) return@onCreated
            if (a.javaClass.simpleName.endsWith("Sheet")) return@onCreated
            // Both draw a full-screen doodle SIBLING under the content; a sibling is invisible to the ancestor walk, so they are named.
            if (a.javaClass.simpleName == "About" || a.javaClass.simpleName == "Licenses") return@onCreated
            val label = "${name.split('.').getOrElse(2) { "page" }}/${a.javaClass.simpleName}"
            // The breadcrumb that separates "hook never fired" from a silent guard.
            logOnce("card path armed: $label")
            val content =
                a.findViewById<ViewGroup>(android.R.id.content) ?: return@onCreated
            content.post {
                runCatching {
                    findPageScroller(content)?.let {
                        injectContentCard(it, label)
                    } ?: watchForPageScroller(content, label)
                }.onFailure { XposedBridge.log("[$TAG] $label card threw: $it") }
            }
        }

        // ── The media gallery's per-tab grids ──────────────────────────────────────────
        // Each tab inflates its grid from a stub that keeps the generic id, so the Activity scopes it.
        val gridId = res.waId("grid", pkg)
        if (gridId != 0) ViewThemeDispatcher.onId(gridId) { v ->
            if (v is ViewStub) return@onId
            if (activityOf(v)?.javaClass?.name?.startsWith("com.whatsapp.gallery.") != true) return@onId
            runCatching { injectContentCard(v, "gallery", frameCard = true) }
                .onFailure { XposedBridge.log("[$TAG] gallery card threw: $it") }
        }

        // Starred's list is the framework android.R.id.list, shared with the chat list, so it is reached through its own container.
        val starredId = res.waId("starred_messages_content", pkg)
        if (starredId != 0) ViewThemeDispatcher.onId(starredId) { v ->
            val list = (v as? ViewGroup)?.findViewById<View>(android.R.id.list) ?: return@onId
            runCatching { injectContentCard(list, "starred") }
                .onFailure { XposedBridge.log("[$TAG] starred card threw: $it") }
        }

        // ── Snackbars ────────────────────────────────────────────────────────────────────
        // Material's slab frosted in place; found from the text view, the layout's one stable id.
        val snackTextId = res.waId("snackbar_text", pkg)
        val snackFrostTag = tagKey("wathemer-snackbar-frost")
        if (snackTextId != 0) ViewThemeDispatcher.onId(snackTextId) { v ->
            var frame: View? = v
            var depth = 0
            while (frame != null && depth < 4 && !frame.javaClass.name.endsWith("SnackbarLayout")) {
                frame = frame.parent as? View
                depth++
            }
            val f = frame ?: return@onId
            if (f.getTag(snackFrostTag) != null) return@onId
            f.setTag(snackFrostTag, true)
            // Posted: the bar measures on the frame after attach, and frost needs a real size.
            f.post {
                runCatching {
                    // No clear first: the forced frost replaces the fill, and a clear that outlives a zero-size bail leaves the bar bare.
                    // The menu's scrim, not the wallpaper tint: a bar floats over content and carries no blur of its own.
                    // Forced: Material's snackbar bridges setBackground to setBackgroundDrawable, and the interceptor sees that path.
                    frost(
                        f, tintOverride = MENU_SCRIM, allowSquare = true, ignorePadding = true,
                        radiusOverride = f.dp(10f), forceLabel = "snackbar",
                    )
                    logOnce("snackbar frosted")
                }
            }
        }

        // ── The updates page: its own card and big title ───────────────────────────────
        val updatesListId = res.waId("updates_list", pkg)
        if (updatesListId != 0) ViewThemeDispatcher.onId(updatesListId) { v ->
            val host = v.parent as? ViewGroup
            if (host == null || !canStack(host)) {
                XposedBridge.log(
                    "[$TAG] updates page host is ${host?.javaClass?.simpleName}, which does " +
                        "not stack; no card there"
                )
                return@onId
            }
            updatesListRef = WeakReference(v)
            updatesHostRef = WeakReference(host)
            // Content scrolls up under the title and the header, as it does on Chats.
            (v as? ViewGroup)?.clipToPadding = false
            runCatching { injectUpdatesCard(host) }
                .onFailure { XposedBridge.log("[$TAG] injectUpdatesCard threw: $it") }
            syncUpdatesCard()
        }

        // The wordmark is an image, not the toolbar's text title, so hiding it leaves the other tabs untouched.
        val logoId = res.waId("toolbar_logo", pkg)
        if (logoId != 0) ViewThemeDispatcher.onId(logoId) { v ->
            if (v.visibility != View.GONE) {
                v.visibility = View.GONE
                XposedBridge.log("[$TAG] toolbar_logo hidden for the large title")
            }
        }
        if (searchBarId != 0) ViewThemeDispatcher.onId(searchBarId) { v ->
            // Lift above the rows that scroll underneath; translationZ, since with no background there is no shadow anyway.
            if (v.translationZ < 1f) v.translationZ = 1f
            searchBarRef = WeakReference(v)
            // The search pill starts where this bar rests; layout coords, since getLocationOnScreen samples it mid-flight.
            if (v.getTag(homeBarLayoutTag) == null) {
                v.setTag(homeBarLayoutTag, true)
                v.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
                    val content = contentRef?.get() ?: return@addOnLayoutChangeListener
                    if (view.height <= 0) return@addOnLayoutChangeListener
                    val r = Rect(0, 0, view.width, view.height)
                    if (runCatching { content.offsetDescendantRectToMyCoords(view, r) }.isSuccess) {
                        homeBarTop = r.top
                        homeBarHeight = r.height()
                    }
                    // No return trigger here: layout fires too early or not at all; the pane's hide callback drives it.
                }
            }
            // Injected here: at the container's attach my_search_bar is not in it yet, so findViewById returns null.
            runCatching { injectBigTitle(v) }
                .onFailure { XposedBridge.log("[$TAG] injectBigTitle threw: $it") }
        }

        // One rounded pane for the action group; register every tab's items or it collapses onto the lone overflow.
        for (n in listOf(
            "menuitem_payment_rupee_icon", "menuitem_camera", "menuitem_search",
        )) {
            val cid = res.waId(n, pkg)
            if (cid != 0) ViewThemeDispatcher.onId(cid) { v ->
                val holder = menuHolder(v); tagGroup(holder, GROUP_NORMAL); registerAction(holder)
            }
        }
        // ── The toolbar bleeds through the selection bar ───────────────────────────────
        // action_mode_bar's own visibility is the signal; overlap is not occlusion, never reintroduce a geometric test.
        // Needs a repeating watcher (onId is one-shot), one restoring writer, and skipping a toolbar that holds the bar.
        val waToolbarId = res.waId("toolbar", pkg)
        val actionBarId = res.waId("action_mode_bar", pkg)
        if (waToolbarId != 0 && actionBarId != 0) {
            ViewThemeDispatcher.onId(actionBarId) { bar ->
                runCatching { watchActionBar(bar, waToolbarId) }
                    .onFailure { XposedBridge.log("[$TAG] watchActionBar threw: $it") }
            }
            XposedBridge.log("[$TAG] toolbar bleed guard armed (toolbar=0x${waToolbarId.toString(16)})")
        } else {
            XposedBridge.log("[$TAG] toolbar bleed guard NOT armed: toolbar=$waToolbarId bar=$actionBarId")
        }
        // ── Selection mode's own icons ─────────────────────────────────────────────────
        // The normal icons stay attached underneath, so the two sets cannot be pooled; syncActionsGlass picks the owner.
        for (n in listOf(
            "menuitem_conversations_pin", "menuitem_conversations_unpin",
            "menuitem_conversations_delete", "menuitem_conversations_archive",
            "menuitem_conversations_unarchive", "menuitem_conversations_mark_read",
            "menuitem_conversations_mark_unread", "menuitem_conversations_lock",
            "menuitem_conversations_unlock", "menuitem_conversations_select_all",
            "menuitem_mute", "menuitem_unmute", "menuitem_delete",
        )) {
            val cid = res.waId(n, pkg)
            if (cid != 0) ViewThemeDispatcher.onId(cid) { v ->
                val holder = menuHolder(v); tagGroup(holder, GROUP_SELECTION); registerAction(holder)
            }
        }
        // The overflow button is in both bars, so it belongs to neither group and is always counted.
        res.waId("menuitem_overflow", pkg).takeIf { it != 0 }?.let { cid ->
            ViewThemeDispatcher.onId(cid) { v ->
                val holder = menuHolder(v); tagGroup(holder, GROUP_BOTH); registerAction(holder)
                // Reaches the chat header too, whose call buttons have no id and no tint of their own.
                runCatching { whitenActionIcons(holder) }
                    .onFailure { logOnce("action icon whitening threw: $it") }
            }
        }

        // Do not frost the voice overlay: the trashcan has no fill of its own and the lock container is full-screen.
        // The picker arms on contact_picker_layout; toolbar and list are names WhatsApp reuses across screens.
        val pickerId = res.waId("contact_picker_layout", pkg)
        if (pickerId != 0) ViewThemeDispatcher.onId(pickerId) { v ->
            runCatching { pickerGlass(v as? ViewGroup ?: return@onId) }
                .onFailure { XposedBridge.log("[$TAG] pickerGlass threw: $it") }
        }

        // The lock pill is styled via ensureLockPane; the trashcan's own disc has no known id.
        val lockId = res.waId("voice_note_lock_container", pkg)
        if (lockId != 0) ViewThemeDispatcher.onId(lockId) { v -> runCatching { ensureLockPane(v) } }

        // ── Filter chips + the filter button ───────────────────────────────────────────
        // No ids, no stacking host, and every capturable subtree is an ancestor, so a frost Drawable is the only option.
        val chipHostId = res.waId("conversations_swipe_to_reveal_filter_recycler_view", pkg)
        val pinnedId = res.waId("conversations_filter_pinned_button_container", pkg)
        // The region filters ride along: their chips have no ids, only content-descs, so the host is the handle there too.
        val filterListId = res.waId("filter_list", pkg)
        for (hid in listOf(chipHostId, pinnedId, filterListId)) {
            if (hid == 0) continue
            ViewThemeDispatcher.onId(hid) { host ->
                val hv = host as? ViewGroup ?: return@onId
                val frostKids = Runnable {
                    for (i in 0 until hv.childCount) {
                        runCatching { frost(hv.getChildAt(i) ?: return@runCatching) }
                    }
                }
                // Paint: every attach. Listener: once per instance.
                if (hv.getTag(frostHostListenerTag) == null) {
                    hv.setTag(frostHostListenerTag, true)
                    hv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> frostKids.run() }
                }
                hv.post(frostKids)
            }
        }

        // ── "See all", the region filters, and the appbar seam ─────────────────────────
        // addon_button is shared: See all on channels, the chevron on Updates; frost()'s shape guard skips the chevron.
        // A WDSButton paints its fill from its variant, so setBackground cannot outrank it; left as a plain frost.
        val addonId = res.waId("addon_button", pkg)
        if (addonId != 0) ViewThemeDispatcher.onId(addonId) { v -> frostOnLayout(v) }

        // ── The reply quote inside the compose pill ───────────────────────────────────
        // Tint-only: the compose pane supplies the blur; a bitmap would sample behind the pill, not the quote.
        val quoteId = res.waId("quoted_message_preview_container", pkg)
        if (quoteId != 0) ViewThemeDispatcher.onId(quoteId) { v ->
            if (!quoteColored) frostOnLayoutWith(v, glassTint(QUOTE_ALPHA), v.dp(QUOTE_RADIUS_DP))
        }

        // ── The same quote, inside a sent/received bubble ─────────────────────────────
        // Target the frame, not the holder (that paints over the accent bar); stands down when QUOTE_BG_COLOR is set.
        // ── And the four green triangles at its corners ───────────────────────────────
        // The frame's foreground nine-patch is a corner mask in stock green, so it is nulled; see installQuoteMaskKill.
        val quoteFrameId = res.waId("quoted_message_frame", pkg)
        if (quoteFrameId != 0) {
            installQuoteMaskKill(quoteFrameId)
            ViewThemeDispatcher.onId(quoteFrameId) { v ->
                if (!quoteColored) {
                    // Blurred fill, not a film: tint-only vanishes against the dark bubble glass.
                    liquidFrostOnLayout(v, QUOTE_RADIUS_DP, QUOTE_ALPHA)
                    if (v.foreground != null) v.foreground = null
                    // The killed foreground mask was what rounded the accent bar; the clip takes over.
                    if (!v.clipToOutline) {
                        v.outlineProvider = object : ViewOutlineProvider() {
                            override fun getOutline(view: View, outline: Outline) {
                                outline.setRoundRect(
                                    0, 0, view.width, view.height, view.dp(QUOTE_RADIUS_DP),
                                )
                            }
                        }
                        v.clipToOutline = true
                    }
                }
            }
        }

        // ── The voice-recording composer ─────────────────────────────────────────────
        // A cover, not a clear: stripped bare, the compose quote underneath shows and two quotes stack.
        val voiceDraftId = res.waId("voice_note_draft_layout_v2", pkg)
        if (voiceDraftId != 0) ViewThemeDispatcher.onId(voiceDraftId) { v ->
            liquidFrostOnLayout(v, 20f, TINT_ALPHA)
        }
        // Tint-only like the compose quote: a second bitmap pill doubles the panel's top edge.
        val quoteV2Id = res.waId("quoted_message_preview_container_v2", pkg)
        if (quoteV2Id != 0) ViewThemeDispatcher.onId(quoteV2Id) { v ->
            if (!quoteColored) frostOnLayoutWith(v, glassTint(QUOTE_ALPHA), v.dp(QUOTE_RADIUS_DP))
        }

        // Section headers go bold; weight only, the size is WhatsApp's and reads fine.
        val headerTvId = res.waId("header_textview", pkg)
        if (headerTvId != 0) ViewThemeDispatcher.onId(headerTvId) { v ->
            val tv = v as? TextView ?: return@onId
            if (tv.typeface?.isBold != true) {
                tv.setTypeface(tv.typeface, Typeface.BOLD)
            }
        }

        // Over glass the 3px rule reads as a seam. INVISIBLE keeps the appbar's height where GONE would collapse it.
        val filterDividerId = res.waId("filter_divider", pkg)
        if (filterDividerId != 0) ViewThemeDispatcher.onId(filterDividerId) { v ->
            if (v.visibility != View.INVISIBLE) {
                v.visibility = View.INVISIBLE
                XposedBridge.log("[$TAG] filter_divider hidden")
            }
        }

        // ── The communities tab ────────────────────────────────────────────────────────
        // The page root does not stack, so the card is a sibling whose margins cancel its height; see injectPageCard.
        val commListId = res.waId("community_recycler_view", pkg)
        if (commListId != 0) ViewThemeDispatcher.onId(commListId) { v ->
            armPageCard(communityCard, v, communityCardTag)
            // Only this tab has the painted ItemDecorations.
            runCatching { killDecorations(v, commListId) }
                .onFailure { XposedBridge.log("[$TAG] killDecorations threw: $it") }
        }

        // ── The calls tab ──────────────────────────────────────────────────────────────
        // Same shape as Communities, measured; its own registration only because both pages are alive at once.
        val callsListId = res.waId("calls_recyclerView", pkg)
        if (callsListId != 0) ViewThemeDispatcher.onId(callsListId) { v ->
            armPageCard(callsCard, v, callsCardTag)
        }

        // The four call-action discs sit in a RecyclerView whose id is the generic list, so the hook is scoped by class.
        val genericListId = res.waId("list", pkg)
        if (genericListId != 0) ViewThemeDispatcher.onId(genericListId) { v ->
            // Routed through WaIds so a class rename logs instead of the discs quietly losing their frost.
            if (!WaIds.classIs(v, "CallInitiationHScroll")) return@onId
            val row = v as? ViewGroup ?: return@onId
            val sweep = Runnable { runCatching { frostCallActions(row) } }
            if (row.getTag(callActionsTag) == null) {
                row.setTag(callActionsTag, true)
                row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sweep.run() }
            }
            sweep.run()
        }

        // ── Cards on the drilled-in channel screens ────────────────────────────────────
        // The card's top is list.top, not the host origin: the chrome differs and one screen's list starts at y321.
        // The call list's WDSDivider reads as a rule on the glass; the list recycles, so the hide re-runs on layout.
        val logsId = res.waId("logs", pkg)
        if (logsId != 0) ViewThemeDispatcher.onId(logsId) { v ->
            val vg = v as? ViewGroup ?: return@onId
            runCatching { hideWdsDividers(vg) }
            if (vg.getTag(callLogDividerTag) == null) {
                vg.setTag(callLogDividerTag, true)
                vg.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { hideWdsDividers(vg) }
                }
            }
        }

        for (n in listOf(
            "directory_category_list",
            "newsletter_list",
            "community_navigation_subgroup_recycler_view",
            "logs",   // the call info screen's entries
        )) {
            val lid = res.waId(n, pkg)
            if (lid == 0) continue
            ViewThemeDispatcher.onId(lid) { v ->
                val host = v.parent as? ViewGroup
                if (host == null || !canStack(host)) {
                    XposedBridge.log(
                        "[$TAG] $n host is ${host?.javaClass?.simpleName}, which does not " +
                            "stack; no card"
                    )
                    return@onId
                }
                (v as? ViewGroup)?.clipToPadding = false
                channelListRef = WeakReference(v)
                channelHostRef = WeakReference(host)
                runCatching { injectChannelCard(host) }
                    .onFailure { XposedBridge.log("[$TAG] injectChannelCard threw: $it") }
                syncChannelCard()
                if (host.getTag(channelCardTag) == null) {
                    host.setTag(channelCardTag, true)
                    host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        runCatching { syncChannelCard() }
                    }
                }
                // Pre-draw, not layout: the collapsing appbar moves the list by offsetTopAndBottom and no layout listener fires.
                if (v.getTag(channelListTag) == null) {
                    v.setTag(channelListTag, true)
                    v.viewTreeObserver.addOnPreDrawListener {
                        runCatching { syncChannelCard() }
                        true
                    }
                }
            }
        }

        // ── The drilled-in screens' toolbar (Channels, Explore channels) ───────────────
        // A different toolbar, in toolbar_holder rather than id/header, so none of the home action-pane work reaches it.
        val toolbarHolderId = res.waId("toolbar_holder", pkg)
        if (toolbarHolderId != 0) ViewThemeDispatcher.onId(toolbarHolderId) { v ->
            val holder = v as? ViewGroup ?: return@onId
            // The pickers give this id to a WDSSearchBar; that component has its own path and its own fills to clear.
            if (holder.javaClass.name.endsWith("WDSSearchBar")) {
                (holder as? FrameLayout)?.let { armWdsSearchBar(it, "toolbar_holder") }
                return@onId
            }
            altToolbarRef = WeakReference(holder)
            altBarRef = null                       // this screen's toolbar is discoverable
            runCatching { syncAltToolbarGlass() }
                .onFailure { XposedBridge.log("[$TAG] syncAltToolbarGlass threw: $it") }
            if (holder.getTag(altToolbarTag) == null) {
                holder.setTag(altToolbarTag, true)
                holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncAltToolbarGlass() }
                }
            }
        }

        // ── The communities flow's opaque accent buttons ───────────────────────────────
        // Flat green fills, frosted from a layout listener because WhatsApp applies the fill after attach.
        for (n in listOf(
            "community_navigation_add_group_button",   // "ADD GROUP", community home
            "empty_community_row_button",              // "Start your community", empty state
            "community_nux_next_button",               // "GET STARTED", creation intro
            "add_members_icon",                        // the green circle on the info page
            "community_home_add_group_icon",           // "add group", the admin row under a community
            "owner_view",                              // the "Community Owner" chip
        )) {
            val bid = res.waId(n, pkg)
            if (bid != 0) ViewThemeDispatcher.onId(bid) { v -> frostAccent(v) }
        }

        // ── The community info page's slab dividers ────────────────────────────────────
        // INVISIBLE rather than GONE keeps the separation, and the gap shows wallpaper.
        for (n in listOf(
            "community_home_top_divider",
            "community_home_header_bottom_divider_admin",
            "community_home_header_bottom_divider_non_admin",
            "community_description_bottom_divider",
            // Slips hideSlabsByShape by construction (its side margins), so it is hidden by name like the pair above.
            "community_description_top_divider",
        )) {
            val did = res.waId(n, pkg)
            if (did != 0) ViewThemeDispatcher.onId(did) { v -> hideSlab(v, n) }
        }

        // ── Hairlines on the other glassed screens ────────────────────────────────────
        // status_separator, newsletter_status_divider and list_footer_gray_divider are excluded: their parent has its own fill.
        for (n in listOf(
            "list_section_divider_status",                     // Updates page, on its glass card
            "conversation_row_favorites_footer_divider",       // Chats list, favourites footer
            "conversations_row_lists_manage_footer_divider",   // Chats list, manage-lists footer
            "search_divider",                                  // search disclaimer block
            "reactions_bottom_sheet_divider",                  // reactions sheet
            "dialog_clear_messages_content_divider",           // clear-messages dialog
            "divider_under_nav_bar",                           // call/privacy sheet
            "group_info_shortcuts_top_divider",                // group/contact info, above the Members bar
        )) {
            val did = res.waId(n, pkg)
            if (did != 0) ViewThemeDispatcher.onId(did) { v -> hideSlab(v, n) }
        }

        // Two more bands carry no id at all, so they are matched by shape, scoped to this page's own container.
        val infoListId = res.waId("community_home_fragment_container", pkg)
        if (infoListId != 0) ViewThemeDispatcher.onId(infoListId) { v ->
            val root = v as? ViewGroup ?: return@onId
            val sweep = Runnable { runCatching { hideSlabsByShape(root) } }
            if (root.getTag(slabSweepTag) == null) {
                root.setTag(slabSweepTag, true)
                // The shape walk from layout. It is a subtree walk, so not every frame.
                root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> sweep.run() }
                // The named dividers from pre-draw: one is re-shown with no bounds change, so no layout callback can catch it.
                root.viewTreeObserver.addOnPreDrawListener {
                    runCatching { reassertSlabs() }
                    true
                }
            }
            sweep.run()
        }

        // ── The call info screen ───────────────────────────────────────────────────────
        // Structurally the community home page again, measured; header_view and the appbar share bounds, so both clear.
        val collapsingId = res.waId("call_info_collapsing_toolbar", pkg)
        for (n in listOf("call_info_app_bar", "header_view")) {
            val hid = res.waId(n, pkg)
            if (hid != 0) ViewThemeDispatcher.onId(hid) { v ->
                // header_view is generic; scoped by parent or an unrelated header loses its background too.
                if (n == "header_view" && (v.parent as? View)?.id != collapsingId) return@onId
                clearBg(v, n)
            }
        }

        // The toolbar's parent cannot stack, so the nearest stacking ancestor takes the panes and the bar is passed in.
        val callInfoTitleId = res.waId("call_info_toolbar_content", pkg)
        if (callInfoTitleId != 0) ViewThemeDispatcher.onId(callInfoTitleId) { v ->
            val bar = v.parent as? ViewGroup ?: return@onId
            var holder: ViewGroup? = null
            var p = bar.parent
            while (p is ViewGroup) {
                if (canStack(p)) { holder = p; break }
                p = p.parent
            }
            val h = holder ?: return@onId
            altToolbarRef = WeakReference(h)
            altBarRef = WeakReference(bar)
            runCatching { syncAltToolbarGlass() }
                .onFailure { XposedBridge.log("[$TAG] call info toolbar threw: $it") }
            if (h.getTag(altToolbarTag) == null) {
                h.setTag(altToolbarTag, true)
                h.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncAltToolbarGlass() }
                }
            }
        }

        // The header panel goes in the CollapsingToolbarLayout, which stacks; the AppBarLayout would displace the header.
        if (collapsingId != 0) ViewThemeDispatcher.onId(collapsingId) { v ->
            val host = v as? ViewGroup ?: return@onId
            callHeaderHostRef = WeakReference(host)
            runCatching { injectCallHeaderPanel(host) }
                .onFailure { XposedBridge.log("[$TAG] injectCallHeaderPanel threw: $it") }
            syncCallHeaderPanel()
            if (host.getTag(callHeaderTag) == null) {
                host.setTag(callHeaderTag, true)
                host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncCallHeaderPanel() }
                }
                // Insurance only: the height can change without a layout pass; the collapse itself writes no geometry here.
                host.viewTreeObserver.addOnPreDrawListener {
                    runCatching { syncCallHeaderPanel() }
                    true
                }
            }
        }

        // action_tile_icon spans three screens; only tiles under a known group frost, or the community tiles get it too.
        val tileIconId = res.waId("action_tile_icon", pkg)
        val tileGroupIds = intArrayOf(
            res.waId("call_log_actions", pkg),          // the call log's row of three
            res.waId("business_details_actions", pkg),  // contact info's row under the name
            res.waId("group_details_actions", pkg),      // group info's row under the subject
        ).filter { it != 0 }.toIntArray()
        if (tileIconId != 0 && tileGroupIds.isNotEmpty()) ViewThemeDispatcher.onId(tileIconId) { v ->
            var p: Any? = v.parent
            var known = false
            while (p is View) {
                if (p.id in tileGroupIds) { known = true; break }
                p = p.parent
            }
            if (!known) return@onId
            frostSquareOnLayout(v)
        }

        // ── The community home page's appbar ───────────────────────────────────────────
        // No toolbar_holder here; the pane host is the toolbar's own parent, a FrameLayout subclass.
        val commAppBarId = res.waId("community_navigation_app_bar", pkg)
        if (commAppBarId != 0) ViewThemeDispatcher.onId(commAppBarId) { v ->
            // Opaque in stock; cleared rather than replaced, so nothing here fights a colour theme.
            clearBg(v, "community_navigation_app_bar")
        }
        val commToolbarId = res.waId("community_navigation_toolbar", pkg)
        if (commToolbarId != 0) ViewThemeDispatcher.onId(commToolbarId) { v ->
            val holder = v.parent as? ViewGroup ?: return@onId
            if (!canStack(holder)) {
                XposedBridge.log(
                    "[$TAG] community toolbar parent is ${holder.javaClass.simpleName}, which does " +
                        "not stack; no panes"
                )
                return@onId
            }
            altToolbarRef = WeakReference(holder)
            altBarRef = null                       // this screen's toolbar is discoverable
            runCatching { syncAltToolbarGlass() }
                .onFailure { XposedBridge.log("[$TAG] syncAltToolbarGlass threw: $it") }
            if (holder.getTag(altToolbarTag) == null) {
                holder.setTag(altToolbarTag, true)
                holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncAltToolbarGlass() }
                }
            }
        }

        // ── The conversation screen ────────────────────────────────────────────────────
        // The toolbar draws before the content here, so the holder needs a translationZ lift for rows to pass behind it.
        // Its geometry changes when the menu inflates; every pane rect is re-derived on layout and nothing is snapshotted.
        val convHolderId = res.waId("search_fragment_and_toolbar_holder", pkg)
        val convCoordId = res.waId("coordinator", pkg)
        val convFooterId = res.waId("footer", pkg)
        if (convCoordId != 0) ViewThemeDispatcher.onId(convCoordId) { v ->
            // A sibling of the holder, so capturing it cannot recurse; re-armed each attach since recreation replaces it.
            (v as? ViewGroup)?.let { convCoordRef = WeakReference(it) }
            runCatching { syncConvToolbar() }
        }
        // Pinned messages and the rest of the strip share the coordinator's top edge with the floated toolbar.
        val convBannerId = res.waId("banner_container", pkg)
        if (convBannerId != 0) ViewThemeDispatcher.onId(convBannerId) { v ->
            (v as? ViewGroup)?.let { banner ->
                runCatching { clearConvBanner(banner) }
                    .onFailure { XposedBridge.log("[$TAG] clearConvBanner threw: $it") }
            }
        }
        if (convHolderId != 0) ViewThemeDispatcher.onId(convHolderId) { v ->
            val holder = v as? ViewGroup ?: return@onId
            convHolderRef = WeakReference(holder)
            runCatching { floatConvToolbar(holder) }
                .onFailure { XposedBridge.log("[$TAG] floatConvToolbar threw: $it") }
            // Band before pill: convPillAlpha asks whether a band exists, so the pill tints right on its first frame.
            runCatching { syncConvBand(holder) }
                .onFailure { XposedBridge.log("[$TAG] syncConvBand threw: $it") }
            runCatching { syncConvToolbar() }
                .onFailure { XposedBridge.log("[$TAG] syncConvToolbar threw: $it") }
            if (holder.getTag(convToolbarTag) == null) {
                holder.setTag(convToolbarTag, true)
                holder.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncConvBand(holder) }
                    runCatching { syncConvToolbar() }
                }
            }
        }
        if (convFooterId != 0) ViewThemeDispatcher.onId(convFooterId) { v ->
            val footer = v as? ViewGroup ?: return@onId
            // footer is generic; scoped by the compose row so no unrelated footer picks this up.
            if (footer.findViewById<View>(res.waId("input_layout", pkg)) == null) {
                return@onId
            }
            convFooterRef = WeakReference(footer)
            runCatching { floatConvFooter(footer) }
                .onFailure { XposedBridge.log("[$TAG] floatConvFooter threw: $it") }
            runCatching { syncConvFooter() }
                .onFailure { XposedBridge.log("[$TAG] syncConvFooter threw: $it") }
            if (footer.getTag(convFooterTag) == null) {
                footer.setTag(convFooterTag, true)
                footer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    runCatching { syncConvFooter() }
                }
            }
        }
        // ── The chat scroll and search discs ─────────────────────────────────────────────
        // All inflate from stubs that keep their ids, so the stub itself must be skipped.
        for (n in listOf(
            "scroll_bottom", "search_fab",
            "next_important_message", "ai_voice_entry_fab", "active_cart",
        )) {
            val fid = res.waId(n, pkg)
            if (fid == 0) continue
            ViewThemeDispatcher.onId(fid) { v ->
                if (v is ViewStub) return@onId
                runCatching { glassChatFab(v, n) }
                    .onFailure { XposedBridge.log("[$TAG] $n glass threw: $it") }
            }
        }
        // A bare ImageView, so the pane host has nowhere to go; the frost disc is the fallback.
        val aiRepliesId = res.waId("ai_replies", pkg)
        if (aiRepliesId != 0) ViewThemeDispatcher.onId(aiRepliesId) { v ->
            if (v is ViewStub) return@onId
            frostCircleOnLayout(v)
        }

        // ── The mention autocomplete panel ───────────────────────────────────────────────
        // A pane, not a film: the list under it must blur or the two text layers fight.
        val mentionAttachId = res.waId("mention_attach", pkg)
        if (mentionAttachId != 0) ViewThemeDispatcher.onId(mentionAttachId) { v ->
            val host = v as? FrameLayout ?: return@onId
            val apply = Runnable { runCatching { syncMentionPane(host) } }
            apply.run()
            if (host.getTag(frostListenerTag) == null) {
                host.setTag(frostListenerTag, true)
                host.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
            }
        }

        // ── Row-side pills: swipe hint, channel replies, quick forward, reaction counts ──
        // Hand-built rather than frost(): a lone-emoji reaction pill is as tall as wide and the aspect gate drops it.
        for (n in listOf(
            "swipe_to_reply_hint",
            "message_hint",
            "replies_pill_container_key",
            "newsletter_quick_forwarding_pill_container_key",
            "reactions_bubble_layout",
        )) {
            val pid2 = res.waId(n, pkg)
            if (pid2 != 0) ViewThemeDispatcher.onId(pid2) { v ->
                watchFrostPosition(v)
                val apply = Runnable {
                    runCatching {
                        if (v.height <= 0) return@runCatching
                        if (v.getTag(pillPadTag) == null) {
                            v.setTag(pillPadTag, true)
                            val ex = v.dp(3f).toInt()
                            val ey = v.dp(1.5f).toInt()
                            v.setPadding(
                                v.paddingLeft + ex, v.paddingTop + ey,
                                v.paddingRight + ex, v.paddingBottom + ey,
                            )
                        }
                        val existing = v.getTag(frostTag) as? FrostDrawable
                        if (existing != null && v.background === existing) {
                            existing.setRadius(v.height / 2f)
                            return@runCatching
                        }
                        // Blurred wallpaper as the fill: a film lets the bubble edge bleed through these.
                        val d = FrostDrawable(
                            v, { host -> wallpaperRecordOf(host) }, v.height / 2f, glassTint(CHIP_ALPHA),
                            strokeWidth = v.dp(1f), strokeColor = glassTint(CHIP_RIM_ALPHA),
                            ignorePadding = true,
                        )
                        v.setTag(frostTag, d)
                        v.background = d
                    }
                }
                apply.run()
                if (v.getTag(frostListenerTag) == null) {
                    v.setTag(frostListenerTag, true)
                    v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
                }
            }
        }

        // The forward picker's send bar, an opaque strip under the recipients.
        val pickerFooterId = res.waId("footer_container", pkg)
        val pickerShadowId = res.waId("shadow_top", pkg)
        if (pickerFooterId != 0) ViewThemeDispatcher.onId(pickerFooterId) { v ->
            if (activityOf(v)?.javaClass?.name?.contains(".picker.") != true) return@onId
            liquidFrostOnLayout(v, 20f, TINT_ALPHA)
            if (pickerShadowId != 0) {
                (v as? ViewGroup)?.findViewById<View>(pickerShadowId)?.let { s ->
                    if (s.visibility != View.INVISIBLE) s.visibility = View.INVISIBLE
                }
            }
        }

        // ── Calls tab leftovers ──────────────────────────────────────────────────────────
        // The joinable-call row keeps a static ripple, which ensureBgHook's colour swap cannot reach.
        val joinableRootId = res.waId("joinable_call_log_root_view", pkg)
        val callRowId = res.waId("call_row_container", pkg)
        if (joinableRootId != 0 && callRowId != 0) ViewThemeDispatcher.onId(callRowId) { v ->
            if (insideId(v, joinableRootId, 3)) liquidFrostOnLayout(v, 16f)
        }
        // Cleared to a hole by the shell clearer while the call-action discs beside it are frosted.
        val addFavId = res.waId("add_favorite_icon", pkg)
        if (addFavId != 0) ViewThemeDispatcher.onId(addFavId) { v -> frostCircleOnLayout(v) }
        // The call-link sheet's inner box has no id of its own; its sibling names the row.
        val callLinkId = res.waId("call_link", pkg)
        if (callLinkId != 0) ViewThemeDispatcher.onId(callLinkId) { v ->
            val box = (v.parent as? ViewGroup)?.let { p ->
                (0 until p.childCount).map { p.getChildAt(it) }.firstOrNull { c ->
                    c !is GlassView && c.id == View.NO_ID && c.background != null && c !is ViewGroup
                }
            } ?: return@onId
            liquidFrostOnLayout(box, 16f)
        }
        val linkIconId = res.waId("link_icon", pkg)
        if (linkIconId != 0) ViewThemeDispatcher.onId(linkIconId) { v -> frostCircleOnLayout(v) }

        // ── The balloons WhatsApp paints in code ─────────────────────────────────────────
        // All take their fill from the bubble resolver at bind time, so the frost re-applies per layout.
        for (n in listOf("conversation_row_date_divider", "date_divider_header")) {
            val did = res.waId(n, pkg)
            if (did != 0) ViewThemeDispatcher.onId(did) { v ->
                if (v is ViewStub) return@onId
                liquidFrostOnLayout(v, keepPadding = true)
            }
        }
        val tiBubbleId = res.waId("ti_bubble", pkg)
        if (tiBubbleId != 0) ViewThemeDispatcher.onId(tiBubbleId) { v ->
            // Only the bubble; the dots are a sibling drawable and must keep animating.
            liquidFrostOnLayout(v, keepPadding = true)
        }
        val unreadTvId = res.waId("unread_divider_tv", pkg)
        if (unreadTvId != 0) ViewThemeDispatcher.onId(unreadTvId) { v ->
            // The band is the parent's flat colour; the pill shape belongs on the text.
            (v.parent as? View)?.let { p -> if (p.background != null) clearBg(p, "unread band") }
            // Stock leaves this label unpadded and unbacked, so the pill needs the band's own 6dp back.
            padUnreadPill(v)
            liquidFrostOnLayout(v, keepPadding = true)
        }
        // info is one of the most reused ids in the app, hence the activity scope.
        val e2eInfoId = res.waId("info", pkg)
        if (e2eInfoId != 0) ViewThemeDispatcher.onId(e2eInfoId) { v ->
            if (activityOf(v)?.javaClass?.name?.endsWith(".Conversation") != true) return@onId
            liquidFrostOnLayout(v, 16f, keepPadding = true)
        }

        // With no bubble on the row this timestamp chip IS the surface; the 100-odd inline ones sit in a bubble already.
        bubblelessRootIds = intArrayOf(
            res.waId("sticker_root", pkg),
            res.waId("push_to_video_root", pkg),
        ).filter { it != 0 }.toIntArray()
        val dateWrapId = res.waId("date_wrapper", pkg)
        if (dateWrapId != 0 && bubblelessRootIds.isNotEmpty()) ViewThemeDispatcher.onId(dateWrapId) { v ->
            val pid = (v.parent as? View)?.id ?: return@onId
            if (!bubblelessRootIds.contains(pid)) return@onId
            // No clearBg first: the frost replaces the fill anyway, and the stock drawable still holds the inset.
            liquidFrostOnLayout(v, keepPadding = true)
        }

        // ── Search-in-chat ───────────────────────────────────────────────────────────────
        // Scoped to Conversation: the home search screen reuses this id with its own treatment.
        val convSearchRootId = res.waId("search_fragment", pkg)
        val convSearchBarId = res.waId("search_view_toolbar", pkg)
        if (convSearchRootId != 0) ViewThemeDispatcher.onId(convSearchRootId) { v ->
            if (activityOf(v)?.javaClass?.name?.endsWith(".Conversation") != true) return@onId
            clearBg(v, "conversation search root")
            // The bar itself gets the capsule pane from syncConvToolbar; only its fill goes here.
            if (convSearchBarId != 0) {
                (v as? ViewGroup)?.findViewById<View>(convSearchBarId)?.let { bar ->
                    clearBg(bar, "conversation search bar")
                }
            }
        }

        // Elevation zeroed: a translucent button casting a shadow reads as a smudge, not as raised.
        val sendContainerId = res.waId("send_container", pkg)
        if (sendContainerId != 0) ViewThemeDispatcher.onId(sendContainerId) { v ->
            if (v.elevation != 0f || v.translationZ != 0f) {
                v.stateListAnimator = null
                v.elevation = 0f
                v.translationZ = 0f
                logOnce("send button elevation zeroed")
            }
            // The fill is a RippleDrawable, so clearing it also costs the touch ripple; stands down for a user colour.
            if (!sendColored) clearBg(v, "send_container")
        }

        // On clear glass the stock glyph reads near-black, so it goes white; dictation_btn stays for the UNRESOLVED log.
        for (n in listOf("send", "voice_note_btn", "dictation_btn")) {
            val gid = res.waId(n, pkg)
            if (gid == 0) continue
            ViewThemeDispatcher.onId(gid) { v ->
                if (!sendIconColored) {
                    (v as? ImageView)?.imageTintList =
                        ColorStateList.valueOf(Color.WHITE)
                }
            }
        }

        // ── The follow button on the updates card ──────────────────────────────────────
        // A pane cannot go in (every capturable subtree is an ancestor), so it takes the chips' tint-only frost.
        // button_view is generic, so it is scoped to its container; Explore more and Create channel stay stock.
        val followContainerId = res.waId("quick_follow_button_container", pkg)
        val followBtnId = res.waId("button_view", pkg)
        if (followBtnId != 0 && followContainerId != 0) {
            ViewThemeDispatcher.onId(followBtnId) { v ->
                if ((v.parent as? View)?.id == followContainerId) frostOnLayout(v)
            }
        }

        // ── The add-status tile on the updates card ─────────────────────────────────────
        // Stock is an opaque slab and a pane cannot go in, so the chips' frost; 16dp, the tile is too tall for a pill.
        val statusTileId = res.waId("status_tile_layout", pkg)
        if (statusTileId != 0) ViewThemeDispatcher.onId(statusTileId) { v ->
            frostOnLayoutWith(v, glassTint(CHIP_ALPHA), v.dp(16f))
        }

        // ── The rest of the updates card's stock leftovers ──────────────────────────────
        // The share strip under the tiles; one id per Facebook/Instagram link state.
        for (n in listOf(
            "updates_contextual_migration_share_view",
            "updates_contextual_status_and_channel_share_view",
            "updates_contextual_status_and_channel_upsell",
        )) {
            val sid = res.waId(n, pkg)
            if (sid != 0) ViewThemeDispatcher.onId(sid) { v ->
                frostOnLayoutWith(v, glassTint(CHIP_ALPHA), v.dp(16f))
            }
        }
        // 12dp matches the slab it replaces.
        val adBannerId = res.waId("advertise_banner_container", pkg)
        if (adBannerId != 0) ViewThemeDispatcher.onId(adBannerId) { v ->
            frostOnLayoutWith(v, glassTint(CHIP_ALPHA), v.dp(12f))
        }
        // Muted tile and hidden disc: both ids are generic, so only the status tray's instances count.
        val statusTrayId = res.waId("status_list", pkg)
        val mutedTileId = res.waId("buttons_layout", pkg)
        val mutedSlabId = res.waId("status_preview", pkg)
        if (statusTrayId != 0 && mutedTileId != 0 && mutedSlabId != 0) {
            ViewThemeDispatcher.onId(mutedTileId) { v ->
                if (!insideId(v, statusTrayId, 4)) return@onId
                (v as? ViewGroup)?.findViewById<View>(mutedSlabId)
                    ?.let { frostOnLayoutWith(it, glassTint(CHIP_ALPHA), it.dp(16f)) }
            }
        }
        val hiddenDiscId = res.waId("contact_selector", pkg)
        if (statusTrayId != 0 && hiddenDiscId != 0) ViewThemeDispatcher.onId(hiddenDiscId) { v ->
            if (insideId(v, statusTrayId, 5)) frostCircleOnLayout(v)
        }
        // The megaphone card root carries the fill but no id; its action button names it.
        val megaBtnId = res.waId("megaphone_action_button", pkg)
        if (megaBtnId != 0) ViewThemeDispatcher.onId(megaBtnId) { v ->
            var anc: View? = v.parent as? View
            var hops = 0
            while (anc != null && hops < 4 && anc.background == null) { anc = anc.parent as? View; hops++ }
            anc?.takeIf { it.background != null }
                ?.let { frostOnLayoutWith(it, glassTint(CHIP_ALPHA), it.dp(16f)) }
        }
        // A transient full-width slab while a follow request runs; the card behind it is enough.
        val qfProgressId = res.waId("quick_follow_progressBar", pkg)
        if (qfProgressId != 0) ViewThemeDispatcher.onId(qfProgressId) { v ->
            (v.parent as? View)?.let { p -> if (p.background != null) clearBg(p, "quick follow strip") }
        }
        // WDSBanner paints its fill in code after attach, so the frost re-applies per layout.
        ViewThemeDispatcher.onView { v ->
            if (v.javaClass.name == "com.whatsapp.ui.wds.components.banners.WDSBanner") {
                // Full bounds: the banner's own padding is the gap, and drawing inside it leaves none.
                frostOnLayoutWith(v, glassTint(CHIP_ALPHA), v.dp(16f), ignorePadding = true)
            }
            false
        }
        // My Statuses ships the home FABs in a foreign window, out of the fab-pane machinery's reach; glassPageFab stands in.
        for (n in listOf("fab", "fab_second")) {
            val fid = res.waId(n, pkg)
            if (fid == 0) continue
            ViewThemeDispatcher.onId(fid) { v ->
                if (activityOf(v)?.javaClass?.name
                        ?.startsWith("com.whatsapp.status.playback.MyStatuses") != true
                ) return@onId
                val apply = Runnable { runCatching { glassPageFab(v) } }
                apply.run()
                if (v.getTag(frostListenerTag) == null) {
                    v.setTag(frostListenerTag, true)
                    v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> apply.run() }
                }
            }
        }

        // Status privacy parks viewport slack inside its stretched column; wrap and pin it so the card hugs real content.
        val spHeaderId = res.waId("see_my_status_header", pkg)
        if (spHeaderId != 0) ViewThemeDispatcher.onId(spHeaderId) { v ->
            val col = v.parent as? View ?: return@onId
            val lp = col.layoutParams ?: return@onId
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                runCatching { lp.javaClass.getField("verticalBias").setFloat(lp, 0f) }
                col.layoutParams = lp
            }
        }

        // ── Bottom sheets ──────────────────────────────────────────────────────────────
        // Material's own container, so this covers every sheet; sheets live in their own window, hence screen coords.
        val sheetId = res.waId("design_bottom_sheet", pkg)
        if (sheetId != 0) ViewThemeDispatcher.onId(sheetId) { v ->
            // On layout, not attach: a sheet attaches at 0x0, so an attach hook alone sees nothing.
            runCatching { injectSheetGlass(v) }
                .onFailure { XposedBridge.log("[$TAG] injectSheetGlass threw: $it") }
        }

        // ── Self-declared sheets ─────────────────────────────────────────────────────────
        // These carry BottomSheetBehavior themselves, so the design_bottom_sheet hook never sees them.
        // Deliberately stock: expressions tray, the call popups, the two webview sheets; the status viewer's details sheet gets tint-only frost.
        for (n in listOf(
            "bottom_sheet",
            "audio_chat_bottom_sheet",
            "pre_call_sheet_content",
            "psa_bottom_sheet_root",
            "virality_bottom_sheet",
            "list_holder",
            "selected_list_holder",
        )) {
            val selfSheetId = res.waId(n, pkg)
            if (selfSheetId == 0) continue
            ViewThemeDispatcher.onId(selfSheetId) { v ->
                runCatching { glassSelfSheet(v, n) }
                    .onFailure { XposedBridge.log("[$TAG] self sheet $n threw: $it") }
            }
        }

        // ── Sheet contents that paint OVER the wrapper's glass ──────────────────────────
        // These roots paint their own fill above the cleared wrapper, and no shell clearing reaches a dialog window.
        // This id roots a full-screen page as well as the sheet, and only the sheet is a card.
        val galleryPickerId = res.waId("gallery_picker_layout", pkg)
        if (galleryPickerId != 0) ViewThemeDispatcher.onId(galleryPickerId) { v ->
            if (!isMediaPickerSheet(v)) return@onId
            liquidFrostOnLayout(v, CARD_RADIUS_DP, TINT_ALPHA)
            // The grid reaches the edges, so uncontained it draws across the sheet's top corners.
            if (!v.clipToOutline) {
                v.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val r = view.dp(CARD_RADIUS_DP)
                        // Bottom carried past the edge: a sheet rounds its top corners only.
                        outline.setRoundRect(0, 0, view.width, view.height + r.toInt(), r)
                    }
                }
                v.clipToOutline = true
            }
        }
        for (n in listOf(
            "sticker_pack_preview_bottom_sheet_layout",
            "view_replies_bottom_sheet",
            "answering_keyboard_popup",
            "history_drawer_content",
        )) {
            val sid3 = res.waId(n, pkg)
            if (sid3 != 0) ViewThemeDispatcher.onId(sid3) { v ->
                liquidFrostOnLayout(v, CARD_RADIUS_DP, TINT_ALPHA)
            }
        }

        // ── The small overlay windows ────────────────────────────────────────────────────
        // Toasts and tooltips live in their own windows, so only their own view can carry glass.
        for (n in listOf("toast_layout", "card_container", "skin_tone_selector")) {
            val tid = res.waId(n, pkg)
            if (tid != 0) ViewThemeDispatcher.onId(tid) { v ->
                liquidFrostOnLayout(v, 16f)
            }
        }
        for (n in listOf("tooltip_text", "ai_voice_tooltip_container")) {
            val tid2 = res.waId(n, pkg)
            if (tid2 != 0) ViewThemeDispatcher.onId(tid2) { v -> liquidFrostOnLayout(v) }
        }
        // Raw Dialogs: no parentPanel, so panelGlass never sees them.
        for (n in listOf("permission_request_dialog", "nag_text", "recipients_container")) {
            val did2 = res.waId(n, pkg)
            if (did2 != 0) ViewThemeDispatcher.onId(did2) { v ->
                liquidFrostOnLayout(v, CARD_RADIUS_DP, TINT_ALPHA)
            }
        }

        // ── Inline opaque panels ───────────────────────────────────────────────────────
        // parentPanel is AppCompat's dialog container, so this reaches every AlertDialog in the app.
        // The expressions tray stays stock (a legible grid needs its fill); the editors' body is in because the catchall strips its fill.
        for (n in listOf("media_picker_popup_content", "parentPanel", "emoji_edit_text_layout")) {
            val pid = res.waId(n, pkg)
            if (pid == 0) continue
            ViewThemeDispatcher.onId(pid) { v ->
                runCatching { panelGlass(v, n) }
                    .onFailure { XposedBridge.log("[$TAG] panelGlass($n) threw: $it") }
            }
        }
        // Its own opaque bar would sit as a slab on that glass; the panel behind is the surface.
        val emojiBarId = res.waId("emoji_edit_text_toolbar", pkg)
        if (emojiBarId != 0) ViewThemeDispatcher.onId(emojiBarId) { v ->
            clearBg(v, "emoji_edit_text_toolbar")
            // A drawable, never a pane: this coordinator orders touch by child index; the appbar has no id.
            val holder = (v.parent as? View)
                ?.takeIf { it.javaClass.name.contains("AppBarLayout") } ?: v
            // Forced on the bar itself: it is kept cleared above, and a plain frost there is swapped straight back.
            runCatching {
                liquidFrostOnLayout(
                    holder, CARD_RADIUS_DP, TINT_ALPHA,
                    forceLabel = if (holder === v) "emoji_edit_text_toolbar" else null,
                )
            }.onFailure { logOnce("emoji edit toolbar frost threw: $it") }
        }
        // The tray draws no fill of its own, so in a dialog it shows the window behind straight through.
        val trayViewId = res.waId("expressions_tray_view_id", pkg)
        val emojiCoordId = res.waId("emoji_edit_text_coordinator", pkg)
        if (trayViewId != 0 && emojiCoordId != 0) ViewThemeDispatcher.onId(trayViewId) { v ->
            // Editors only: in a chat the tray sits on the compose surface, where a legible grid keeps its own fill.
            if (v.rootView?.findViewById<View>(emojiCoordId) == null) return@onId
            runCatching { liquidFrostOnLayout(v, CARD_RADIUS_DP, TINT_ALPHA) }
                .onFailure { logOnce("expressions tray frost threw: $it") }
        }

        // ── The reactions tray ─────────────────────────────────────────────────────────
        // It rides three transform AnimatorSets, so the glass is a background drawable, never a pane.
        val trayId = res.waId("reactions_tray_layout", pkg)
        if (trayId != 0) ViewThemeDispatcher.onId(trayId) { v ->
            runCatching { frostReactionsTray(v) }
                .onFailure { XposedBridge.log("[$TAG] reactions tray frost threw: $it") }
        }

        // ── The settings cover ─────────────────────────────────────────────────────────
        // me_tab_cover_photo is the flat band behind the profile header; clearing it lets the wallpaper run full height.
        val coverId = res.waId("me_tab_cover_photo", pkg)
        if (coverId != 0) ViewThemeDispatcher.onId(coverId) { v -> clearBg(v, "me_tab_cover_photo") }

        // ── The call screen ────────────────────────────────────────────────────────────
        // Audio calls only: video renders into a Surface no capture can see, so the glass stands down live.
        val callRootId = res.waId("call_screen_root", pkg)
        if (callRootId != 0) ViewThemeDispatcher.onId(callRootId) { v ->
            runCatching { callScreenGlass(v as? ViewGroup ?: return@onId) }
                .onFailure { XposedBridge.log("[$TAG] call screen glass threw: $it") }
        }
        // WA re-inflates and re-shows its own call backdrop after connect; the wallpaper wins only if this loses every time.
        val callBgId = res.waId("call_background", pkg)
        if (callBgId != 0) ViewThemeDispatcher.onId(callBgId) { v ->
            if (v.rootView?.findViewWithTag<View>("wt_wallpaper") != null) v.visibility = View.INVISIBLE
        }
        // The card can rebuild without the root re-attaching; its own registration keeps the refs live.
        val callCardId = res.waId("call_controls_card", pkg)
        if (callCardId != 0) ViewThemeDispatcher.onId(callCardId) { v ->
            runCatching { callCardGlass(v as? ViewGroup ?: return@onId) }
                .onFailure { XposedBridge.log("[$TAG] call card glass threw: $it") }
        }
        // The More rows carry their own opaque fills over the pane; the label's attach is the inflate signal.
        val noiseLabelId = res.waId("noise_cancellation_label", pkg)
        if (noiseLabelId != 0) ViewThemeDispatcher.onId(noiseLabelId) { v ->
            runCatching { callMoreMenuGlass(v) }
                .onFailure { XposedBridge.log("[$TAG] call more menu threw: $it") }
        }
        // The header circles are WaImageButtons, not WDS; their fill is their own background drawable.
        for (n in listOf("minimize_btn", "participant_btn", "network_health_btn", "security_btn", "send_message_btn")) {
            val bid = res.waId(n, pkg)
            if (bid == 0) continue
            ViewThemeDispatcher.onId(bid) { v ->
                if (v.rootView?.findViewWithTag<View>("wt_wallpaper") == null) return@onId
                runCatching { tintWdsShape(v.background, glassTint(CHIP_ALPHA)) }
            }
        }

        // ── Overflow / dropdown menus ──────────────────────────────────────────────────
        // content is each menu row's id and the only handle a popup exposes; its fill lives in another window.
        menuRowId = res.waId("content", pkg)
        menuTitleId = res.waId("menu_title", pkg)
        menuSelRowId = res.waId("message_selection_drop_down_row_text", pkg)
        ensurePopupGlass()

        // ── Attachment tile pills ──────────────────────────────────────────────────────
        // The pill is the icon's own background; scoped through the holders because icon is one of the most reused ids.
        val attachIconId = res.waId("icon", pkg)
        if (attachIconId != 0) for (n in listOf(
            "pickfiletype_gallery_holder", "pickfiletype_location_holder",
            "pickfiletype_contact_holder", "pickfiletype_document_holder",
            "pickfiletype_poll_holder", "pickfiletype_payment_holder",
            "pickfiletype_event_holder", "pickfiletype_imagine_sheet_holder",
            "pickfiletype_camera_holder", "pickfiletype_audio_holder",
            "pickfiletype_music_holder", "pickfiletype_quiz_holder",
            "pickfiletype_question_holder", "pickfiletype_group_status_holder",
            "pickfiletype_pix_holder", "pickfiletype_remittance_holder",
            "pickfiletype_call_link",
        )) {
            val hid = res.waId(n, pkg)
            if (hid == 0) continue
            ViewThemeDispatcher.onId(hid) { holder ->
                val icon = holder.findViewById<View>(attachIconId) ?: return@onId
                // On layout: at attach the icon is 0x0 and frost refuses it. Registration guarded, the frost is not.
                if (icon.getTag(tilePillTag) == null) {
                    icon.setTag(tilePillTag, true)
                    icon.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                        runCatching { frost(v, allowSquare = true, ignorePadding = true) }
                    }
                }
                // ignorePadding is the pill-vs-lozenge difference: an icon button's padding is glyph space, not chip inset.
                icon.post { runCatching { frost(icon, allowSquare = true, ignorePadding = true) } }
            }
        }

        // One component, three ids; a stub's inflatedId is its own, so the usual id misses those pages.
        for (n in listOf("wds_search_bar", "search_bar", "persistent_search_bar")) {
            val barId = res.waId(n, pkg)
            if (barId == 0) continue
            ViewThemeDispatcher.onId(barId) { v ->
                // The stub fires this id too, and it is not the bar.
                val bar = v as? FrameLayout ?: return@onId
                armWdsSearchBar(bar, n)
            }
        }

        // The AppCompat SearchView gets its own path: it declines hosts that cannot carry a pane rather than clearing a fill it cannot replace.
        // search_bar_layout too: where search_view sits in a LinearLayout, that layout's parent can carry the pane.
        for (sn in listOf("search_view", "search_bar_layout")) {
        val searchViewId = res.waId(sn, pkg)
        if (searchViewId != 0) ViewThemeDispatcher.onId(searchViewId) { v ->
            if (v.getTag(wdsBarTag) == null) {
                v.setTag(wdsBarTag, true)
                v.viewTreeObserver.addOnGlobalLayoutListener {
                    runCatching { syncSearchViewGlass(v) }
                }
            }
            runCatching { syncSearchViewGlass(v) }
                .onFailure { logOnce("search view glass threw on $sn: $it") }
        }
        }

        // The band under the photo: its fill is a FOREGROUND, it has no id, so its parent reaches it.
        val photoSectionId = res.waId("me_tab_profile_info_photo_section", pkg)
        if (photoSectionId != 0) ViewThemeDispatcher.onId(photoSectionId) { v ->
            val section = v as? ViewGroup ?: return@onId
            val strip = Runnable {
                for (i in 0 until section.childCount) {
                    val c = section.getChildAt(i) ?: continue
                    if (c is ViewGroup || c is ViewStub) continue
                    if (c.foreground != null) {
                        c.foreground = null
                        logOnce("me tab photo band foreground cleared")
                    }
                }
            }
            strip.run()
            if (section.getTag(frostHostListenerTag) == null) {
                section.setTag(frostHostListenerTag, true)
                section.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> strip.run() }
            }
        }
        val meTabId = res.waId("me_tab_container", pkg)
        if (meTabId != 0) ViewThemeDispatcher.onId(meTabId) { v ->
            runCatching { ensureMeTabCard(v) }
                .onFailure { logOnce("me tab card threw: $it") }
        }

        val searchInnerId = res.waId("search_bar_inner_layout", pkg)
        if (searchInnerId != 0) ViewThemeDispatcher.onId(searchInnerId) { v ->
            clearBg(v, "search_bar_inner_layout")
            // clearBg always runs, so a failed pane means an invisible search bar; log it or there is nothing to go on.
            runCatching { injectSearchFieldGlass(v) }
                .onFailure { logOnce("search field glass FAILED, bar is now transparent with no pane: $it") }
            // Line its sides up with the two strips rather than WhatsApp's own inset.
            (v.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val side = (16 * v.resources.displayMetrics.density).toInt()
                if (lp.leftMargin != side || lp.rightMargin != side) {
                    lp.leftMargin = side
                    lp.rightMargin = side
                    lp.marginStart = side
                    lp.marginEnd = side
                    v.layoutParams = lp
                }
            }
        }

        if (toolbarId != 0) ViewThemeDispatcher.onId(toolbarId) { v ->
            toolbarRef = WeakReference(v)
            // Also driven from here: the channel screens are a different Activity, so id/content's listener never fires there.
            syncToolbarTitle()
            if (v.getTag(toolbarTitleTag) == null) {
                v.setTag(toolbarTitleTag, true)
                v.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> syncToolbarTitle() }
            }
            // No pane behind the toolbar; cleared so the blurred wallpaper itself is the frost.
            clearBg(v, "toolbar")
            // Pad the toolbar's end or the pane clips at the screen edge; 8dp, tighter than the free-floating surfaces.
            val side = (8 * v.resources.displayMetrics.density).toInt()
            if (v.paddingEnd != side) {
                v.setPaddingRelative(v.paddingStart, v.paddingTop, side, v.paddingBottom)
            }
        }
        if (toolbarContainerId != 0) ViewThemeDispatcher.onId(toolbarContainerId) { v ->
            clearBg(v, "toolbar_container")
        }
        // Keeps the toolbar and nav see-through when WhatsApp re-applies fills; forcedBg decides which views, not id.
        ensureBgHook()

        // ── Bottom nav: float it as a pill with content passing behind ─────────────────
        // Floated by a negative top margin: the weighted id/content absorbs the space and grows underneath the pill.
        if (navContainerId != 0) ViewThemeDispatcher.onId(navContainerId) { v ->
            runCatching { floatNav(v) }.onFailure { XposedBridge.log("[$TAG] floatNav threw: $it") }
        }
        // No pane of ours: the nav is themeable. Round both views, they share bounds and the square one shows through.
        for (n in listOf("bottom_nav", "bottom_nav_container")) {
            val nid = res.waId(n, pkg)
            if (nid == 0) continue
            ViewThemeDispatcher.onId(nid) { v ->
                if (n == "bottom_nav") navBarRef = WeakReference(v)
                roundView(v, n)
                // The outline provider reads view.height, so invalidate on resize; registration guarded, roundView is not.
                if (v.getTag(navRoundListenerTag) == null) {
                    v.setTag(navRoundListenerTag, true)
                    v.addOnLayoutChangeListener { view, _, t, _, b, _, _, _, _ ->
                        if (b - t > 0) view.invalidateOutline()
                    }
                }
            }
        }
        // ── The active tab pill ──────────────────────────────────────────────────────────
        // The chips' treatment, never a pane: Material animates the pill by transform, the reaction bar's killer.
        val pillId = res.waId("navigation_bar_item_active_indicator_view", pkg)
        if (pillId != 0 && !tokenTabPillSet) {
            // Forced: the menu view re-applies its own indicator drawable on every refresh, and that fires no layout.
            ViewThemeDispatcher.onId(pillId) { v -> frostOnLayout(v, forceLabel = "active tab pill") }
        }

        // A seam across the pill; hide it INVISIBLE so the nav's height does not change under us.
        val dividerId = res.waId("bottom_nav_divider", pkg)
        if (dividerId != 0) ViewThemeDispatcher.onId(dividerId) { v ->
            if (v.visibility != View.INVISIBLE) v.visibility = View.INVISIBLE
        }
        // Not per-view attach callbacks: fab's onId never fires again after a recreation, so id/content's layout drives it.
        fabIds = listOf("fab", "extended_mini_fab", "fab_second")
            .map { res.waId(it, pkg) }
            .filter { it != 0 }
            .toIntArray()
        runCatching {
            val p = ModulePrefs.open()
            p.reload()
            fabColored = p.getInt(Prefs.OVR_FAB_BG, 0) != 0
            miniFabColored = p.getInt(Prefs.OVR_MINI_FAB_BG, 0) != 0
            sendColored = p.getInt(Prefs.COMPOSE_SEND_BG, 0) != 0
            sendIconColored = p.getInt(Prefs.COMPOSE_SEND_ICON, 0) != 0
            quoteColored = p.getInt(Prefs.QUOTE_BG_COLOR, 0) != 0
            // Either toolbar token stands us down: the icon sweep cannot tell which toolbar it is looking at.
            toolbarIconsColored =
                p.getInt(Prefs.OVR_TOOLBAR_ICONS, 0) != 0 ||
                p.getInt(Prefs.CHAT_TOOLBAR_ICONS, 0) != 0
        }.onFailure { XposedBridge.log("[$TAG] fab colour prefs unreadable: $it") }

        ViewThemeDispatcher.onId(headerId) { header ->
            headerRef = WeakReference(header)
            // Re-bind the pane to each new header or it stays tied to a dead one and the pill vanishes at random.
            actionsGlassRef?.get()?.let { g -> bindPane(g, header, "toolbar actions") }
            registerFadeOnHide(header)
            runCatching { injectToolbarGlass(header, pagerHolderId) }
                .onFailure { XposedBridge.log("[$TAG] injectToolbarGlass threw: $it") }
            // Contact info reuses this id; infoHeaderGlass tells the two apart by the collapsing photo.
            runCatching { infoHeaderGlass(header) }
                .onFailure { XposedBridge.log("[$TAG] infoHeaderGlass threw: $it") }
        }

        // ── Home -> conversation: the open transition and the row ids ───────────────────
        rowContainerId = res.waId("contact_row_container", pkg)
        callRowContainerId = res.waId("call_row_container", pkg)
        ensureChatOpenTransition()
        // Row selection rides ensureBgHook (see rowSelectionPane); it must exist before the first row binds.
        if (rowContainerId != 0 || callRowContainerId != 0) ensureBgHook()

        // ── The search screen ──────────────────────────────────────────────────────────
        searchInputId = res.waId("search_input", pkg)
        searchResultListId = res.waId("result_list", pkg)
        // ── A pane per search result ──────────────────────────────────────────────────
        // One pane stamping a rect per row, so cost does not grow with results; an empty query yields no rects at all.
        if (searchResultListId != 0) ViewThemeDispatcher.onId(searchResultListId) { v ->
            runCatching { searchRowGlass(v) }
                .onFailure { XposedBridge.log("[$TAG] searchRowGlass threw: $it") }
        }
        // divider is generic: only ever resolved inside the search fragment's subtree, never against the window.
        searchDividerId = res.waId("divider", pkg)
        val searchFragmentId = res.waId("search_fragment", pkg)
        if (searchFragmentId != 0) ViewThemeDispatcher.onId(searchFragmentId) { v ->
            runCatching { layoutSearchScreen(v) }
                .onFailure { XposedBridge.log("[$TAG] layoutSearchScreen threw: $it") }
        }

        // ── Contact info: five groups that look like one wall of text ──────────────────
        // Gives back the grouping the transparency pass erased; keyed on the *_details_card ids, one per info screen.
        for (n in listOf(
            "contact_details_card",
            "group_details_card",
            "newsletter_details_card",
            "business_details_card",
        )) {
            val id = res.waId(n, pkg)
            if (id == 0) continue
            ViewThemeDispatcher.onId(id) { v ->
                runCatching { infoCardGlass(v) }
                    .onFailure { XposedBridge.log("[$TAG] infoCardGlass threw: $it") }
            }
        }
        infoHeaderPlaceholderId = res.waId("header_placeholder", pkg)
        infoParticipantsId = res.waId("participants_card", pkg)
        infoMemberSheetId = res.waId("group_participants_search", pkg)
        if (infoMemberSheetId != 0) ViewThemeDispatcher.onId(infoMemberSheetId) { v ->
            runCatching { memberSheetPane(v) }
                .onFailure { XposedBridge.log("[$TAG] memberSheetPane threw: $it") }
        }

        infoCollapsingPhotoId = res.waId("collapsing_profile_photo_view", pkg)
        infoPictureId = res.waId("picture", pkg)
        infoPhotoOverlayId = res.waId("photo_overlay", pkg)
        infoTitleId = res.waId("contact_title", pkg)
        infoSubtitleId = res.waId("contact_subtitle", pkg)
        infoGroupTitleId = res.waId("group_title", pkg)
        infoGroupSubtitleId = res.waId("group_details_card_subtitle", pkg)

        // The info tiles ride the action_tile_icon registration above; a control on glass must be brighter than the glass.

        // Rounded, not inset: the strip is deliberately edge-to-edge. Re-run on layout as the thumbs arrive.
        val thumbsId = res.waId("media_card_thumbs", pkg)
        if (thumbsId != 0) ViewThemeDispatcher.onId(thumbsId) { host ->
            val hv = host as? ViewGroup ?: return@onId
            val round = Runnable {
                for (i in 0 until hv.childCount) {
                    val thumb = hv.getChildAt(i) ?: continue
                    runCatching { roundInnerSurface(thumb, "media card thumb") }
                }
            }
            if (hv.getTag(frostHostListenerTag) == null) {
                hv.setTag(frostHostListenerTag, true)
                hv.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> round.run() }
            }
            hv.post(round)
        }

        // ── Square corners inside a rounded bubble ─────────────────────────────────────
        // Clipped, not re-backgrounded: an outline clip touches no drawable, so it survives every recolour.
        for (n in listOf(
            "quoted_message_holder",       // the reply block inside a bubble
            "media_container_wrapper",     // single image / video
            "media_container",
            "picture_frame",
        )) {
            val qid = res.waId(n, pkg)
            if (qid == 0) continue
            ViewThemeDispatcher.onId(qid) { v -> roundInnerSurface(v, n) }
        }

        runCatching { ensureToolbarDividerHook(app) }
            .onFailure { XposedBridge.log("[$TAG] ensureToolbarDividerHook threw: $it") }
        runCatching { installBubbleGlass(app) }
            .onFailure { XposedBridge.log("[$TAG] installBubbleGlass threw: $it") }

        // WDSButtons rebuild their fill from the variant per style pass and swallow external backgrounds; retint inside their own path.
        runCatching {
            // The live layout is the _v2 family; the older variant's ids ride along in case a build flips back.
            draftBtnIds = listOf(
                "voice_note_draft_stop_btn_v2",
                "voice_note_cancel_btn_v2",
                "voice_note_draft_pause_resume_btn",
                "voice_note_draft_delete_btn",
                "draft_send_v2",
                "voice_note_draft_send_btn",
            ).map { app.resources.waId(it, app.packageName) }.filter { it != 0 }.toIntArray()
            // The send glyph goes white; stock's black arrow disappears into the glass fill.
            draftSendBtnIds = listOf("draft_send_v2", "voice_note_draft_send_btn")
                .map { app.resources.waId(it, app.packageName) }.filter { it != 0 }.toIntArray()
            // The call screen's circular WDSButtons take the same chip frost; End stays red on purpose.
            callBtnIds = listOf(
                "audio_route_button", "camera_button", "mute_button", "more_button",
                "screen_sharing_button", "minimize_btn", "participant_btn",
                "calling_camera_switch_wds_button", "calling_effects_wds_button",
                "network_health_btn", "security_btn", "send_message_btn",
            ).map { app.resources.waId(it, app.packageName) }.filter { it != 0 }.toIntArray()
            val cls = WaIds.clazz(
                app.classLoader, "com.whatsapp.ui.wds.components.button.WDSButton", "draft button frost",
            )
            if (cls != null && (draftBtnIds.isNotEmpty() || callBtnIds.isNotEmpty())) {
                XposedHelpers.findAndHookMethod(
                    cls, "setupBackgroundStyle",
                    ColorStateList::class.java,
                    ColorStateList::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (!armed) return
                            val v = param.thisObject as? View ?: return
                            if (v.id == 0 || (v.id !in draftBtnIds && v.id !in callBtnIds)) return
                            if (runCatching { tintWdsShape(v.background, glassTint(CHIP_ALPHA)) }.getOrDefault(false)) {
                                logOnce("WDS buttons frosted through the style path")
                            }
                        }
                    },
                )
                XposedHelpers.findAndHookMethod(
                    cls, "setupContentStyle",
                    ColorStateList::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!armed) return
                            val v = param.thisObject as? View ?: return
                            if (v.id == 0 || v.id !in draftSendBtnIds) return
                            // The widget derives paint colour and icon filter from this list; white rides its own path.
                            param.args[0] = draftWhiteContent
                        }
                    },
                )
            }
        }

        // The nav count badge is Material's BadgeDrawable in the tab's OVERLAY, invisible to every dump; caught at attach.
        runCatching {
            XposedHelpers.findAndHookMethod(
                ViewOverlay::class.java, "add",
                Drawable::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val d = param.args[0] as? Drawable ?: return
                        runCatching { themeNavBadge(d) }
                    }
                },
            )
        }

        armed = true
        XposedBridge.log("[$TAG] armed (header=$headerId pagerHolder=$pagerHolderId)")
        HookLog.arm("glass/engine", "blur=${BLUR_DP}dp tint=$TINT_ALPHA radius=${CARD_RADIUS_DP}dp")
        // A global wallpaper decoded before this point waited here; the wallpaper installer runs first.
        pendingGlobal?.let { resolveGlassTintFrom(it, pendingGlobalDim) }
        pendingGlobal = null
    }

    private var pendingGlobal: Bitmap? = null
    private var pendingGlobalDim = 0f

    /** The global wallpaper's bitmap as soon as the cache has it; any thread. Resolves the tint before any pane bakes it. */
    fun noteGlobalWallpaper(bitmap: Bitmap, dim: Float) {
        Handler(Looper.getMainLooper()).post {
            if (armed) resolveGlassTintFrom(bitmap, dim) else { pendingGlobal = bitmap; pendingGlobalDim = dim }
        }
    }

    /** Invalidates every frosted view under [decor]; a swapped wallpaper reaches pills that already recorded. */
    private fun invalidateFrosts(decor: View) {
        fun walk(v: View) {
            if (v.getTag(frostTag) != null) v.invalidate()
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i) ?: continue)
        }
        runCatching { walk(decor) }
    }


    /** The unread badges' glass fill for HomeActivityHook; 0 stands the glass down. */
    fun badgeGlassFill(): Int = if (armed) glassTint(CHIP_ALPHA) else 0

    /** The digit over the frost; white for legibility on the translucent fill. */
    fun badgeGlassText(): Int = if (armed) 0xFFFFFFFF.toInt() else 0


    /** WallpaperImage calls this at inject: the tags the draw path reads, and the tint resolve for the global wallpaper only. */
    fun noteWallpaperInjected(decor: View, image: ImageView, dim: View?, look: WallpaperLook) {
        image.setTag(wallpaperLookTag, look)
        noteWallpaperViews(decor, image, dim)
        if (!armed) return
        if (look.global) resolveGlassTint(listOfNotNull(image, dim))
    }

    /** WallpaperImage calls this after swapping a window's image in place; the record rebuilds on its next read. */
    fun noteWallpaperSwapped(decor: View, image: ImageView, dim: View?, look: WallpaperLook) {
        image.setTag(wallpaperLookTag, look)
        noteWallpaperViews(decor, image, dim)
        if (!armed) return
        markWallpaperGeometryDirty()
        invalidateFrosts(decor)
    }


    // status.playback and group.product are named per page, the rest of those packages must stay bare; the dialer has no scroller to card.
    /** Manifest names never obfuscate; every non-sheet page under these takes the content card and folder header. */
    private val CARDED_ACTIVITY_PREFIXES = listOf(
        "com.whatsapp.settings.",
        "com.whatsapp.payments.",
        "com.whatsapp.catalog.",
        "com.whatsapp.status.audienceselector.",
        "com.whatsapp.status.updates.",
        "com.whatsapp.status.playback.MyStatuses",
        "com.whatsapp.status.playback.MyStatusAudience",
        "com.whatsapp.status.playback.ArchivedStatuses",
        "com.whatsapp.status.playback.audience.",
        "com.whatsapp.status.playback.newsletterstatus.",
        "com.whatsapp.conversation.conversationrow.message.MessageDetails",
        "com.whatsapp.conversation.conversationrow.message.KeptMessages",
        "com.whatsapp.dmsetting.",
        "com.whatsapp.ephemeral.",
        "com.whatsapp.group.product.GroupPermissions",
        "com.whatsapp.group.product.GroupMembersSelector",
        "com.whatsapp.group.product.newgroup.",
        "com.whatsapp.contact.ui.picker.AddGroupParticipantsSelector",
        "com.whatsapp.contact.ui.picker.BroadcastListMembersSelector",
        "com.whatsapp.chatinfo.addtogroups.",
        "com.whatsapp.xfamily.groups.ui.GroupMembersSelector",
        "com.whatsapp.calling.ui.callhistory.group.GroupCallParticipantPicker",
        "com.whatsapp.calling.ui.calllink.",
        "com.whatsapp.contactshub.",
        // Settings-reachable pages living outside com.whatsapp.settings.
        "com.whatsapp.aura.",
        "com.whatsapp.lists.",
        "com.whatsapp.chatlock.",
        "com.whatsapp.twofactor.",
        "com.whatsapp.backup.",
        "com.whatsapp.storage.",
        "com.whatsapp.blocklist.",
        "com.whatsapp.profile.ui.",
        "com.whatsapp.authentication.",
        "com.whatsapp.lastseen.",
        "com.whatsapp.privacy.",
        "com.whatsapp.integrityai.",
        "com.whatsapp.privateai.",
        "com.whatsapp.migration.transfer.",
        "com.whatsapp.favorites.",
    )


    /* ── Contact info's collapsing header ───────────────────────────────────────────────── */


    /* ── Contact info's section cards ───────────────────────────────────────────────────── */


    /** Draft button ids, resolved at install; the WDSButton hook compares against them per style pass. */
    private var draftBtnIds = IntArray(0)
    private var draftSendBtnIds = IntArray(0)

    /** White content for the draft send button; the widget's own path turns it into paint colour and icon filter. */
    private val draftWhiteContent: ColorStateList by lazy {
        ColorStateList.valueOf(0xFFFFFFFF.toInt())
    }


    // Never add ConstraintLayout to canStack: an unconstrained index-0 child breaks the Broadcast page's + FAB.


    // ── The conversation screen ────────────────────────────────────────────────────────
    // Chrome only: pills behind the toolbar and compose row; bubbles are not panes, installBubbleGlass reskins them.


    // ── Chat bubbles as glass ──────────────────────────────────────────────────────────
    // The one surface that cannot be a pane: rows carry no ids and nothing hosts a child; see GlassBubbleDrawable.


    /* Bubbles take the panes' tint. */


    /* ── A selected chat row ────────────────────────────────────────────────────────────── */


    /* ── A selected message row, measurements ──────────────────────────────────────────── */


    /* ── The call screen ────────────────────────────────────────────────────────────────── */


    /* ── The contact picker ─────────────────────────────────────────────────────────────── */


    /* ── The voice-recording lock pill ──────────────────────────────────────────────────── */


    /* ── A selected message row ─────────────────────────────────────────────────────────── */


    // The corner arcs on the call-info header are the pane's own specular rim, not an elevation shadow.
    // If they are ever unwanted the levers are specStrength/specPower/light1/light2, not elevation.


    // ══ The search screen ══════════════════════════════════════════════════════════════════
    // A vertical LinearLayout swapped into id/content: a toolbar margin reflows everything, so the panel lives in id/content via [bindPane].


    // Fade views are registered where they are resolved, never lazily in the search path, which races the event itself.
    // The FABs are deliberately not in this set: deferring their everyday hides by 200ms feels sticky.


    private var chatOpenHooked = false

    private fun ensureChatOpenTransition() {
        if (chatOpenHooked) return
        chatOpenHooked = true
        runCatching {
            // Framework fade ids; our own anim resources cannot resolve in WhatsApp's process.
            // No shared elements by design; requestFeature changes the whole process's window animations, so the removal is total.
            XposedHelpers.findAndHookMethod(
                Activity::class.java, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val a = param.thisObject as? Activity ?: return
                        if (a.javaClass.name != "com.whatsapp.Conversation") return
                        if (Build.VERSION.SDK_INT < 34) return
                        runCatching {
                            a.overrideActivityTransition(
                                Activity.OVERRIDE_TRANSITION_OPEN,
                                android.R.anim.fade_in, android.R.anim.fade_out,
                            )
                            a.overrideActivityTransition(
                                Activity.OVERRIDE_TRANSITION_CLOSE,
                                android.R.anim.fade_in, android.R.anim.fade_out,
                            )
                        }.onFailure { XposedBridge.log("[$TAG] chat fade refused: $it") }
                    }
                },
            )
            // API 31-33 have no overrideActivityTransition, so ask the old way around each edge.
            if (Build.VERSION.SDK_INT < 34) {
                XposedHelpers.findAndHookMethod(
                    Activity::class.java, "startActivityForResult",
                    Intent::class.java, Int::class.javaPrimitiveType,
                    Bundle::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val i = param.args[0] as? Intent ?: return
                            if (i.component?.className != "com.whatsapp.Conversation") return
                            val a = param.thisObject as? Activity ?: return
                            @Suppress("DEPRECATION")
                            runCatching {
                                a.overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                            }
                        }
                    },
                )
                XposedHelpers.findAndHookMethod(
                    Activity::class.java, "finish",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val a = param.thisObject as? Activity ?: return
                            if (a.javaClass.name != "com.whatsapp.Conversation") return
                            @Suppress("DEPRECATION")
                            runCatching {
                                a.overridePendingTransition(
                                    android.R.anim.fade_in, android.R.anim.fade_out,
                                )
                            }
                        }
                    },
                )
            }
            XposedBridge.log("[$TAG] chat transitions armed (fade both ways)")
        }.onFailure { XposedBridge.log("[$TAG] chat-open transition FAILED: $it") }
    }


}
