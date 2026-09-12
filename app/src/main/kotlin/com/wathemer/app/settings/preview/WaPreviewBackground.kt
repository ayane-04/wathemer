// Chat wallpaper layer: the user's real wallpaper when set, a soft doodle fallback otherwise.
// Known limit: settings reads wallpaperPath directly, the hook uses WallpaperResolver, and the two can disagree.
package com.wathemer.app.settings.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wathemer.app.hooks.wallpaper.BitmapDecoder
import com.wathemer.app.hooks.wallpaper.WallpaperImage
import java.io.File

/** Converts the hook's px blur to dp and scales it to preview size; skipping either correction misleads. */
@Composable
internal fun previewBlurDp(blurPx: Int, previewHeightPx: Float): Dp {
    val density = LocalDensity.current
    val screenPx = LocalContext.current.resources.displayMetrics.heightPixels.toFloat()
    val scaled = if (screenPx > 0f && previewHeightPx > 0f) {
        blurPx * (previewHeightPx / screenPx)
    } else {
        blurPx.toFloat()
    }
    return with(density) { scaled.toDp() }
}

/** Chat backdrop: real wallpaper when usable, else the doodle, so every path has a backdrop. */
@Composable
fun ChatWallpaper(
    baseColor: Color,
    doodleColor: Color,
    snap: ThemeSnapshot? = null,
    modifier: Modifier = Modifier,
) {
    val path = snap?.wallpaperPath
        ?.takeIf { snap.wallpaperEnabled && it.isNotBlank() }
        ?.takeIf { runCatching { File(it).canRead() }.getOrDefault(false) }

    // Decode once per path, at a size under the thumbnail decode already on this thread.
    val bitmap = remember(path) {
        path?.let { BitmapDecoder.decodeScaled(File(it), 600, 600) }
    }

    if (bitmap == null) {
        DoodleWallpaper(baseColor, doodleColor, modifier)
        return
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(baseColor)) {
        val blurDp = previewBlurDp(snap?.wallpaperBlur ?: 0, with(LocalDensity.current) { maxHeight.toPx() })
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = if (blurDp.value > 0.1f) {
                Modifier.fillMaxSize().blur(blurDp)
            } else {
                Modifier.fillMaxSize()
            },
            contentScale = ContentScale.Crop,
        )
        val dim = snap?.wallpaperDim ?: 0
        if (dim > 0) {
            // Same colour constant the injector uses, so the dim reads the same here as in WhatsApp.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(WallpaperImage.DIM_COLOR).copy(alpha = dim / 100f)),
            )
        }
    }
}

/** Faint deterministic doodle; low alpha so bubble translucency shows, no seed so it is stable. */
@Composable
private fun DoodleWallpaper(
    baseColor: Color,
    doodleColor: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize().background(baseColor)) {
        val w = size.width
        val h = size.height
        val faint = doodleColor.copy(alpha = 0.04f)
        val faintBright = doodleColor.copy(alpha = 0.06f)
        val stroke = 1.2.dp.toPx()
        val gridX = 26.dp.toPx()
        val gridY = 28.dp.toPx()
        var rowIndex = 0
        var y = 6.dp.toPx()
        while (y < h) {
            val offsetX = if (rowIndex % 2 == 0) 0f else gridX / 2
            var x = offsetX
            while (x < w) {
                when ((rowIndex + (x / gridX).toInt()) % 4) {
                    0 -> drawCircle(faintBright, radius = 1.4.dp.toPx(), center = Offset(x, y))
                    1 -> {
                        // tiny tick mark
                        drawLine(faint, Offset(x - 3.dp.toPx(), y), Offset(x + 3.dp.toPx(), y), stroke)
                    }
                    2 -> {
                        // diagonal stroke
                        drawLine(faint, Offset(x - 4.dp.toPx(), y - 3.dp.toPx()), Offset(x + 4.dp.toPx(), y + 3.dp.toPx()), stroke)
                    }
                    else -> {
                        // small donut
                        drawCircle(faint, radius = 2.dp.toPx(), center = Offset(x, y), style = Stroke(stroke))
                    }
                }
                x += gridX
            }
            y += gridY
            rowIndex++
        }
    }
}
