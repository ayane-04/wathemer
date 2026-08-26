// Release checks against GitHub, and the download behind them. Nothing here runs unless the settings
// app is open: the module inside WhatsApp never touches the network.
package com.wathemer.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.wathemer.app.BuildConfig
import com.wathemer.app.R
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "WaThemer.Update"

/** A published release: the version its tag names, the notes, and the APK to fetch. */
data class Release(
    val version: String,
    val notes: String,
    val assetUrl: String,
    val assetName: String,
)

object Updates {

    private const val RELEASES = "https://api.github.com/repos/ayane-04/WaThemer/releases"

    /** Where the download lands. Must stay in step with the cache-path the FileProvider declares. */
    private const val DIR = "updates"

    private const val CHANNEL = "updates"

    private const val NOTIFICATION = 4201

    /** Blocking, so callers stay on Dispatchers.IO. Null on any failure; the screen reports that as unreachable. */
    fun latest(): Release? = runCatching {
        open(RELEASES).use { c ->
            if (c.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "releases request returned ${c.responseCode}")
                return null
            }
            val releases = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                if (release.optBoolean("draft")) continue
                val assets = release.optJSONArray("assets") ?: continue
                for (j in 0 until assets.length()) {
                    val asset = assets.getJSONObject(j)
                    val name = asset.optString("name")
                    if (!name.endsWith(".apk")) continue
                    return Release(
                        version = release.optString("tag_name").removePrefix("v").trim(),
                        notes = release.optString("body").trim(),
                        assetUrl = asset.optString("browser_download_url"),
                        assetName = name,
                    )
                }
            }
            null
        }
    }.onFailure { Log.w(TAG, "release check failed: $it") }.getOrNull()

    /** Blocking. One file at a time: the directory is emptied first, so a stale APK cannot be installed by mistake. */
    fun download(context: Context, release: Release): File? {
        val dir = File(context.cacheDir, DIR)
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, release.assetName)
        return runCatching {
            open(release.assetUrl).use { c ->
                if (c.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "download returned ${c.responseCode}")
                    return null
                }
                c.inputStream.use { input -> target.outputStream().use(input::copyTo) }
            }
            target
        }.onFailure {
            Log.w(TAG, "download failed: $it")
            target.delete()
        }.getOrNull()
    }

    /** The system installer's own confirm dialog is the install step; this only hands it the file. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", apk)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Best effort: the screen shows the same state and its own Install button, so a denied permission costs nothing. */
    fun notifyDownloaded(context: Context, apk: File, release: Release) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
            val tap = PendingIntent.getActivity(
                context, 0, installIntent(context, apk),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            manager.notify(
                NOTIFICATION,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.mb_ic_download)
                    .setContentTitle("WaThemer ${release.version} downloaded")
                    .setContentText("Tap to install.")
                    .setAutoCancel(true)
                    .setContentIntent(tap)
                    .build(),
            )
        }.onFailure { Log.w(TAG, "notification refused: $it") }
    }

    /** The APK already on disk for this release, or null once it has been cleared or never fetched. */
    fun downloaded(context: Context, release: Release): File? =
        File(File(context.cacheDir, DIR), release.assetName).takeIf { it.isFile && it.length() > 0 }

    /** GitHub answers 403 to a request with no User-Agent, which reads as a network failure if you do not set one. */
    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "WaThemer/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }

    private inline fun <T> HttpURLConnection.use(body: (HttpURLConnection) -> T): T =
        try { body(this) } finally { disconnect() }
}
