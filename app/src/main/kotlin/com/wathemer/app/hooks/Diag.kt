// Debug-only screen diagnostics: what the host drew and what this module touched, so a tester's
// screenshot can be read against the log. Never logs message text; a tester's chats are real.
package com.wathemer.app.hooks

import android.app.Activity
import android.app.Application
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.wathemer.app.BuildConfig
import com.wathemer.app.hooks.glass.forcedBgLabels
import de.robv.android.xposed.XposedBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Diag {

    private const val TAG = "WaThemerDiag"

    /** False in release and every entry point returns on it; the code itself ships, since every module class is kept. */
    @JvmField val enabled = BuildConfig.DEBUG

    /** Screens are numbered so a screenshot's timestamp can be tied to one dump without reading bounds. */
    private var screenSeq = 0

    /** Trees are large; an unchanged one says nothing a second time. */
    private var lastSignature = ""

    private const val MAX_LINES = 500
    private const val MAX_DEPTH = 24

    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private fun line(msg: String) = XposedBridge.log("[$TAG] $msg")

    /** Wall clock on each RESUME and TREE header: the tester's screenshot filename carries one too, and that is the join key. */
    private fun stamp() = clock.format(Date())

    fun attach(app: Application) {
        if (!enabled) return
        line("diagnostics ON (debug build) host=${app.packageName}")
        ActivityLifecycle.onResumed("diag") { a -> onScreen(a) }
    }

    private fun onScreen(a: Activity) {
        if (!enabled) return
        val n = ++screenSeq
        line("#$n RESUME ${a.javaClass.name}  ${stamp()}")
        val decor = runCatching { a.window?.decorView }.getOrNull() ?: return
        // Two passes: an early one as the screen builds, a late one for the panes that arrive after glass settles.
        for (delay in longArrayOf(450L, 2500L)) {
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { dump(decor, "#$n ${a.javaClass.simpleName}") }
                    .onFailure { line("#$n tree dump threw: $it") }
            }, delay)
        }
    }

    /** One annotated line per view, depth first. Bounds are screen coordinates, so they match the screenshot. */
    fun dump(root: View, why: String) {
        if (!enabled) return
        val out = ArrayList<String>()
        walk(root, 0, out)
        val signature = out.joinToString("\n")
        if (signature == lastSignature) {
            line("TREE $why: unchanged since the last dump")
            return
        }
        lastSignature = signature
        line("TREE $why  ${stamp()}  ${out.size} views")
        for (s in out.take(MAX_LINES)) line(s)
        if (out.size > MAX_LINES) line("  ... ${out.size - MAX_LINES} more views suppressed")
        line("TREE $why end")
    }

    private fun walk(v: View, depth: Int, out: ArrayList<String>) {
        if (out.size > MAX_LINES + 50) return
        out.add(describe(v, depth))
        if (depth >= MAX_DEPTH) return
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1, out)
    }

    private fun describe(v: View, depth: Int): String {
        val sb = StringBuilder(96)
        sb.append("  ").append("| ".repeat(depth))
        sb.append(v.javaClass.simpleName.ifEmpty { v.javaClass.name.substringAfterLast('.') })

        val id = v.id
        if (id != View.NO_ID) sb.append(" #").append(WaIds.nameOf(id))

        val loc = IntArray(2)
        runCatching { v.getLocationOnScreen(loc) }
        sb.append(' ').append(loc[0]).append(',').append(loc[1])
            .append('-').append(loc[0] + v.width).append(',').append(loc[1] + v.height)

        sb.append(
            when (v.visibility) {
                View.VISIBLE -> " vis"
                View.INVISIBLE -> " INVIS"
                else -> " GONE"
            }
        )
        if (v.alpha != 1f) sb.append(" a=").append(String.format(Locale.US, "%.2f", v.alpha))

        sb.append(" bg=").append(bgOf(v.background))

        // Colour and length only, never the string: a tester's chats are real messages.
        if (v is TextView) {
            sb.append(" txt=").append(hex(v.currentTextColor)).append(" len=").append(v.text?.length ?: 0)
        }
        if (v is ImageView) {
            v.drawable?.let { sb.append(" img=").append(it.javaClass.simpleName) }
        }

        marksOf(v)?.let { sb.append("  [wt:").append(it).append(']') }
        return sb.toString()
    }

    /** What this module did to the view, read from the registries it already maintains. */
    private fun marksOf(v: View): String? {
        val marks = ArrayList<String>(2)
        val cls = v.javaClass.name
        if (cls.startsWith("com.wathemer.app.glass.")) marks.add(v.javaClass.simpleName)
        runCatching { synchronized(forcedBgLabels) { forcedBgLabels[v] } }
            .getOrNull()?.let { marks.add("bg=$it") }
        return if (marks.isEmpty()) null else marks.joinToString(" ")
    }

    private fun bgOf(d: Drawable?): String = when (d) {
        null -> "none"
        is ColorDrawable -> hex(d.color)
        else -> d.javaClass.simpleName
    }

    private fun hex(c: Int): String = "#%08X".format(c)
}
