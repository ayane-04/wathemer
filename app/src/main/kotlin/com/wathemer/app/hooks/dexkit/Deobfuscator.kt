// Structural resolution of WhatsApp's obfuscated names via DexKit, with a self-healing name cache.
// Obfuscated names must be spelled as ART sees them, never jadx's C-prefixed display form.
package com.wathemer.app.hooks.dexkit

import android.app.Application
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.text.TextPaint
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher

private const val TAG = "WaThemer.Deob"

object Deobfuscator {

    @Volatile private var bridge: DexKitBridge? = null
    private val cache = DeobfuscatorCache()
    private var app: Application? = null

    /** Prefix every diagnostic line with TAG; routed to the LSPosed module log. */
    private fun dlog(m: String) { XposedBridge.log("$TAG: $m") }

    init {
        runCatching { System.loadLibrary("dexkit") }
            .onFailure { dlog("failed to load libdexkit: $it") }
    }

    @Volatile private var cacheLoaded = false

    /** Load the name cache; separate from [ensureBridge] so a warm cache never pays to open DexKit. */
    @Synchronized
    fun ensureCache(application: Application) {
        if (cacheLoaded) return
        cacheLoaded = true
        app = application
        cache.load(application)
    }

    /** Open the bridge at most once per process; idempotent so sibling features can each call it. */
    @Synchronized
    fun ensureBridge(application: Application): Boolean {
        ensureCache(application)
        if (bridge != null) return true
        val path = application.applicationInfo.sourceDir
        return runCatching {
            bridge = DexKitBridge.create(path)
            dlog("DexKitBridge opened on $path")
            true
        }.getOrElse {
            dlog("ensureBridge failed on $path: ${it.stackTraceToString()}")
            false
        }
    }

    /** Persist the cache. Caller invokes after all loadXxx have run. */
    fun saveCache() {
        app?.let { cache.save(it) }
    }

    /** Release the native dex index after install; nulled so [ensureBridge] can re-open if ever needed. */
    @Synchronized
    fun closeBridge() {
        val b = bridge ?: return
        bridge = null
        runCatching { b.close() }
            .onSuccess { dlog("DexKitBridge closed; native dex index released") }
            .onFailure { dlog("DexKitBridge close failed: $it") }
    }

    /** Null when DexKit is unavailable; resolvers must degrade gracefully, never throw. */
    private fun bridgeOrNull(): DexKitBridge? = bridge

    // ── Bubble provider resolution ────────────────────────────────────────

    @Volatile private var bubbleProviderResolved = false
    private var bubbleProviderClass: Class<*>? = null
    private var bubbleDrawableMethod: Method? = null
    private var balloonDateDrawableMethod: Method? = null
    private var balloonBorderDrawableMethod: Method? = null
    private var balloonInsetMethod: Method? = null

    /** Resolve the bubble provider class + 4 methods. Idempotent. */
    @Synchronized
    fun loadBubbleProviderClass(classLoader: ClassLoader): Class<*>? {
        if (bubbleProviderResolved) return bubbleProviderClass
        bubbleProviderResolved = true

        // Self-healing: try cached name first.
        cache.getString("bubble_provider_class")?.let { name ->
            try {
                val cls = Class.forName(name, false, classLoader)
                bubbleProviderClass = cls
                resolveBubbleMethods(cls)
                dlog("bubble provider via cache -> $name")
                return cls
            } catch (_: Throwable) {
                cache.remove("bubble_provider_class")
            }
        }

        val bridge = bridgeOrNull() ?: run {
            dlog("bubble provider UNRESOLVED; DexKit bridge not open")
            return null
        }

        // Primary query: addUsingString + returnType=Drawable.
        val q1 = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create()
                    .addUsingString("Unreachable code: direction=")
                    .returnType(Drawable::class.java),
            ),
        )
        if (q1.isNotEmpty()) {
            val methodInstance = q1[0].getMethodInstance(classLoader).also { it.isAccessible = true }
            bubbleDrawableMethod = methodInstance
            val declaringClass = methodInstance.declaringClass
            bubbleProviderClass = declaringClass
            cache.putString("bubble_provider_class", declaringClass.name)
            resolveBubbleMethods(declaringClass)
            dlog("bubble provider via Drawable-anchor -> ${declaringClass.name}")
            return declaringClass
        }

        // Fallback: same anchor + returnType=Rect -> declaring class is still the provider.
        val q2 = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create()
                    .addUsingString("Unreachable code: direction=")
                    .returnType(Rect::class.java),
            ),
        )
        if (q2.isEmpty()) {
            dlog("bubble provider UNRESOLVED; both Drawable + Rect queries empty")
            return null
        }
        val declaringClass = q2[0].getMethodInstance(classLoader).declaringClass
        bubbleProviderClass = declaringClass
        cache.putString("bubble_provider_class", declaringClass.name)
        resolveBubbleMethods(declaringClass)
        dlog("bubble provider via Rect-fallback -> ${declaringClass.name}")
        return declaringClass
    }

    fun loadBubbleDrawableMethod(classLoader: ClassLoader): Method? {
        loadBubbleProviderClass(classLoader)
        return bubbleDrawableMethod
    }

    fun loadBalloonDateDrawableMethod(classLoader: ClassLoader): Method? {
        loadBubbleProviderClass(classLoader)
        return balloonDateDrawableMethod
    }

    fun loadBalloonBorderDrawableMethod(classLoader: ClassLoader): Method? {
        loadBubbleProviderClass(classLoader)
        return balloonBorderDrawableMethod
    }

    /** The bubble content-inset method: WA reads text padding here, not from the drawable's getPadding. */
    fun loadBalloonInsetMethod(classLoader: ClassLoader): Method? {
        loadBubbleProviderClass(classLoader)
        return balloonInsetMethod
    }

    /** Shape-pick the 3 Drawable methods by parameter shape; the main painter is the max-arity (int,int,...) one. */
    private fun resolveBubbleMethods(clazz: Class<*>) {
        val intType = Int::class.javaPrimitiveType
        val drawableMethods = clazz.declaredMethods.filter { it.returnType == Drawable::class.java }

        if (bubbleDrawableMethod == null) {
            bubbleDrawableMethod = drawableMethods
                .filter { m ->
                    m.parameterCount >= 2 &&
                        m.parameterTypes[0] == intType &&
                        m.parameterTypes[1] == intType
                }
                .maxByOrNull { it.parameterCount }
                ?.also { it.isAccessible = true }
        }

        balloonDateDrawableMethod = drawableMethods.firstOrNull { m ->
            m.parameterCount == 1 &&
                m.parameterTypes[0] == intType &&
                m != bubbleDrawableMethod
        }?.also { it.isAccessible = true }

        // Arg 1 pinned: the direction is read from it, and a future 3-arg drawable method would otherwise match on count alone.
        balloonBorderDrawableMethod = drawableMethods.firstOrNull { m ->
            m.parameterCount == 3 &&
                m.parameterTypes[1] == intType &&
                m != bubbleDrawableMethod &&
                m != balloonDateDrawableMethod
        }?.also { it.isAccessible = true }

        // Inset method (for bubble-shape text padding): Rect return, (int, boolean) params.
        balloonInsetMethod = clazz.declaredMethods.firstOrNull { m ->
            m.returnType == Rect::class.java &&
                m.parameterCount == 2 &&
                m.parameterTypes[0] == intType &&
                m.parameterTypes[1] == Boolean::class.javaPrimitiveType
        }?.also { it.isAccessible = true }

        dlog(
            "methods on ${clazz.name} -> " +
                "drawable=${bubbleDrawableMethod?.name} " +
                "date=${balloonDateDrawableMethod?.name} " +
                "border=${balloonBorderDrawableMethod?.name} " +
                "inset=${balloonInsetMethod?.name}",
        )
    }

    // ── Bubble leaf builders (DynamicBubbleProvider) ──────────────────────

    private const val BUBBLE_LEAF_CACHE_KEY = "bubble_leaf_class"

    @Volatile private var bubbleLeafResolved = false
    private var bubbleTailedBuilder: Method? = null
    private var bubbleExtBuilder: Method? = null

    /** Resolve the two leaf nine-patch builders; the static NinePatchDrawable(Paint, ...) anchor is unique app-wide. */
    @Synchronized
    fun loadBubbleLeafBuilders(application: Application, classLoader: ClassLoader): Pair<Method?, Method?> {
        if (bubbleLeafResolved) return bubbleTailedBuilder to bubbleExtBuilder
        bubbleLeafResolved = true

        ensureCache(application)
        cache.getString(BUBBLE_LEAF_CACHE_KEY)?.let { name ->
            val cls = runCatching { Class.forName(name, false, classLoader) }.getOrNull()
            if (cls != null && pickBubbleLeafMethods(cls)) {
                dlog("bubble leaf builders via cache -> $name")
                return bubbleTailedBuilder to bubbleExtBuilder
            }
            cache.remove(BUBBLE_LEAF_CACHE_KEY)
        }

        if (ensureBridge(application)) {
            val bridge = bridgeOrNull()
            val hits = if (bridge == null) null else runCatching {
                bridge.findMethod(
                    FindMethod.create().matcher(
                        MethodMatcher.create().returnType(NinePatchDrawable::class.java),
                    ),
                )
            }.getOrElse {
                dlog("bubble leaf query threw: ${it.stackTraceToString()}")
                null
            }
            val owners = hits.orEmpty()
                .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                .filter {
                    Modifier.isStatic(it.modifiers) &&
                        it.parameterTypes.firstOrNull() == Paint::class.java
                }
                .map { it.declaringClass }
                .distinct()
            when {
                owners.isEmpty() -> dlog("bubble leaf builders UNRESOLVED; no NinePatchDrawable(Paint, ...) statics")
                owners.size > 1 ->
                    dlog("bubble leaf builders AMBIGUOUS: ${owners.size} owners ${owners.map { it.name }}")
                else -> {
                    val cls = owners[0]
                    if (pickBubbleLeafMethods(cls)) {
                        cache.putString(BUBBLE_LEAF_CACHE_KEY, cls.name)
                        dlog("bubble leaf builders via DexKit -> ${cls.name}")
                    } else {
                        dlog("bubble leaf shape-pick failed on ${cls.name}")
                    }
                }
            }
        }
        return bubbleTailedBuilder to bubbleExtBuilder
    }

    /** Pick the two builders by arity alone; the tail-less one carries an extra tag argument. */
    private fun pickBubbleLeafMethods(clazz: Class<*>): Boolean {
        val intType = Int::class.javaPrimitiveType
        val boolType = Boolean::class.javaPrimitiveType
        val candidates = clazz.declaredMethods.filter { m ->
            m.returnType == NinePatchDrawable::class.java &&
                Modifier.isStatic(m.modifiers) &&
                m.parameterTypes.firstOrNull() == Paint::class.java
        }
        bubbleTailedBuilder = candidates.firstOrNull { m ->
            m.parameterCount == 4 &&
                m.parameterTypes[2] == intType && m.parameterTypes[3] == boolType
        }?.apply { isAccessible = true }
        bubbleExtBuilder = candidates.firstOrNull { m ->
            m.parameterCount == 5 &&
                m.parameterTypes[3] == intType && m.parameterTypes[4] == boolType
        }?.apply { isAccessible = true }
        dlog(
            "bubble leaf pick on ${clazz.name} -> tailed=${bubbleTailedBuilder?.name} " +
                "ext=${bubbleExtBuilder?.name} (of ${candidates.size} candidates)",
        )
        return bubbleTailedBuilder != null && bubbleExtBuilder != null
    }

    // ── Clickable-link span resolution ────────────────────────────────────

    private const val LINK_SPAN_CACHE_KEY = "link_span_class"
    private const val UPDATE_DRAW_STATE = "updateDrawState"
    private const val LINK_COLOR_FIELD = "linkColor"

    /** Last-resort names, spelled as ART sees them, never jadx's C-prefixed form; neither declares updateDrawState on current builds. */
    private val LEGACY_LINK_SPAN_NAMES = listOf(
        "X.17S",   // the build before the bridge
        "X.1hK",   // the build before that
    )

    @Volatile private var linkSpanResolved = false
    private var linkSpanMethod: Method? = null

    /** Resolve updateDrawState on the span base only (subclass overrides keep their styling); all three anchor parts are needed. */
    @Synchronized
    fun loadLinkSpanDrawStateMethod(application: Application, classLoader: ClassLoader): Method? {
        if (linkSpanResolved) return linkSpanMethod
        linkSpanResolved = true

        // Cheap path first: a warm cache resolves without ever opening DexKit.
        ensureCache(application)
        cache.getString(LINK_SPAN_CACHE_KEY)?.let { name ->
            val cached = loadDrawStateMethod(name, classLoader)
            if (cached != null) {
                linkSpanMethod = cached
                dlog("link span via cache -> $name")
                return cached
            }
            // Stale name from a previous WA build. Drop it and re-run the query.
            cache.remove(LINK_SPAN_CACHE_KEY)
        }

        if (ensureBridge(application)) {
            val resolved = queryLinkSpanMethod(classLoader)
            if (resolved != null) {
                linkSpanMethod = resolved
                cache.putString(LINK_SPAN_CACHE_KEY, resolved.declaringClass.name)
                dlog("link span via DexKit -> ${resolved.declaringClass.name}")
                return resolved
            }
        }

        for (name in LEGACY_LINK_SPAN_NAMES) {
            val legacy = loadDrawStateMethod(name, classLoader) ?: continue
            linkSpanMethod = legacy
            cache.putString(LINK_SPAN_CACHE_KEY, name)
            dlog("link span via legacy name -> $name")
            return legacy
        }

        dlog("link span UNRESOLVED (query + ${LEGACY_LINK_SPAN_NAMES.size} legacy names all missed)")
        return null
    }

    /** Two-stage on purpose: keep the linkColor check as a post-filter; an addUsingField matcher can fail silently. */
    private fun queryLinkSpanMethod(classLoader: ClassLoader): Method? {
        val bridge = bridgeOrNull() ?: return null
        val hits = runCatching {
            bridge.findMethod(
                FindMethod.create().matcher(
                    MethodMatcher.create()
                        .name(UPDATE_DRAW_STATE)
                        .paramTypes(TextPaint::class.java)
                        .returnType(Void.TYPE)
                        .declaredClass(
                            ClassMatcher.create()
                                .superClass("android.text.style.MetricAffectingSpan"),
                        ),
                ),
            )
        }.getOrElse {
            dlog("link span query threw: ${it.stackTraceToString()}")
            return null
        }
        if (hits.isEmpty()) {
            dlog("link span stage-1 query matched nothing")
            return null
        }
        val narrowed = hits.filter { m ->
            runCatching {
                m.usingFields.any { uf ->
                    val f = uf.field
                    // Check both: the aar exposes name and fieldName with no guarantee which is the bare identifier.
                    f.fieldName == LINK_COLOR_FIELD || f.name == LINK_COLOR_FIELD
                }
            }.getOrDefault(false)
        }
        if (narrowed.isEmpty()) {
            dlog("link span stage-2 filter rejected all ${hits.size} hits (no $LINK_COLOR_FIELD read)")
            return null
        }
        if (narrowed.size > 1) {
            // Ambiguous: bail to the legacy ladder rather than silently picking wrong.
            dlog("link span AMBIGUOUS: ${narrowed.size} candidates ${narrowed.map { it.declaredClassName }}")
            return null
        }
        return runCatching {
            narrowed[0].getMethodInstance(classLoader).apply { isAccessible = true }
        }.getOrElse {
            dlog("link span getMethodInstance(${narrowed[0].declaredClassName}) failed: $it")
            null
        }
    }

    /** `<class>.updateDrawState(TextPaint)` by class name, or null if either is absent. */
    private fun loadDrawStateMethod(className: String, classLoader: ClassLoader): Method? =
        runCatching {
            Class.forName(className, false, classLoader)
                .getDeclaredMethod(UPDATE_DRAW_STATE, TextPaint::class.java)
                .apply { isAccessible = true }
        }.getOrNull()
}
