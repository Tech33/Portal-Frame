package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Manages over-the-air installation of companion apps onto the Meta Portal,
 * specifically the native Spotify Connect Aloha app (com.facebook.aloha.spotifystandalone)
 * and custom sideloaded APKs, completely without needing a USB cable or MacBook connection.
 */
object CompanionAppInstaller {

    private const val TAG = "CompanionAppInstaller"

    val SPOTIFY_PACKAGES = listOf(
        "com.facebook.aloha.spotifystandalone",
        "com.spotify.music",
        "com.spotify.tv.android",
        "com.spotify.lite"
    )

    const val OFFICIAL_SPOTIFY_RELEASE_URL =
        "https://github.com/Tech33/Portal-Frame/releases/latest/download/spotify.apk"

    fun isSpotifyInstalled(context: Context): Boolean {
        val pm = context.packageManager
        for (pkg in SPOTIFY_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return true
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return false
    }

    fun getInstalledSpotifyPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in SPOTIFY_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {}
        }
        return null
    }

    fun launchSpotify(context: Context): Boolean {
        val pkg = getInstalledSpotifyPackage(context) ?: return false
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: Intent().apply {
                setClassName(pkg, "$pkg.MainActivity")
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed launching Spotify ($pkg)", e)
            false
        }
    }

    /**
     * Terminates background Spotify processes and stops active playback
     * when the media widget idle timeout expires, saving memory and battery on Portal Go.
     */
    fun killSpotify(context: Context) {
        try {
            // 1. Dispatch standard Android Media Stop key event
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            audioManager?.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_STOP)
            )
            audioManager?.dispatchMediaKeyEvent(
                android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_STOP)
            )

            // 2. Kill background processes via ActivityManager
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            for (pkg in SPOTIFY_PACKAGES) {
                am?.killBackgroundProcesses(pkg)
            }

            // 3. Fallback shell force-stop
            for (pkg in SPOTIFY_PACKAGES) {
                try {
                    Runtime.getRuntime().exec(arrayOf("am", "force-stop", pkg))
                } catch (_: Exception) {}
            }
            Log.i(TAG, "Successfully terminated background Spotify processes")
        } catch (e: Exception) {
            Log.w(TAG, "Failed terminating Spotify background processes", e)
        }
    }

    private fun openConnectionWithRedirects(initialUrl: String, maxRedirects: Int = 5): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < maxRedirects) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = false // Manually handle cross-protocol/cross-host redirects (e.g. GitHub to AWS S3)
                setRequestProperty("User-Agent", "Portal-Frame")
            }
            val status = conn.responseCode
            if (status in 301..308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank()) {
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(URL(currentUrl), location).toString()
                    }
                    redirects++
                    continue
                }
            }
            return conn
        }
        throw Exception("Too many redirects ($redirects) from $initialUrl")
    }

    fun installSpotify(
        context: Context,
        fallbackUrl: String? = null,
        onStatus: (String) -> Unit = {}
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        val candidateUrls = if (fallbackUrl != null) {
            listOf(fallbackUrl)
        } else {
            listOf(
                OFFICIAL_SPOTIFY_RELEASE_URL,
                "https://raw.githubusercontent.com/Tech33/Portal-Frame/main/spotify.apk"
            )
        }

        mainHandler.post { onStatus("Connecting to download server…") }

        thread(name = "Spotify-Downloader") {
            var lastError: Exception? = null
            var success = false

            for (targetUrl in candidateUrls) {
                try {
                    val destFile = File(context.cacheDir, "spotify_installer.apk")
                    if (destFile.exists()) destFile.delete()

                    mainHandler.post { onStatus("Downloading Spotify for Meta Portal…") }

                    val conn = openConnectionWithRedirects(targetUrl)
                    if (conn.responseCode !in 200..299) {
                        val code = conn.responseCode
                        conn.disconnect()
                        throw Exception("HTTP error $code from $targetUrl")
                    }

                    val totalBytes = conn.contentLength
                    var downloadedBytes = 0L

                    conn.inputStream.use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(8192)
                            var bytesRead: Int
                            var lastReport = System.currentTimeMillis()

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead
                                val now = System.currentTimeMillis()
                                if (now - lastReport > 500L && totalBytes > 0) {
                                    val pct = (downloadedBytes * 100 / totalBytes).toInt()
                                    mainHandler.post { onStatus("Downloading Spotify ($pct%)…") }
                                    lastReport = now
                                }
                            }
                        }
                    }
                    conn.disconnect()

                    if (destFile.length() < 100_000) {
                        throw Exception("Downloaded file too small (${destFile.length()} bytes)")
                    }

                    mainHandler.post { onStatus("Installing Spotify automatically…") }

                    // Arm the accessibility service to auto-click Install/Update
                    ScreenControl.enableAccessibility(context)
                    PortalAccessibilityService.armAutoInstall()

                    val launched = UpdateInstaller.promptInstall(context, destFile)
                    mainHandler.post {
                        if (launched) {
                            onStatus("Installing Spotify in background…")
                        } else {
                            onStatus("Permission needed: Allow installing unknown apps.")
                        }
                    }
                    success = true
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Failed downloading Spotify from $targetUrl: ${e.message}")
                    lastError = e
                }
            }

            if (!success) {
                Log.e(TAG, "All Spotify download URLs failed", lastError)
                mainHandler.post { onStatus("Installation failed: ${lastError?.message ?: "network error"}") }
            }
        }
    }

    fun installFromUrl(
        context: Context,
        apkUrl: String,
        onStatus: (String) -> Unit = {}
    ) {
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post { onStatus("Downloading custom APK…") }

        thread(name = "Custom-APK-Downloader") {
            try {
                val destFile = File(context.cacheDir, "custom_app.apk")
                if (destFile.exists()) destFile.delete()

                val conn = openConnectionWithRedirects(apkUrl)
                if (conn.responseCode !in 200..299) {
                    val code = conn.responseCode
                    conn.disconnect()
                    throw Exception("HTTP $code")
                }

                conn.inputStream.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                mainHandler.post { onStatus("Installing app automatically…") }

                ScreenControl.enableAccessibility(context)
                PortalAccessibilityService.armAutoInstall()

                val launched = UpdateInstaller.promptInstall(context, destFile)
                mainHandler.post {
                    if (launched) {
                        onStatus("Installing app in background…")
                    } else {
                        onStatus("Installation prompt opened.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed installing from URL: $apkUrl", e)
                mainHandler.post { onStatus("Install failed: ${e.message}") }
            }
        }
    }
}
