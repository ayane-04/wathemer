// Global font swap. Layer 1 patches sSystemFontMap and the boot-snapshotted statics; layer 2 re-stamps setTypeface.
// sSystemFontMap is greylisted; LSPosed relaxes enforcement, but gate via HiddenApiBypass if throttling appears.
package com.wathemer.app.hooks

import android.app.Application
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.util.Log
import android.widget.TextView
import com.wathemer.app.BuildConfig
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File

object FontSwap {

    private const val TAG = "WaThemer.Font"
    // android.util.Log tag, greppable in logcat. XposedBridge.log routing is less reliable.
    private const val LOGTAG = "WaThemerFont"
    private const val WHATSAPP_PKG = "com.whatsapp"
    private val pkg = BuildConfig.APPLICATION_ID

    // Family keys merged into sSystemFontMap; monospace only on opt-in (the OTP field uses RobotoMono directly).
    private val FAMILY_KEYS = arrayOf(
        "sans-serif", "sans-serif-light", "sans-serif-condensed", "sans-serif-medium",
        "sans-serif-thin", "sans", "sans-serif-black", "sans-serif-smallcaps", "serif",
    )

    @Volatile private var normalFace: Typeface? = null
    @Volatile private var boldFace: Typeface? = null
    private val reentry = ThreadLocal.withInitial { false }
    @Volatile private var loggedRestampFail = false

    private val xprefs: XSharedPreferences by lazy {
        XSharedPreferences(BuildConfig.APPLICATION_ID, Prefs.FILE).apply { makeWorldReadable(); reload() }
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        xprefs.reload()
        val choice = (xprefs.getString(Prefs.KEY_CUSTOM_FONT, "") ?: "").trim()
        if (choice.isBlank()) {
            Log.i(LOGTAG, "no custom font set; stock WA")
            return
        }
        val mapMono = xprefs.getBoolean(Prefs.KEY_FONT_MAP_MONOSPACE, false)
        Log.i(LOGTAG, "install: choice=$choice mapMono=$mapMono")

        // ── Layer 1: the global map and statics. Needs a Context, hence the onCreate before-hook.
        XposedHelpers.findAndHookMethod(
            Application::class.java, "onCreate",
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Application ?: return
                    if (app.packageName != WHATSAPP_PKG) return
                    if (normalFace != null) return            // idempotent: only patch once
                    loadFaces(app, choice)
                    val nf = normalFace ?: run {
                        Log.w(LOGTAG, "face '$choice' failed to load; leaving stock WA")
                        return
                    }
                    val bf = boldFace ?: Typeface.create(nf, Typeface.BOLD)
                    val mono = if (mapMono) nf else null
                    patchSystemFontMap(nf, mono)
                    patchStaticFields(nf, bf, mono)
                    Log.i(LOGTAG, "Layer1 applied (choice=$choice)")
                }
            },
        )

        // ── Layer 2: per-view enforcement on the platform 2-arg setTypeface.
        XposedHelpers.findAndHookMethod(
            TextView::class.java, "setTypeface",
            Typeface::class.java, Int::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val nf = normalFace ?: return
                    if (reentry.get() == true) return
                    val tv = param.thisObject as? TextView ?: return
                    val requested = param.args.getOrNull(0) as? Typeface
                    if (requested === nf || requested === boldFace) return   // already ours
                    // Prefer the style argument: setTypeface(null, BOLD) carries the request only there, not on the typeface.
                    val argStyle = (param.args.getOrNull(1) as? Int) ?: 0
                    val style = if (argStyle > 0) argStyle else (requested?.style ?: Typeface.NORMAL)
                    val target = styledFace(nf, style)
                    if (tv.typeface === target) return                       // idempotent
                    reentry.set(true)
                    try {
                        // Single-arg set so this cannot re-enter the hooked 2-arg method.
                        tv.typeface = target
                    } catch (t: Throwable) {
                        // Latched: this hook runs on every bind, and a consistently-throwing subclass would log per scroll frame.
                        if (!loggedRestampFail) {
                            loggedRestampFail = true
                            Log.w(LOGTAG, "restamp failed: ${t.message}")
                        }
                    } finally {
                        reentry.set(false)
                    }
                }
            },
        )
        Log.i(LOGTAG, "FontSwap installed (Layer1 armed on Application.onCreate, Layer2 on TextView.setTypeface)")
        XposedBridge.log("[$TAG] installed: choice=$choice mono=$mapMono")
    }

    /** Pick a styled variant. Prefer a real bold face for pure-bold; synthesize otherwise. */
    private fun styledFace(base: Typeface, style: Int): Typeface {
        if (style == Typeface.NORMAL) return base
        val b = boldFace
        if (b != null && style == Typeface.BOLD) return b
        return Typeface.create(base, style)   // synth bold / italic / bold-italic
    }

    /** Load the chosen face(s) from our own APK (res/font), via createPackageContext. */
    private fun loadFaces(app: Application, choice: String) {
        if (choice == "user") {
            val f = resolveUserFont(app)
            if (f == null) {
                Log.w(LOGTAG, "user font unavailable; stock WA")
                return
            }
            // Builder returns null on an unparseable file where createFromFile would hand back DEFAULT silently.
            normalFace = runCatching { Typeface.Builder(f).build() }.getOrNull()
            if (normalFace == null) {
                Log.w(LOGTAG, "user font failed to parse; stock WA")
                return
            }
            boldFace = normalFace?.let { Typeface.create(it, Typeface.BOLD) }
            val shownName = xprefs.getString(Prefs.KEY_FONT_USER_NAME, "") ?: ""
            Log.i(LOGTAG, "user font '$shownName' loaded (${f.length()} bytes)")
            return
        }
        val res = runCatching {
            app.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY).resources
        }.getOrElse {
            Log.w(LOGTAG, "createPackageContext($pkg) failed: $it"); return
        }
        val rid = res.getIdentifier("${choice}_regular", "font", pkg)
        if (rid == 0) { Log.w(LOGTAG, "font res ${choice}_regular not found"); return }
        normalFace = runCatching { res.getFont(rid) }.getOrElse {
            Log.w(LOGTAG, "getFont(${choice}_regular) failed: $it"); null
        }
        val bid = res.getIdentifier("${choice}_bold", "font", pkg)
        if (bid != 0) {
            boldFace = runCatching { res.getFont(bid) }.getOrNull()
        }
        if (boldFace == null) normalFace?.let { boldFace = Typeface.create(it, Typeface.BOLD) }
        Log.i(LOGTAG, "loaded faces: normal=${normalFace != null} bold=${boldFace != null} (realBold=${bid != 0})")
    }

    /** The user font file, WA-readable. Cache in our filesDir first, FontProvider second, the legacy Downloads path last. */
    private fun resolveUserFont(app: Application): File? {
        val name = (xprefs.getString(Prefs.KEY_FONT_USER_FILE, "") ?: "").trim()
        if (name.isNotBlank()) {
            val stamp = xprefs.getInt(Prefs.KEY_FONT_USER_STAMP, 0)
            // Stamps are unique forever, so a matching cache file is the right bytes by construction.
            val cache = File(app.filesDir, "wt_font_$stamp")
            if (cache.canRead() && cache.length() > 0) return cache
            runCatching {
                val uri = Uri.parse("content://${pkg}.fonts/$name")
                val part = File(app.filesDir, "${cache.name}.part${android.os.Process.myPid()}")
                app.contentResolver.openInputStream(uri)!!.use { input ->
                    part.outputStream().use { out -> input.copyTo(out) }
                }
                app.filesDir.listFiles()?.forEach {
                    if (it.name.startsWith("wt_font_") && it.name != cache.name && it.name != part.name) it.delete()
                }
                if (!part.renameTo(cache)) {
                    cache.delete()
                    check(part.renameTo(cache))
                }
                Log.i(LOGTAG, "user font fetched via provider (${cache.length()} bytes)")
                return cache
            }.onFailure { Log.w(LOGTAG, "provider fetch failed: $it") }
        }
        // A ttf in Downloads is a non-media file, invisible cross-app under scoped storage; kept only as a last resort.
        val legacy = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "WaThemer/font.ttf",
        )
        if (legacy.canRead()) return legacy
        return null
    }

    /** Layer 1a: point the family keys at our face in the live sSystemFontMap. */
    @Suppress("UNCHECKED_CAST")
    private fun patchSystemFontMap(nf: Typeface, mono: Typeface?) {
        try {
            val custom = HashMap<String, Typeface>()
            FAMILY_KEYS.forEach { custom[it] = nf }
            if (mono != null) custom["monospace"] = mono
            val existing = XposedHelpers.getStaticObjectField(Typeface::class.java, "sSystemFontMap")
            if (existing is MutableMap<*, *>) {
                (existing as MutableMap<String, Typeface>).putAll(custom)
            } else {
                XposedHelpers.setStaticObjectField(Typeface::class.java, "sSystemFontMap", custom)
            }
            Log.i(LOGTAG, "sSystemFontMap merged (${custom.size} keys)")
        } catch (t: Throwable) {
            Log.w(LOGTAG, "patchSystemFontMap failed: $t")
        }
    }

    /** Layer 1b: overwrite the boot-snapshotted static Typeface fields the map can't reach. */
    private fun patchStaticFields(nf: Typeface, bf: Typeface, mono: Typeface?) {
        fun set(name: String, face: Typeface) =
            runCatching { XposedHelpers.setStaticObjectField(Typeface::class.java, name, face) }
                .onFailure { Log.w(LOGTAG, "set Typeface.$name failed: ${it.message}") }
        set("SANS_SERIF", nf)
        set("SERIF", nf)
        set("DEFAULT", nf)
        set("DEFAULT_BOLD", bf)
        if (mono != null) set("MONOSPACE", mono)
        // Never Typeface.setDefault(): maxTargetSdk=P, so it is blocklisted for WhatsApp.
        Log.i(LOGTAG, "static Typeface fields overwritten")
    }
}
