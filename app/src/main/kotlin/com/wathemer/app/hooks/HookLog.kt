// One ledger for every treatment the module installs, so a log capture answers "what actually ran".
// A feature that is absent rather than broken leaves nothing to search for; this makes absence loud.
package com.wathemer.app.hooks

import de.robv.android.xposed.XposedBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

object HookLog {

    private const val TAG = "WaThemerLog"

    /** What became of one named treatment. ARMED but never HIT is the interesting case. */
    enum class State { ARMED, SKIPPED, HIT, FAILED }

    private class Entry(val name: String) {
        @Volatile var state: State = State.ARMED
        @Volatile var detail: String = ""
        val hits = AtomicInteger(0)
    }

    private val entries = ConcurrentHashMap<String, Entry>()

    /** Dumps are cheap but not free; a repeat with nothing new to say is suppressed. */
    @Volatile private var lastSignature = ""

    private fun entry(name: String) = entries.getOrPut(name) { Entry(name) }

    private fun line(msg: String) = XposedBridge.log("[$TAG] $msg")

    /** Registration succeeded: the anchor resolved and the hook is in place. Nothing has run yet. */
    fun arm(name: String, detail: String = "") {
        val e = entry(name)
        // A later ARM must not overwrite a HIT; re-registration on a second Activity is normal.
        if (e.state == State.HIT || e.state == State.FAILED) return
        e.state = State.ARMED
        e.detail = detail
        line("ARM   $name${if (detail.isEmpty()) "" else "  $detail"}")
    }

    /** Declined on purpose, with the reason. A guard doing its job must never look like a dead hook. */
    fun skip(name: String, why: String) {
        val e = entry(name)
        if (e.state == State.HIT) return
        e.state = State.SKIPPED
        e.detail = why
        line("SKIP  $name  $why")
    }

    /** The treatment actually did its work. Logged the first time, counted after that. */
    fun hit(name: String, detail: String = "") {
        val e = entry(name)
        val n = e.hits.incrementAndGet()
        if (e.state != State.HIT) {
            e.state = State.HIT
            e.detail = detail
            line("HIT   $name${if (detail.isEmpty()) "" else "  $detail"}")
        }
    }

    /** Threw. Recorded against the surface so the summary names it, not just logcat's stack. */
    fun fail(name: String, t: Throwable) {
        val e = entry(name)
        e.state = State.FAILED
        e.detail = t.toString()
        line("FAIL  $name  $t")
    }

    /** Run [body], recording HIT or FAIL against [name]. Never rethrows: a hook must not crash WA. */
    inline fun guard(name: String, body: () -> Unit) {
        try {
            body()
            hit(name)
        } catch (t: Throwable) {
            fail(name, t)
        }
    }

    /** True once this surface has done its work, for callers that want to log a state change only. */
    fun isHit(name: String): Boolean = entries[name]?.state == State.HIT

    /**
     * The whole ledger in one block, grouped by state. ARMED means registered and never fired,
     * which on a screen the user has visited is the line worth reading.
     */
    fun dump(reason: String, force: Boolean = false) {
        val all = entries.values.sortedBy { it.name }
        if (all.isEmpty()) {
            line("SUMMARY ($reason): nothing registered")
            return
        }
        val byState = all.groupBy { it.state }
        val signature = all.joinToString(",") { "${it.name}=${it.state}${it.hits.get()}" }
        if (!force && signature == lastSignature) {
            line("SUMMARY ($reason): unchanged since the last dump")
            return
        }
        lastSignature = signature
        line(
            "SUMMARY ($reason): ${all.size} treatments; " +
                State.entries.joinToString(" ") { s -> "$s=${byState[s]?.size ?: 0}" }
        )
        // FAILED and ARMED first: those are the two that mean something is not on screen.
        for (state in listOf(State.FAILED, State.ARMED, State.SKIPPED, State.HIT)) {
            val group = byState[state] ?: continue
            line("  -- $state (${group.size}) --")
            for (e in group) {
                val hits = if (e.state == State.HIT) " x${e.hits.get()}" else ""
                val detail = if (e.detail.isEmpty()) "" else "  ${e.detail}"
                line("     ${e.name}$hits$detail")
            }
        }
    }
}
