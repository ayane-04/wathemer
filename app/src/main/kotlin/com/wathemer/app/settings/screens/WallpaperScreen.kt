// Custom wallpaper settings screen: enable toggle, gallery pick via UCrop to Downloads, dim, blur, preview.
// Turning the wallpaper off turns Liquid Glass off with it; glass has nothing to draw without one.
package com.wathemer.app.settings.screens

import android.app.Activity
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wathemer.app.hooks.wallpaper.BitmapDecoder
import com.wathemer.app.hooks.wallpaper.WallpaperImage
import com.wathemer.app.settings.components.MenuRow
import com.wathemer.app.settings.components.NavTopBar
import com.wathemer.app.settings.components.Palette
import com.wathemer.app.settings.components.PreviewPanel
import com.wathemer.app.settings.components.SectionHeader
import com.wathemer.app.settings.components.SliderItem
import com.wathemer.app.settings.components.ToggleItem
import com.wathemer.app.settings.nav.NavController
import com.wathemer.app.settings.prefs.Prefs
import com.wathemer.app.settings.prefs.WallpaperAsset
import com.wathemer.app.settings.preview.LocalThemeSnapshot
import com.wathemer.app.settings.preview.previewBlurDp
import com.wathemer.app.settings.preview.updateWallpaperBlur
import com.wathemer.app.settings.preview.updateWallpaperDim
import com.wathemer.app.settings.preview.updateWallpaperEnabled
import com.wathemer.app.settings.preview.updateWallpaperPath
import com.yalantis.ucrop.UCrop
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun WallpaperScreen(nav: NavController, prefs: Prefs) {
    val context = LocalContext.current
    val snapshot = LocalThemeSnapshot.current
    val snap by snapshot

    // Self-repair once per entry; keyed on the path so a re-pick re-checks.
    LaunchedEffect(snap.wallpaperPath) {
        // The restore copy must run off main; the pref write after it must stay on main for Compose state.
        val hadGlass = prefs.glassEnabled
        val settled = withContext(Dispatchers.IO) { WallpaperAsset.reconcile(context, prefs) }
        if (settled != snap.wallpaperPath) snapshot.updateWallpaperPath(prefs, settled)
        // The prune takes glass with it, so say so here; the toggle below announces the same thing.
        if (hadGlass && !prefs.glassEnabled) {
            Toast.makeText(
                context,
                "Liquid Glass turned off too, it needs a wallpaper.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    val cropLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        // Every failure gets a message; only RESULT_CANCELED stays silent, there silence is right.
        when {
            result.resultCode == Activity.RESULT_OK && result.data != null -> {
                val cropped = UCrop.getOutput(result.data!!)
                val dest = cropped?.let { src ->
                    WallpaperAsset.persist(context) { context.contentResolver.openInputStream(src) }
                }
                when {
                    cropped == null -> {
                        Log.w(LOGTAG, "crop returned OK with no output Uri")
                        Toast.makeText(context, "Couldn't read the cropped image.", Toast.LENGTH_LONG).show()
                    }
                    dest == null ->
                        Toast.makeText(context, "Couldn't save the wallpaper. Storage may be full.", Toast.LENGTH_LONG).show()
                    else -> {
                        snapshot.updateWallpaperPath(prefs, dest.absolutePath)
                        if (!snap.wallpaperEnabled) {
                            snapshot.updateWallpaperEnabled(prefs, true)
                        }
                    }
                }
            }
            // UCrop shows nothing itself on failure; without this arm a bad image closes the crop screen unexplained.
            result.resultCode == UCrop.RESULT_ERROR -> {
                Log.w(LOGTAG, "crop failed: ${result.data?.let { UCrop.getError(it) }}")
                Toast.makeText(context, "Couldn't crop that image. Try another one.", Toast.LENGTH_LONG).show()
            }
        }
    }
    val pickLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            val tempDest = File(context.cacheDir, "ucrop_wallpaper_${System.currentTimeMillis()}.png")
            val dm = context.resources.displayMetrics
            val cropIntent = UCrop.of(uri, Uri.fromFile(tempDest))
                .withAspectRatio(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
                .withMaxResultSize(dm.widthPixels, dm.heightPixels)
                .getIntent(context)
            cropLauncher.launch(cropIntent)
        }
    }

    Scaffold(containerColor = Palette.Bg) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            NavTopBar(
                title = "Wallpaper",
                subtitle = if (snap.wallpaperEnabled) "Enabled" else "Disabled",
                onBack = { nav.pop() },
            )
            PreviewPanel(modifier = Modifier.weight(0.4f).fillMaxWidth()) {
                WallpaperThumbnail(
                    path = snap.wallpaperPath,
                    stamp = snap.wallpaperStamp,
                    dim = snap.wallpaperDim,
                    blur = snap.wallpaperBlur,
                )
            }
            Column(
                modifier = Modifier
                    .weight(0.6f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SectionHeader(
                    title = "Custom wallpaper",
                    subtitle = "Bubbles, header and input bar go translucent so it shows through.",
                )
                ToggleItem(
                    title = "Enable",
                    subtitle = "Show custom background in WhatsApp",
                    checked = snap.wallpaperEnabled,
                    onCheckedChange = { newChecked ->
                        snapshot.updateWallpaperEnabled(prefs, newChecked)
                        // Glass cannot turn on without a wallpaper; wallpaper off must switch glass off with it, announced by toast.
                        if (!newChecked && prefs.glassEnabled) {
                            prefs.glassEnabled = false
                            // Two lines max, Android truncates anything longer.
                            Toast.makeText(
                                context,
                                "Liquid Glass turned off too, it needs a wallpaper.",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                )
                MenuRow(
                    title = "Pick image",
                    subtitle = if (snap.wallpaperPath.isNullOrBlank()) {
                        "Choose from gallery"
                    } else {
                        "Replace current image"
                    },
                    onClick = {
                        if (!WallpaperAsset.hasAllFilesAccess()) {
                            WallpaperAsset.requestAllFilesAccess(context)
                        } else {
                            pickLauncher.launch("image/*")
                        }
                    },
                )
                SliderItem(
                    title = "Dim",
                    subtitle = "Darken the wallpaper",
                    value = snap.wallpaperDim.toFloat(),
                    range = 0f..100f,
                    steps = 99,
                    onValueChange = { v -> snapshot.updateWallpaperDim(prefs, v.roundToInt()) },
                    valueLabel = { "${it.toInt()}%" },
                )
                // Range 0-150 must match Prefs.wallpaperBlur and WallpaperImage; the high ceiling is the glass frost.
                SliderItem(
                    title = "Blur",
                    subtitle = "Softens the wallpaper. Also acts as the glass frost.",
                    value = snap.wallpaperBlur.toFloat(),
                    range = 0f..150f,
                    steps = 149,
                    onValueChange = { v -> snapshot.updateWallpaperBlur(prefs, v.roundToInt()) },
                    valueLabel = { "${it.toInt()} px" },
                )
            }
        }
    }
}

@Composable
private fun WallpaperThumbnail(path: String?, stamp: Long, dim: Int, blur: Int) {
    if (path.isNullOrBlank() || !File(path).canRead()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "No wallpaper picked",
                    color = Palette.FgMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.4.sp,
                )
                Text(
                    "Tap \"Pick image\" below to choose one",
                    color = Palette.FgSubtle,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        return
    }
    // Keyed on the stamp too: the picker always writes the same file, so the path alone never changes.
    val bitmap = remember(path, stamp) {
        BitmapDecoder.decodeScaled(File(path), 800, 800)
    }
    if (bitmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Could not decode image", color = Palette.FgMuted, fontSize = 13.sp)
        }
        return
    }
    // Blur must be scaled twice, for px vs dp and for thumbnail size; unscaled it reads roughly 8x too soft.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Shared with the chat previews (previewBlurDp) so the two cannot drift apart.
        val blurDp = previewBlurDp(blur, with(LocalDensity.current) { maxHeight.toPx() })

        val imageModifier = if (blurDp.value > 0.1f) {
            Modifier.fillMaxSize().blur(blurDp)
        } else {
            Modifier.fillMaxSize()
        }
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Wallpaper preview",
            modifier = imageModifier,
            contentScale = ContentScale.Crop,
        )
        if (dim > 0) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(WallpaperImage.DIM_COLOR).copy(alpha = dim / 100f)),
            )
        }
        // Caption, so nobody takes the preview for the real thing.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                "Preview only",
                color = Color.White.copy(alpha = 0.9f),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.3.sp,
            )
        }
    }
}

private const val LOGTAG = "WaThemer.Wallpaper"
