// Opt-in snowfall over WhatsApp's content. Keep it in front: an underlay never shows through the panes.
package com.wathemer.app.hooks

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.wathemer.app.settings.prefs.Prefs
import de.robv.android.xposed.XposedBridge
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

object EffectsOverlays {
    private const val TAG = "WaThemer.Effects"

    /** Attach guard, one snow layer per window; the wt_ prefix keeps the wallpaper machinery's hands off it. */
    private const val SNOW_TAG = "wt_effect_snow"

    fun install() {
        val xprefs = ModulePrefs.open()
        if (!xprefs.getBoolean(Prefs.KEY_EFFECT_SNOW, false)) return
        // Dispatched after the wallpaper client; the flakes sit in content, above the wallpaper, whichever attaches first.
        ActivityLifecycle.onCreated("snow") { a ->
            if (a.packageName == "com.whatsapp") {
                runCatching { attach(a) }
            }
        }
        XposedBridge.log("$TAG: snow armed")
    }

    /** Topmost child of content: over the app, under the system bars, tap-transparent. */
    private fun attach(a: Activity) {
        val content = a.window?.decorView?.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(SNOW_TAG) != null) return
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        content.addView(SnowView(a).apply { tag = SNOW_TAG }, lp)
    }

    /** Depth-bound sprites: distant flakes are soft dots, near ones six-armed crystals that slowly rotate. */
    private class SnowView(ctx: Context) : View(ctx) {
        private class Flake(
            var x: Float,
            var y: Float,
            val scale: Float,
            val vy: Float,
            var phase: Float,
            val swayAmp: Float,
            val swayRate: Float,
            val alpha: Int,
            val sprite: Int,
            var rot: Float,
            val rotRate: Float,
        )

        private val flakes = ArrayList<Flake>(COUNT)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dst = RectF()
        private val rnd = Random()
        private var running = false
        private val sprites: Array<Bitmap> by lazy { arrayOf(softDisc(), crystal(0.55f, 0.30f), crystal(0.68f, 0.24f)) }
        private val step = object : Runnable {
            override fun run() {
                if (!running) return
                advance()
                invalidate()
                postOnAnimation(this)
            }
        }

        init {
            isClickable = false
            isFocusable = false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = false

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            start()
        }

        override fun onDetachedFromWindow() {
            running = false
            super.onDetachedFromWindow()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == VISIBLE) start() else running = false
        }

        private fun start() {
            if (!running) {
                running = true
                postOnAnimation(step)
            }
        }

        /** A radial white-to-clear disc; the soft edge is what separates distant snow from blobs. */
        private fun softDisc(): Bitmap {
            val size = (14f * resources.displayMetrics.density).toInt().coerceAtLeast(24)
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val r = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = RadialGradient(
                r, r, r,
                intArrayOf(0xFFFFFFFF.toInt(), 0xB3FFFFFF.toInt(), 0x00FFFFFF),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP,
            )
            Canvas(b).drawCircle(r, r, r, p)
            return b
        }

        /** A six-armed dendrite: glow pass under a bright core, two side branches per arm, a small centre. */
        private fun crystal(branchAt: Float, branchLen: Float): Bitmap {
            val size = (22f * resources.displayMetrics.density).toInt().coerceAtLeast(44)
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val cx = size / 2f
            val arm = cx * 0.92f
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); alpha = 64
                strokeWidth = arm * 0.17f; strokeCap = Paint.Cap.ROUND
            }
            val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); alpha = 232
                strokeWidth = arm * 0.07f; strokeCap = Paint.Cap.ROUND
            }
            for (k in 0 until 6) {
                val ang = Math.toRadians(k * 60.0)
                val ex = cx + cos(ang).toFloat() * arm
                val ey = cx + sin(ang).toFloat() * arm
                c.drawLine(cx, cx, ex, ey, glow)
                c.drawLine(cx, cx, ex, ey, core)
                val bx = cx + cos(ang).toFloat() * arm * branchAt
                val by = cx + sin(ang).toFloat() * arm * branchAt
                for (s in intArrayOf(-1, 1)) {
                    val ba = ang + s * Math.toRadians(38.0)
                    c.drawLine(
                        bx, by,
                        bx + cos(ba).toFloat() * arm * branchLen,
                        by + sin(ba).toFloat() * arm * branchLen,
                        core,
                    )
                }
            }
            c.drawCircle(cx, cx, arm * 0.10f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
            return b
        }

        private fun advance() {
            if (width == 0 || height == 0) return
            val d = resources.displayMetrics.density
            if (flakes.isEmpty()) repeat(COUNT) { flakes.add(newFlake(d, rnd.nextFloat() * height)) }
            for (i in flakes.indices) {
                val f = flakes[i]
                f.phase += f.swayRate
                f.y += f.vy
                f.x += sin(f.phase) * f.swayAmp
                f.rot += f.rotRate
                val r = SPRITE_HALF_DP * d * f.scale
                if (f.y - r > height) flakes[i] = newFlake(d, -2f * r)
            }
        }

        private fun newFlake(d: Float, y: Float): Flake {
            // Depth in one number: speed, size, brightness and detail all derive from scale.
            val scale = 0.3f + rnd.nextFloat() * 0.7f
            val crystal = scale > 0.62f
            return Flake(
                x = rnd.nextFloat() * width,
                y = y,
                scale = scale,
                vy = (0.35f + 1.5f * scale + rnd.nextFloat() * 0.3f) * d,
                phase = rnd.nextFloat() * (Math.PI * 2).toFloat(),
                swayAmp = (0.15f + rnd.nextFloat() * 0.35f) * d * scale,
                swayRate = 0.01f + rnd.nextFloat() * 0.03f,
                alpha = (70 + 165 * scale).toInt(),
                sprite = if (crystal) 1 + rnd.nextInt(2) else 0,
                rot = rnd.nextFloat() * 360f,
                rotRate = if (crystal) (rnd.nextFloat() - 0.5f) * 1.2f else 0f,
            )
        }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            // Indexed like advance(): for-in builds an Iterator per frame on a 60-120Hz path.
            for (i in flakes.indices) {
                val f = flakes[i]
                val r = SPRITE_HALF_DP * d * f.scale * (if (f.sprite == 0) 1f else 1.5f)
                dst.set(f.x - r, f.y - r, f.x + r, f.y + r)
                paint.alpha = f.alpha
                if (f.rotRate != 0f) {
                    canvas.save()
                    canvas.rotate(f.rot, f.x, f.y)
                    canvas.drawBitmap(sprites[f.sprite], null, dst, paint)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(sprites[f.sprite], null, dst, paint)
                }
            }
        }

        private companion object {
            const val COUNT = 90
            const val SPRITE_HALF_DP = 3.5f
        }
    }
}
