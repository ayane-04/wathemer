// Release checks against GitHub, and the download behind them. Nothing here runs unless the settings
// app is open: the module inside WhatsApp never touches the network.
package com.wathemer.app.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.FileProvider
import com.wathemer.app.BuildConfig
import com.wathemer.app.R
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

private const val TAG = "WaThemer.Update"

/** A published release: the version its tag names, the notes, and the APK to fetch. */
data class Release(
    val version: String,
    val notes: String,
    val assetUrl: String,
    val assetName: String,
    /** From the release listing or the cache; zero means unknown and skips the length check. */
    val size: Long = 0L,
)

object Updates {

    private const val RELEASES = "https://api.github.com/repos/ayane-04/WaThemer/releases"

    /** Only GitHub, over https: the store that feeds a cached release is not trusted to name the host. */
    private val ALLOWED_HOSTS = setOf(
        "api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com",
    )

    /** Where the download lands. Must stay in step with the cache-path the FileProvider declares. */
    private const val DIR = "updates"

    private const val CHANNEL = "updates"

    private const val NOTIFICATION = 4201

    /** The asset being fetched, or null. Process-wide: a rebuilt screen must not re-arm the button mid-copy. */
    val inFlight = mutableStateOf<String?>(null)

    private val main = Handler(Looper.getMainLooper())

    /** One worker: the directory is wiped per download, so two at once would destroy each other whatever their names. */
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "wathemer-update") }

    /** Runs the fetch off the screen's lifetime; the toast and the notification come from here, so leaving the screen loses neither. */
    fun startDownload(context: Context, release: Release) {
        if (inFlight.value != null) return
        inFlight.value = release.assetName
        val app = context.applicationContext
        worker.execute {
            val file = runCatching { download(app, release) }.getOrNull()
            main.post {
                inFlight.value = null
                if (file == null) {
                    Toast.makeText(app, "Download failed", Toast.LENGTH_SHORT).show()
                } else {
                    notifyDownloaded(app, file, release)
                    Toast.makeText(app, "Downloaded ${release.version}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

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
                        size = asset.optLong("size"),
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
        // Staged then renamed: a process death mid-copy must not leave a stub that the Install row offers.
        val part = File(dir, release.assetName + ".part")
        return runCatching {
            open(release.assetUrl).use { c ->
                if (c.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.w(TAG, "download returned ${c.responseCode}")
                    return null
                }
                c.inputStream.use { input -> part.outputStream().use(input::copyTo) }
            }
            if (release.size > 0L && part.length() != release.size) {
                Log.w(TAG, "download is ${part.length()} bytes, the listing said ${release.size}")
                part.delete()
                return null
            }
            if (!part.renameTo(target)) {
                Log.w(TAG, "could not rename the finished download into place")
                part.delete()
                return null
            }
            target
        }.onFailure {
            Log.w(TAG, "download failed: $it")
            part.delete()
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
    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        // Host equality, never a substring: a user-info trick parses github.com out of a foreign host.
        require(u.protocol == "https" && u.host.lowercase() in ALLOWED_HOSTS) { "refused url host ${u.host}" }
        return (u.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "WaThemer/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
    }

    private inline fun <T> HttpURLConnection.use(body: (HttpURLConnection) -> T): T =
        try { body(this) } finally { disconnect() }
}
