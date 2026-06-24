package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import rikka.shizuku.Shizuku
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a signed release APK from GitHub and installs it.
 *
 * Install strategy (tried in order):
 *  1. **Shizuku** — silent install via the ADB-shell PackageInstaller session.
 *     Requires Shizuku to be running (started once by provision.command).
 *     Completely bypasses the Meta Portal's broken installer dialog.
 *  2. **Intent fallback** — fires ACTION_VIEW for the APK; shows the system
 *     installer dialog (broken/invisible on some Portal models).
 */
internal object UpdateInstaller {

    private const val TAG = "PortalFrame"
    private const val MAX_APK_BYTES = 80L * 1024 * 1024

    // Action broadcast back to InstallStatusReceiver when a session commits.
    const val ACTION_INSTALL_STATUS = "com.portalhacks.frame.INSTALL_STATUS"
    const val EXTRA_STATUS = "status"
    const val EXTRA_MESSAGE = "message"

    sealed class Result {
        data class Ready(val file: File) : Result()
        data class Error(val message: String) : Result()
    }

    // ── Shizuku availability ─────────────────────────────────────────────────

    /** True when Shizuku is installed, running, and Frame has been granted permission. */
    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /** True when Shizuku is installed but permission hasn't been granted yet. */
    fun needsShizukuPermission(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestShizukuPermission(requestCode: Int) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) { }
    }

    // ── Download ─────────────────────────────────────────────────────────────

    /** Download [manifest.apkUrl] into [Context.cacheDir]. */
    fun download(context: Context, manifest: UpdateChecker.UpdateManifest): Result {
        val url = manifest.apkUrl
        if (!url.startsWith("https://")) {
            return Result.Error("Refusing non-HTTPS download URL.")
        }
        val cacheDir = context.externalCacheDir ?: context.cacheDir
        val dest = File(cacheDir, "frame-update.apk")
        dest.delete()
        var c: HttpURLConnection? = null
        try {
            c = URL(url).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 20000
            c.readTimeout = 120000
            c.setRequestProperty("User-Agent", ImageLoader.UA)
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return Result.Error("Download failed (HTTP $code).")
            }
            val tmp = File(dest.absolutePath + ".tmp")
            val input = BufferedInputStream(c.inputStream)
            val out = FileOutputStream(tmp)
            val buf = ByteArray(8192)
            var total = 0L
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                total += n
                if (total > MAX_APK_BYTES) {
                    out.close(); input.close(); tmp.delete()
                    return Result.Error("Download exceeded size limit.")
                }
                out.write(buf, 0, n)
            }
            out.flush(); out.close(); input.close()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return Result.Error("Couldn't save the downloaded APK.")
            }
            val expected = manifest.sha256
            if (expected != null) {
                val actual = sha256Hex(dest)
                if (!actual.equals(expected, ignoreCase = true)) {
                    dest.delete()
                    return Result.Error("Download failed integrity check.")
                }
            }
            return Result.Ready(dest)
        } catch (e: Exception) {
            Log.e(TAG, "APK download failed", e)
            dest.delete()
            return Result.Error("Download failed — check your connection.")
        } finally {
            c?.disconnect()
        }
    }

    // ── Install ──────────────────────────────────────────────────────────────

    /**
     * Install [apk] using the best available method.
     * Returns true if Shizuku silent install was attempted (no user action needed),
     * false if the intent fallback was used (user must interact with dialog).
     */
    fun promptInstall(context: Context, apk: File): Boolean {
        if (!apk.isFile) return false

        // 1️⃣ Shizuku path — silent, no dialog
        if (isShizukuReady()) {
            return try {
                installViaShizuku(context, apk)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku install failed, falling back to intent", e)
                installViaIntent(context, apk)
            }
        }

        // 2️⃣ Intent fallback
        return installViaIntent(context, apk)
    }

    // ── Shizuku silent install ───────────────────────────────────────────────

    private fun installViaShizuku(context: Context, apk: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }

        val sessionId = packageInstaller.createSession(params)
        packageInstaller.openSession(sessionId).use { session ->
            // Stream APK bytes into the session
            apk.inputStream().buffered().use { apkIn ->
                session.openWrite("frame-update.apk", 0, apk.length()).use { out ->
                    apkIn.copyTo(out)
                    session.fsync(out)
                }
            }

            // Broadcast result back to InstallStatusReceiver
            val intent = Intent(context, InstallStatusReceiver::class.java).apply {
                action = ACTION_INSTALL_STATUS
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_MUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = android.app.PendingIntent.getBroadcast(
                context, sessionId, intent, flags
            )
            session.commit(pi.intentSender)
        }
        Log.i(TAG, "Shizuku install session committed for session $sessionId")
    }

    // ── Intent fallback ──────────────────────────────────────────────────────

    private fun installViaIntent(context: Context, apk: File): Boolean {
        // On Android 8+, send user to "Install unknown apps" settings if not allowed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            try {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_SECURITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) { }
            }
            return false
        }
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", apk,
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return true
    }

    // ── Utilities ────────────────────────────────────────────────────────────

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        file.inputStream().use { input ->
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }
}
