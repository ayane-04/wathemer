package com.wathemer.app.settings.preview

import android.graphics.BitmapFactory
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.R
import com.wathemer.app.settings.components.BitmapIcon
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.prefs.BubbleStyles

// ── Bubble primitives with real text ────────────────────────

/** Nine-patch or stock rounded-rect bubble bg; never tint colour artwork, mirrors BubbleShapes.tintUnlessColourArt. */
private fun Modifier.bubbleBg(
    shape: Drawable?,
    bg: Color,
    rounded: RoundedCornerShape,
    tintable: Boolean = true,
): Modifier =
    if (shape != null) {
        drawBehind {
            shape.setBounds(0, 0, size.width.toInt(), size.height.toInt())
            shape.colorFilter =
                if (tintable) PorterDuffColorFilter(bg.toArgb(), PorterDuff.Mode.SRC_IN) else null
            drawIntoCanvas { c -> shape.draw(c.nativeCanvas) }
        }
    } else {
        background(bg, rounded)
    }

/** Must mirror BubbleShapes.isColourArtwork exactly; if the thresholds drift the grid stops predicting WhatsApp. */
@Composable
private fun rememberIsColourArt(style: Int, dir: String): Boolean {
    val ctx = LocalContext.current
    return remember(style, dir) {
        if (style <= 0) return@remember false
        val name = (BubbleStyles.assetPrefix(style) ?: return@remember false) + "_balloon_${dir}_normal"
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        if (id == 0) return@remember false
        val bmp = runCatching {
            BitmapFactory.decodeResource(
                ctx.resources, id,
                BitmapFactory.Options().apply { inSampleSize = 4 },
            )
        }.getOrNull() ?: return@remember false
        var opaque = 0; var sat = 0; var sr = 0L; var sg = 0L; var sb = 0L
        for (y in 0 until bmp.height) for (x in 0 until bmp.width) {
            val p = bmp.getPixel(x, y)
            if ((p ushr 24) < 128) continue
            opaque++
            val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
            sr += r; sg += g; sb += b
            if (maxOf(r, g, b) - minOf(r, g, b) > 40) sat++
        }
        bmp.recycle()
        if (opaque == 0) return@remember false
        val mr = (sr / opaque).toInt(); val mg = (sg / opaque).toInt(); val mb = (sb / opaque).toInt()
        (sat * 100 / opaque > 5) || (maxOf(mr, mg, mb) - minOf(mr, mg, mb) > 12)
    }
}

/** Bubble padding from the nine-patch content region (keeps text clear of tail and border), else tail-aware insets. */
@Composable
private fun bubbleContentPadding(shape: Drawable?, tailStart: Boolean): PaddingValues {
    if (shape == null) return PaddingValues(horizontal = 9.dp, vertical = 5.dp)
    val density = LocalDensity.current
    val r = remember(shape) {
        val rect = Rect()
        val ok = shape.getPadding(rect)
        if (ok && (rect.left or rect.top or rect.right or rect.bottom) != 0) rect else null
    }
    return if (r != null) {
        with(density) {
            PaddingValues(
                start = r.left.toDp() + 4.dp,
                top = r.top.toDp() + 4.dp,
                end = r.right.toDp() + 4.dp,
                bottom = r.bottom.toDp() + 4.dp,
            )
        }
    } else if (tailStart) {
        PaddingValues(start = 18.dp, top = 9.dp, end = 12.dp, bottom = 9.dp)
    } else {
        PaddingValues(start = 12.dp, top = 9.dp, end = 18.dp, bottom = 9.dp)
    }
}

@Composable
private fun rememberBubbleShape(style: Int, dir: String): Drawable? {
    val ctx = LocalContext.current
    return remember(style, dir) {
        val prefix = BubbleStyles.assetPrefix(style)
        if (prefix == null) {
            null
        } else {
            val id = ctx.resources.getIdentifier(
                "${prefix}_balloon_${dir}_normal", "drawable", ctx.packageName,
            )
            if (id == 0) null else ctx.getDrawable(id)
        }
    }
}

/** Single-bubble preview for the Bubble shape screen: just the edited side, tinted shape, auto-contrast text. */
@Composable
fun SingleBubblePreview(isOutgoing: Boolean, style: Int, tint: Color, modifier: Modifier = Modifier) {
    val dir = if (isOutgoing) "outgoing" else "incoming"
    val shape = rememberBubbleShape(style, dir)
    // Colour artwork is drawn untinted, so the preview matches what WhatsApp will render.
    val tintable = !rememberIsColourArt(style, dir)
    // onColorFor ignores alpha, so a translucent tint must be composited over the real backdrop first.
    // Do not fix onColorFor instead; its other callers sit on different backdrops.
    val onTint = onColorFor(tint.compositeOver(Palette.Bg).toArgb())
    val dateColor = onTint.copy(alpha = 0.6f)
    Box(modifier = modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start,
        ) {
            if (isOutgoing) {
                OutgoingBubble(tint, onTint, dateColor, "This is your outgoing bubble", "10:14", shape = shape, tintable = tintable)
            } else {
                IncomingBubble(tint, onTint, dateColor, "This is an incoming bubble", "10:14", shape = shape, tintable = tintable)
            }
        }
    }
}

@Composable
private fun IncomingBubble(bg: Color, textColor: Color, dateColor: Color, text: String, time: String, shape: Drawable? = null, tintable: Boolean = true) {
    Box(
        modifier = Modifier
            .widthIn(min = 60.dp, max = 240.dp)
            .bubbleBg(shape, bg, RoundedCornerShape(topStart = 3.dp, topEnd = 10.dp, bottomEnd = 10.dp, bottomStart = 10.dp), tintable)
            .padding(bubbleContentPadding(shape, tailStart = true)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text, color = textColor, fontSize = 11.sp, lineHeight = 14.sp)
            Text(time, color = dateColor, fontSize = 8.sp, modifier = Modifier.align(Alignment.End).padding(top = 1.dp))
        }
    }
}

@Composable
private fun OutgoingBubble(bg: Color, textColor: Color, dateColor: Color, text: String, time: String, shape: Drawable? = null, tintable: Boolean = true) {
    Box(
        modifier = Modifier
            .widthIn(min = 60.dp, max = 240.dp)
            .bubbleBg(shape, bg, RoundedCornerShape(topStart = 10.dp, topEnd = 3.dp, bottomEnd = 10.dp, bottomStart = 10.dp), tintable)
            .padding(bubbleContentPadding(shape, tailStart = false)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(text, color = textColor, fontSize = 11.sp, lineHeight = 14.sp)
            Row(modifier = Modifier.align(Alignment.End).padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(time, color = dateColor, fontSize = 8.sp)
                Spacer(Modifier.size(4.dp))
                DoubleTick(dateColor)
            }
        }
    }
}

// ── Glyphs ───────────────────────────────────────────────

@Composable
internal fun BackArrow(tint: Color) = BitmapIcon(R.drawable.ic_ui_back, tint, 15.dp)

@Composable
internal fun VideoIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_video, tint, 16.dp)

@Composable
internal fun PhoneIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_phone, tint, 13.dp)

@Composable
internal fun MenuDotsIcon(tint: Color) {
    Canvas(modifier = Modifier.size(width = 4.dp, height = 14.dp)) {
        val w = size.width; val h = size.height
        val r = 1.4.dp.toPx()
        drawCircle(tint, r, center = Offset(w / 2, h * 0.2f))
        drawCircle(tint, r, center = Offset(w / 2, h * 0.5f))
        drawCircle(tint, r, center = Offset(w / 2, h * 0.8f))
    }
}

@Composable
internal fun EmojiIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_emoji, tint, 14.dp)

@Composable
internal fun AttachIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_attach, tint, 13.dp)

@Composable
internal fun CameraIcon(tint: Color) = BitmapIcon(R.drawable.ic_wa_camera, tint, 13.dp)

@Composable
internal fun DoubleTick(tint: Color) {
    Canvas(modifier = Modifier.size(width = 11.dp, height = 7.dp)) {
        val w = size.width; val h = size.height
        val s = 1.1.dp.toPx()
        drawLine(tint, Offset(0f, h * 0.5f), Offset(w * 0.3f, h * 0.95f), s)
        drawLine(tint, Offset(w * 0.3f, h * 0.95f), Offset(w * 0.7f, h * 0.05f), s)
        drawLine(tint, Offset(w * 0.32f, h * 0.5f), Offset(w * 0.62f, h * 0.95f), s)
        drawLine(tint, Offset(w * 0.62f, h * 0.95f), Offset(w, h * 0.05f), s)
    }
}

@Composable
internal fun ThinRule(color: Color) {
    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(color))
}

/** Picker-grid thumb: real nine-patch under the hook's tint rule; cheap, the grid shows NAMES.size cells one side at a time. */
@Composable
fun BubbleThumb(style: Int, isOutgoing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    val dir = if (isOutgoing) "outgoing" else "incoming"
    val shape = rememberBubbleShape(style, dir) ?: return
    val tintable = !rememberIsColourArt(style, dir)
    Box(
        modifier = modifier
            .widthIn(min = 52.dp, max = 96.dp)
            .fillMaxWidth()
            .height(46.dp)
            .bubbleBg(shape, tint, RoundedCornerShape(8.dp), tintable),
    )
}
