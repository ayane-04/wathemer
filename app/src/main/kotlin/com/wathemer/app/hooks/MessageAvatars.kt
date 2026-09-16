// Pictures beside messages: the sender's stored thumbnail, drawn in the row's content child.
// WhatsApp lays its rows out by hand, so the picture lives in that child's padding and overlay, never as a new child.
package com.wathemer.app.hooks

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import com.wathemer.app.hooks.dispatch.ViewThemeDispatcher
import com.wathemer.app.hooks.glass.tagKey
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

object MessageAvatars {

    private const val TAG = "WaThemer.MessageAvatars"
    private const val GAP_DP = 6f
    private const val JID_CLASS = "com.whatsapp.infra.core.jid.Jid"
    private const val USER_JID_CLASS = "com.whatsapp.infra.core.jid.UserJid"
    private const val SETTER = "setFMessage"

    private val xprefs: ModulePrefs.WtPrefs by lazy { ModulePrefs.open() }

    private var inChats = false
    private var inGroups = false
    private var mine = false
    private var firstOnly = false
    private var sizePx = 0
    private var gapPx = 0
    private var dateWrapperId = 0
    private var headerStubId = 0
    private lateinit var avatarsDir: File
    private var defaultAvatar: Drawable? = null

    private val avatarTag = tagKey("wathemer-msg-avatar")
    private val basePadTag = tagKey("wathemer-msg-avatar-pad")

    // Main thread only: rows bind and lay out there.
    private val NONE = Any()
    private val setterCache = HashMap<Class<*>, Any>()
    private val messageFieldCache = HashMap<Class<*>, List<Field>>()
    private var setterHooked = false
    private var messageClass: Class<*>? = null
    private var keyField: Field? = null
    private var fromMeField: Field? = null
    private var chatJidField: Field? = null
    private var senderMethod: Method? = null
    private var rawString: Method? = null

    private class Cached(val bitmap: Bitmap, val stamp: Long)
    private val bitmaps = LruCache<String, Cached>(96)
    private val logged = HashSet<String>()

    fun install(app: Application) {
        xprefs.reload()
        inChats = xprefs.getBoolean(Prefs.KEY_MSG_AVATAR_CHATS, false)
        inGroups = xprefs.getBoolean(Prefs.KEY_MSG_AVATAR_GROUPS, false)
        mine = xprefs.getBoolean(Prefs.KEY_MSG_AVATAR_MINE, false)
        firstOnly = xprefs.getBoolean(Prefs.KEY_MSG_AVATAR_FIRST_ONLY, false)
        if (!inChats && !inGroups && !mine) {
            HookLog.skip("install/MessageAvatars", "no picture switch on")
            return
        }
        val res = app.resources
        val pkg = app.packageName
        val density = res.displayMetrics.density
        val sizeDp = xprefs.getInt(Prefs.KEY_MSG_AVATAR_SIZE, Prefs.MSG_AVATAR_SIZE_DEFAULT)
            .coerceIn(Prefs.MSG_AVATAR_SIZE_MIN, Prefs.MSG_AVATAR_SIZE_MAX)
        sizePx = (sizeDp * density).toInt()
        gapPx = (GAP_DP * density).toInt()
        avatarsDir = File(app.filesDir, "Avatars")
        defaultAvatar = runCatching {
            val id = res.getIdentifier("avatar_contact", "drawable", pkg)
            if (id == 0) null else res.getDrawable(id, null)
        }.getOrNull()
        dateWrapperId = res.waId("date_wrapper", pkg)
        headerStubId = res.waId("conversation_row_participant_header_view_stub", pkg)
        if (dateWrapperId == 0) {
            HookLog.skip("install/MessageAvatars", "no date_wrapper id")
            return
        }
        ViewThemeDispatcher.onId(dateWrapperId) { v ->
            runCatching { onRowAttached(v) }.onFailure { logOnce("attach: $it") }
        }
        HookLog.arm("MessageAvatars/rows", "chats=$inChats groups=$inGroups mine=$mine first=$firstOnly size=${sizeDp}dp")
    }

    // ── Finding the row and its message ──

    /** The first attach names the row class; from then on every bind arrives through the hooked setter. */
    private fun onRowAttached(dateWrapper: View) {
        val row = rowOf(dateWrapper) ?: return
        if (!setterHooked) hookSetter(row)
        val msg = currentMessage(row) ?: return
        bind(row, msg)
    }

    private fun rowOf(v: View): View? {
        var p = v.parent as? View
        while (p != null) {
            if (setterOf(p.javaClass) != null) return p
            p = p.parent as? View
        }
        return null
    }

    /** The topmost declaration in the chain, so every subclass that calls super passes through one hook. */
    private fun setterOf(cls: Class<*>): Method? {
        val cached = setterCache[cls]
        if (cached != null) return cached as? Method
        var top: Method? = null
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java && c != View::class.java && c != ViewGroup::class.java) {
            c.declaredMethods.firstOrNull { it.name == SETTER && it.parameterCount == 1 }?.let { top = it }
            c = c.superclass
        }
        setterCache[cls] = top ?: NONE
        return top
    }

    private fun hookSetter(row: View) {
        val m = setterOf(row.javaClass) ?: return
        setterHooked = true
        runCatching { resolveMessageShape(m.parameterTypes[0], row.javaClass.classLoader ?: return) }
            .onFailure { logOnce("message shape: $it") }
        if (keyField == null) {
            XposedBridge.log("$TAG: message shape unresolved on ${m.parameterTypes[0].name}; pictures off")
            HookLog.skip("MessageAvatars/setter", "message shape unresolved")
            return
        }
        XposedBridge.hookMethod(m, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val r = p.thisObject as? View ?: return
                val msg = p.args.getOrNull(0) ?: return
                runCatching { bind(r, msg) }.onFailure { logOnce("bind: $it") }
            }
        })
        HookLog.arm("MessageAvatars/setter", "${m.declaringClass.name}.${m.name}")
    }

    /** The key is the one field whose type holds a jid, a string and a boolean; the sender is the one method returning a UserJid. */
    private fun resolveMessageShape(msgClass: Class<*>, cl: ClassLoader) {
        messageClass = msgClass
        val jidCls = Class.forName(JID_CLASS, false, cl)
        val userJidCls = Class.forName(USER_JID_CLASS, false, cl)
        rawString = jidCls.getMethod("getRawString")
        for (f in allFields(msgClass)) {
            val t = f.type
            if (t.isPrimitive || t.isArray || t.name.startsWith("java.") || t.name.startsWith("android.")) continue
            val fs = t.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            if (fs.size > 4) continue
            val jid = fs.firstOrNull { jidCls.isAssignableFrom(it.type) } ?: continue
            val bool = fs.firstOrNull { it.type == java.lang.Boolean.TYPE } ?: continue
            if (fs.none { it.type == String::class.java }) continue
            keyField = f.apply { isAccessible = true }
            chatJidField = jid.apply { isAccessible = true }
            fromMeField = bool.apply { isAccessible = true }
            break
        }
        val senders = allMethods(msgClass).filter { it.parameterCount == 0 && it.returnType == userJidCls }
        senderMethod = if (senders.size == 1) senders[0].apply { isAccessible = true } else null
        XposedBridge.log(
            "$TAG: message ${msgClass.name} key=${keyField?.name} jid=${chatJidField?.name} fromMe=${fromMeField?.name} " +
                "sender=${senderMethod?.name ?: "none of ${senders.size}"}",
        )
    }

    private fun allFields(cls: Class<*>): List<Field> {
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            c.declaredFields.filterTo(out) { !Modifier.isStatic(it.modifiers) }
            c = c.superclass
        }
        return out
    }

    private fun allMethods(cls: Class<*>): List<Method> {
        val out = ArrayList<Method>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out.addAll(c.declaredMethods)
            c = c.superclass
        }
        return out
    }

    /** The row keeps two message slots; the bound one is the first non-null in declaration order. */
    private fun currentMessage(row: View): Any? {
        val mc = messageClass ?: return null
        val fields = messageFieldCache.getOrPut(row.javaClass) {
            allFields(row.javaClass).filter { it.type == mc }.onEach { it.isAccessible = true }
        }
        return fields.firstNotNullOfOrNull { it.get(row) }
    }

    // ── One bind ──

    private fun bind(row: View, msg: Any) {
        val key = keyField?.get(msg) ?: return
        val fromMe = fromMeField?.getBoolean(key) ?: return
        val chat = chatJidField?.get(key) ?: return
        val chatRaw = rawString?.invoke(chat) as? String ?: return
        val group = chatRaw.endsWith("@g.us")
        val person = chatRaw.endsWith("@s.whatsapp.net") || chatRaw.endsWith("@lid")
        val container = contentChild(row) ?: return
        val wanted = when {
            !group && !person -> false
            fromMe -> mine
            group -> inGroups
            else -> inChats
        }
        if (!wanted || (firstOnly && group && !fromMe && !headerShown(row))) {
            hide(container)
            return
        }
        val file = when {
            fromMe -> File(avatarsDir, "me.j")
            group -> senderRaw(msg)?.let { File(avatarsDir, "$it.j") }
            else -> File(avatarsDir, "$chatRaw.j")
        }
        show(container, file?.let { bitmapFor(it) }, atEnd = fromMe)
    }

    private fun senderRaw(msg: Any): String? =
        runCatching { senderMethod?.invoke(msg)?.let { rawString?.invoke(it) as? String } }.getOrNull()

    /** The row's direct child on the way to the date, whatever the row type names it. */
    private fun contentChild(row: View): View? {
        var v: View = (row as? ViewGroup)?.findViewById(dateWrapperId) ?: return null
        while (true) {
            val p = v.parent as? View ?: return null
            if (p === row) return v
            v = p
        }
    }

    /** The participant header only shows on the first message of a run, so it is the run boundary. */
    private fun headerShown(row: View): Boolean {
        if (headerStubId == 0) return true
        val h = (row as? ViewGroup)?.findViewById<View>(headerStubId) ?: return false
        return h !is ViewStub && h.visibility == View.VISIBLE
    }

    // ── Drawing ──

    private fun show(container: View, bmp: Bitmap?, atEnd: Boolean) {
        val base = (container.getTag(basePadTag) as? IntArray)
            ?: intArrayOf(container.paddingLeft, container.paddingTop, container.paddingRight, container.paddingBottom)
                .also { container.setTag(basePadTag, it) }
        val extra = sizePx + gapPx
        val left = if (atEnd) base[0] else base[0] + extra
        val right = if (atEnd) base[2] + extra else base[2]
        if (container.paddingLeft != left || container.paddingRight != right) container.setPadding(left, base[1], right, base[3])
        var d = container.getTag(avatarTag) as? AvatarDrawable
        if (d == null) {
            d = AvatarDrawable()
            container.overlay.add(d)
            container.setTag(avatarTag, d)
            container.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
                (v.getTag(avatarTag) as? AvatarDrawable)?.let { place(v, it) }
            }
        }
        d.set(bmp, defaultAvatar, atEnd)
        d.shown = true
        place(container, d)
        container.invalidate()
        HookLog.hit("MessageAvatars/rows")
    }

    /** Bottom-aligned with the content, inside the padding the bind opened. */
    private fun place(container: View, d: AvatarDrawable) {
        if (!d.shown) return
        val base = container.getTag(basePadTag) as? IntArray ?: return
        val x = if (d.atEnd) container.width - base[2] - sizePx else base[0]
        val y = container.height - base[3] - sizePx
        d.setBounds(x, y, x + sizePx, y + sizePx)
    }

    private fun hide(container: View) {
        val d = container.getTag(avatarTag) as? AvatarDrawable ?: return
        if (!d.shown) return
        d.shown = false
        d.setBounds(0, 0, 0, 0)
        (container.getTag(basePadTag) as? IntArray)?.let { b -> container.setPadding(b[0], b[1], b[2], b[3]) }
        container.invalidate()
    }

    /** Thumbnails are tiny; decoded on the bind thread once per file and kept while the file's timestamp holds. */
    private fun bitmapFor(file: File): Bitmap? {
        if (!file.isFile) return null
        val key = file.path
        val stamp = file.lastModified()
        bitmaps.get(key)?.let { if (it.stamp == stamp) return it.bitmap }
        val bmp = runCatching { BitmapFactory.decodeFile(key) }.getOrNull() ?: return null
        bitmaps.put(key, Cached(bmp, stamp))
        return bmp
    }

    private fun logOnce(msg: String) {
        if (logged.add(msg.take(120))) XposedBridge.log("$TAG: $msg")
    }

    /** A circle of the thumbnail, or WhatsApp's own placeholder when there is none. */
    private class AvatarDrawable : Drawable() {
        var shown = false
        var atEnd = false
        private var bitmap: Bitmap? = null
        private var fallback: Drawable? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()

        fun set(b: Bitmap?, fb: Drawable?, end: Boolean) {
            bitmap = b
            fallback = fb
            atEnd = end
            paint.shader = b?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            invalidateSelf()
        }

        override fun draw(canvas: Canvas) {
            if (!shown) return
            val r = bounds
            if (r.isEmpty) return
            val b = bitmap
            if (b != null) {
                val s = maxOf(r.width().toFloat() / b.width, r.height().toFloat() / b.height)
                matrix.setScale(s, s)
                matrix.postTranslate(r.left + (r.width() - b.width * s) / 2f, r.top + (r.height() - b.height * s) / 2f)
                paint.shader?.setLocalMatrix(matrix)
                canvas.drawCircle(r.exactCenterX(), r.exactCenterY(), r.width() / 2f, paint)
            } else {
                val fb = fallback ?: return
                fb.bounds = r
                fb.draw(canvas)
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }
}
