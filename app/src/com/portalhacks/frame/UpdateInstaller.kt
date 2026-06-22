package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads a signed release APK from GitHub and launches the system installer.
 * APK bytes are capped; optional SHA-256 verification uses the manifest hash.
 */
internal object UpdateInstaller {

    private const val TAG = "PortalFrame"
    private const val MAX_APK_BYTES = 80L * 1024 * 1024

    sealed class Result {
        data class Ready(val file: File) : Result()
        data class Error(val message: String) : Result()
    }

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
                    out.close()
                    input.close()
                    tmp.delete()
                    return Result.Error("Download exceeded size limit.")
                }
                out.write(buf, 0, n)
            }
            out.flush()
            out.close()
            input.close()
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

    /**
     * Launch the package installer for [apk]. On Android 8+ may send the user to
     * "Install unknown apps" settings first if Frame isn't allowed to install APKs.
     */
    fun promptInstall(context: Context, apk: File): Boolean {
        if (!apk.isFile) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch app-specific unknown app sources setting, trying generic list", e)
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed to launch unknown app sources list, trying security settings", e2)
                    try {
                        val intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    } catch (e3: Exception) {
                        Log.e(TAG, "Failed to launch security settings", e3)
                    }
                }
            }
            return false
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

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
