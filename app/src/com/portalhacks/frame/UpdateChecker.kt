package com.portalhacks.frame

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches [version.json] from GitHub Releases and compares [UpdateManifest.versionCode]
 * to the installed build. HTTPS-only; manifest size is capped.
 */
internal object UpdateChecker {

    private const val TAG = "PortalFrame"
    private const val MAX_MANIFEST_BYTES = 16 * 1024

    /** Parsed release metadata from version.json on GitHub. */
    data class UpdateManifest(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String?,
        val releaseNotes: String?,
    )

    fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** @return null on network/parse failure or if the manifest is invalid. */
    fun fetchManifest(url: String = ConfigReceiver.UPDATE_MANIFEST_URL): UpdateManifest? {
        return try {
            parseManifest(httpGet(url))
        } catch (e: Exception) {
            Log.w(TAG, "update manifest fetch failed", e)
            null
        }
    }

    fun isUpdateAvailable(context: Context, manifest: UpdateManifest): Boolean =
        manifest.versionCode > currentVersionCode(context)

    private fun parseManifest(json: String): UpdateManifest? {
        val o = JSONObject(json)
        val versionCode = o.optLong("versionCode", -1)
        val versionName = o.optString("versionName", "").trim()
        val apkUrl = o.optString("apkUrl", "").trim()
        if (versionCode < 0 || versionName.isEmpty() || !isAllowedApkUrl(apkUrl)) {
            Log.w(TAG, "update manifest rejected: invalid fields")
            return null
        }
        val sha = o.optString("sha256", "").trim().lowercase()
        val notes = o.optString("releaseNotes", "").trim()
        return UpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            sha256 = sha.takeIf { it.isNotEmpty() },
            releaseNotes = notes.takeIf { it.isNotEmpty() },
        )
    }

    private fun isAllowedApkUrl(url: String): Boolean {
        if (!url.startsWith("https://")) {
            return false
        }
        val host = URL(url).host.lowercase()
        return host == "github.com" ||
            host.endsWith(".github.com") ||
            host == "githubusercontent.com" ||
            host.endsWith(".githubusercontent.com")
    }

    @Throws(Exception::class)
    private fun httpGet(urlStr: String): String {
        if (!urlStr.startsWith("https://")) {
            throw IllegalArgumentException("refusing non-HTTPS manifest URL")
        }
        var c: HttpURLConnection? = null
        try {
            c = URL(urlStr).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = true
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", ImageLoader.UA)
            val code = c.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("manifest HTTP $code")
            }
            val input = BufferedInputStream(c.inputStream)
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            var total = 0
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                total += n
                if (total > MAX_MANIFEST_BYTES) {
                    throw IllegalStateException("manifest exceeds $MAX_MANIFEST_BYTES bytes")
                }
                bos.write(buf, 0, n)
            }
            input.close()
            return bos.toString(Charsets.UTF_8.name())
        } finally {
            c?.disconnect()
        }
    }
}
