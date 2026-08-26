package com.wathemer.app.hooks

import android.content.res.Resources
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** Every WA id lookup routes through here so a miss logs; instrumentation only, callers still get the raw 0. */
object WaIds {

    private const val TAG = "WaThemer"

    /** Every name asked for, so the summary can give a denominator rather than a bare count. */
    private val requested = ConcurrentHashMap.newKeySet<String>()

    /** Names that resolved to 0. Doubles as the log-once guard: `add` returns false if present. */
    private val missing = ConcurrentHashMap.newKeySet<String>()

    /** Resolved ids back to the name they were asked for, so a dispatcher can name what it fired. */
    private val names = ConcurrentHashMap<Int, String>()

    /** The name [id] was resolved from, or a hex id when it never came through here. */
    fun nameOf(id: Int): String = names[id] ?: "id/0x${id.toString(16)}"

    /** Resolves a WA id, logging the first miss per name; returns the raw 0 so existing guards keep working. */
    fun id(res: Resources, name: String, pkg: String): Int {
        val value = runCatching { res.getIdentifier(name, "id", pkg) }.getOrDefault(0)
        requested.add(name)
        if (value != 0) names[value] = name
        if (value == 0 && missing.add(name)) {
            XposedBridge.log(
                "[$TAG] WA id UNRESOLVED: '$name' in $pkg; the surface driven by it will be absent, " +
                    "not broken. If WhatsApp renamed it, this line is the only symptom."
            )
        }
        return value
    }

    // ── Class-name guards ──────────────────────────────────────────────────────────────────
    // A renamed guard class fails closed and silent, so a per-marker reject counter reports itself; logSummary runs too early.

    private val guardMatched = ConcurrentHashMap.newKeySet<String>()
    private val guardRejects = ConcurrentHashMap<String, Int>()

    /** 20 because a never-opened Calls tab racks up legitimate rejections; a false positive costs more than a late true one. */
    private const val GUARD_SUSPECT_AFTER = 20

    /** contains(marker) with a renamed-marker log. Gates only, never search predicates, which reject by design. */
    fun classIs(obj: Any?, marker: String): Boolean {
        if (obj?.javaClass?.name?.contains(marker) == true) {
            guardMatched.add(marker)
            return true
        }
        if (marker in guardMatched) return false          // already proven live; stop counting
        val n = guardRejects.merge(marker, 1, Int::plus) ?: 1
        if (n == GUARD_SUSPECT_AFTER) {
            XposedBridge.log(
                "[$TAG] WA class guard '$marker' rejected $n candidates and has never matched. " +
                    "If the feature it gates is missing, this is the reason; the class was " +
                    "probably renamed, and the guard fails closed rather than loudly."
            )
        }
        return false
    }

    // ── Reflected members on WhatsApp classes ──────────────────────────────────────────────
    // Obfuscated members renumber every WA build; resolve name-then-shape and refuse to guess when several candidates fit.

    /** Field by name with optional sole-field-of-type fallback; type null disables shape, expect still type-checks the pin. */
    fun field(
        cls: Class<*>,
        name: String,
        type: Class<*>? = null,
        what: String,
        expect: Class<*>? = type,
    ): Field? {
        var wrongType = false
        runCatching { cls.getDeclaredField(name).apply { isAccessible = true } }
            .getOrNull()?.let { f ->
                if (expect == null || expect.isAssignableFrom(f.type)) return f
                wrongType = true
                XposedBridge.log(
                    "[$TAG] $what: field '$name' still exists on ${cls.name} but is a " +
                        "${f.type.simpleName}, not a ${expect.simpleName}; the pin has been reused " +
                        "for something else. Ignoring it rather than writing to the wrong field."
                )
            }
        // Worded off wrongType so the two log lines cannot contradict each other.
        val nameSaid = if (wrongType) "field '$name' is the wrong type" else "no field '$name'"

        if (type != null) {
            val candidates = cls.declaredFields.filter { it.type == type }
            when (candidates.size) {
                1 -> {
                    val f = candidates[0].apply { isAccessible = true }
                    XposedBridge.log(
                        "[$TAG] $what: field '$name' is gone from ${cls.name}, RECOVERED by shape as " +
                            "'${f.name}' (the only ${type.simpleName}). WhatsApp renumbered; update the pin."
                    )
                    return f
                }
                0 -> XposedBridge.log(
                    "[$TAG] $what UNRESOLVED: $nameSaid on ${cls.name} and no ${type.simpleName} " +
                        "field at all. The feature is OFF."
                )
                else -> XposedBridge.log(
                    "[$TAG] $what UNRESOLVED: $nameSaid on ${cls.name}; ${candidates.size} " +
                        "${type.simpleName} fields (${candidates.map { it.name }}) so shape cannot pick " +
                        "one. Refusing to guess. The feature is OFF."
                )
            }
        } else {
            XposedBridge.log("[$TAG] $what UNRESOLVED: $nameSaid on ${cls.name}. The feature is OFF.")
        }
        anchorFailed(what)
        return null
    }

    /** A declared no-arg method by name, logged on a miss. Shape is not a usable fallback here. */
    fun method(cls: Class<*>, name: String, what: String): Method? {
        runCatching { cls.getDeclaredMethod(name).apply { isAccessible = true } }
            .getOrNull()?.let { return it }
        XposedBridge.log("[$TAG] $what UNRESOLVED: no method '$name()' on ${cls.name}. The feature is OFF.")
        anchorFailed(what)
        return null
    }

    /** A WhatsApp class by fully-qualified name, logged on a miss. */
    fun clazz(loader: ClassLoader, fqcn: String, what: String): Class<*>? {
        runCatching { loader.loadClass(fqcn) }.getOrNull()?.let { return it }
        XposedBridge.log("[$TAG] $what UNRESOLVED: class '$fqcn' not found. The feature is OFF.")
        anchorFailed(what)
        return null
    }

    private val failedAnchors = ConcurrentHashMap.newKeySet<String>()
    private fun anchorFailed(what: String) { failedAnchors.add(what) }

    /** One denominator line per launch; anchors keep resolving lazily after install, so the per-name lines stay the authority. */
    fun logSummary() {
        val total = requested.size
        if (total > 0) {
            if (missing.isEmpty()) {
                XposedBridge.log(
                    "[$TAG] WA ids: all $total anchors resolved AT INSTALL. Anchors resolved later " +
                        "(on Activity create / view attach) report themselves individually above."
                )
            } else {
                XposedBridge.log(
                    "[$TAG] WA ids: ${missing.size} of $total install-time anchors UNRESOLVED; " +
                        "${missing.sorted()} (more may resolve later; watch for further UNRESOLVED lines)"
                )
            }
        }
        // Reflected members all resolve during install, so they can be summarised here.
        if (failedAnchors.isNotEmpty()) {
            XposedBridge.log(
                "[$TAG] WA reflected anchors FAILED: ${failedAnchors.sorted()}; those features are " +
                    "off. Obfuscated members renumber on every WhatsApp build; this is the expected " +
                    "way for that to surface."
            )
        }
        // Class guards report from [classIs]; none has been evaluated this early in startup.
    }
}

/** `res.waId("my_search_bar", pkg)` in place of `res.getIdentifier("my_search_bar", "id", pkg)`. */
fun Resources.waId(name: String, pkg: String): Int = WaIds.id(this, name, pkg)
