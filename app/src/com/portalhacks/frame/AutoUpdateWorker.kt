package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors

object AutoUpdateWorker {
    private const val TAG = "AutoUpdateWorker"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val telemetryExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "update-telemetry").apply { priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var isUpdating = false
    @Volatile private var lastTelemetryMs = 0L

    /**
     * Posts encrypted real-time status and telemetry back to the cloud relay key-value store.
     * message.html polls these keys to display live fleet update progress across all Portals.
     */
    fun postUpdateStatus(context: Context, state: String, message: String, progress: Int) {
        telemetryExecutor.execute {
            try {
                val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
                val channel = prefs.getString(
                    ConfigReceiver.KEY_ANNOUNCEMENT_CHANNEL,
                    ConfigReceiver.DEFAULT_ANNOUNCEMENT_CHANNEL
                )?.trim() ?: ConfigReceiver.DEFAULT_ANNOUNCEMENT_CHANNEL
                if (channel.isEmpty()) return@execute

                val shortId = ConfigReceiver.getDeviceShortId(context)
                val name = ConfigReceiver.getDeviceDisplayName(context)
                val nickname = prefs.getString(ConfigReceiver.KEY_CUSTOM_NICKNAME, "")?.trim() ?: ""
                val city = prefs.getString(ConfigReceiver.KEY_DEVICE_CITY, "")?.trim() ?: ""
                val curVer = UpdateChecker.currentVersionName(context)
                val curCode = UpdateChecker.currentVersionCode(context)

                // Read battery and charging state
                var batteryPct = -1
                var isCharging = false
                try {
                    val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                    val bIntent = context.registerReceiver(null, ifilter)
                    val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                    if (level >= 0 && scale > 0) batteryPct = (level * 100 / scale)
                    val bStatus = bIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                    isCharging = bStatus == BatteryManager.BATTERY_STATUS_CHARGING || bStatus == BatteryManager.BATTERY_STATUS_FULL
                } catch (_: Exception) {}

                val payload = JSONObject()
                    .put("id", shortId)
                    .put("shortId", shortId)
                    .put("name", name)
                    .put("nickname", nickname)
                    .put("city", city)
                    .put("version", curVer)
                    .put("build", curCode)
                    .put("state", state)
                    .put("progress", progress.coerceIn(0, 100))
                    .put("message", message)
                    .put("battery", batteryPct)
                    .put("charging", isCharging)
                    .put("timestamp", System.currentTimeMillis())

                val aesKey = CryptoUtils.deriveAesKey(channel)
                val encHex = CryptoUtils.encrypt(payload.toString(), aesKey)
                if (encHex.isEmpty()) return@execute

                val targetKeys = mutableListOf<String>()
                targetKeys.add("${channel}_status_${shortId.lowercase(Locale.US)}")
                if (nickname.isNotEmpty()) {
                    val cleanNick = nickname.lowercase(Locale.US).replace(" ", "-")
                    targetKeys.add("${channel}_status_$cleanNick")
                }
                if (city.isNotEmpty()) {
                    targetKeys.add("${channel}_status_${city.lowercase(Locale.US)}")
                }

                for (k in targetKeys.distinct()) {
                    try {
                        val encodedKey = URLEncoder.encode(k, "UTF-8")
                        val url = "https://keyvalue.immanuel.co/api/KeyVal/UpdateValue/cs79vqdm/$encodedKey/$encHex"
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 4000
                        conn.readTimeout = 4000
                        conn.setRequestProperty("Content-Length", "0")
                        conn.responseCode
                        conn.disconnect()
                    } catch (e: Exception) {
                        Log.d(TAG, "Failed posting status to $k: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "postUpdateStatus error", e)
            }
        }
    }

    /**
     * Send periodic idle heartbeat so message.html knows Portal is online and its installed version.
     */
    fun postHeartbeat(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTelemetryMs < 45_000L) return
        lastTelemetryMs = now
        val ver = UpdateChecker.currentVersionName(context)
        postUpdateStatus(context, "idle", "Online • v$ver", 100)
    }

    /**
     * Triggers an Apple/Google-style non-destructive, atomic, deep-sleep resilient update.
     */
    fun triggerNow(context: Context, onStatus: ((String) -> Unit)? = null) {
        if (isUpdating) {
            onStatus?.invoke("Update already in progress")
            return
        }
        isUpdating = true

        val appContext = context.applicationContext

        // Acquire WakeLock and WifiLock to prevent deep sleep CPU throttling or Wi-Fi drops
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PortalFrame:AutoUpdateWakeLock")?.apply {
            acquire(15 * 60 * 1000L) // 15 min safety timeout
        }
        val wm = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PortalFrame:AutoUpdateWifiLock")?.apply {
            acquire()
        }

        ImageLoader(appContext).executor().execute {
            try {
                postUpdateStatus(appContext, "waking", "Waking Portal and checking update manifest…", 5)
                onStatus?.invoke("Checking for updates…")

                val manifest = UpdateChecker.fetchManifest()
                if (manifest == null) {
                    val msg = "Could not reach update server"
                    onStatus?.invoke(msg)
                    postUpdateStatus(appContext, "error", msg, 0)
                    isUpdating = false
                    return@execute
                }

                if (!UpdateChecker.isUpdateAvailable(appContext, manifest)) {
                    val msg = "Already on latest version (v${UpdateChecker.currentVersionName(appContext)})"
                    onStatus?.invoke(msg)
                    postUpdateStatus(appContext, "up_to_date", msg, 100)
                    isUpdating = false
                    return@execute
                }

                // Pre-flight disk space check
                if (!UpdateInstaller.ensureDiskHeadroom(appContext)) {
                    val msg = "Insufficient storage space on Portal"
                    onStatus?.invoke(msg)
                    postUpdateStatus(appContext, "error", msg, 0)
                    isUpdating = false
                    return@execute
                }

                val startMsg = "Downloading version ${manifest.versionName}…"
                onStatus?.invoke(startMsg)
                postUpdateStatus(appContext, "downloading", startMsg, 10)

                var lastReportedPct = 10
                val result = UpdateInstaller.download(appContext, manifest) { pct, _, _ ->
                    val overallPct = 10 + (pct * 0.65).toInt()
                    val downloadMsg = "Downloading update ($pct%)…"
                    onStatus?.invoke(downloadMsg)
                    if (overallPct - lastReportedPct >= 15 || pct == 100) {
                        lastReportedPct = overallPct
                        postUpdateStatus(appContext, "downloading", "Downloading v${manifest.versionName} ($pct%)", overallPct)
                    }
                }

                when (result) {
                    is UpdateInstaller.Result.Ready -> {
                        // Pre-flight signature check
                        postUpdateStatus(appContext, "verifying", "Verifying cryptographic signature…", 80)
                        val (sigMatch, sigError) = UpdateInstaller.checkSignatureCompatibility(appContext, result.file)
                        if (!sigMatch) {
                            val err = sigError ?: "Signature mismatch: incompatible release key"
                            Log.e(TAG, err)
                            result.file.delete()
                            onStatus?.invoke(err)
                            postUpdateStatus(appContext, "error", err, 0)
                            return@execute
                        }

                        val installMsg = "Staging zero-touch installation of v${manifest.versionName}…"
                        onStatus?.invoke(installMsg)
                        postUpdateStatus(appContext, "installing", installMsg, 90)
                        installSilentlyOrPrompt(appContext, result.file)
                    }
                    is UpdateInstaller.Result.Error -> {
                        val failMsg = "Download failed: ${result.message}"
                        onStatus?.invoke(failMsg)
                        postUpdateStatus(appContext, "error", failMsg, 0)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "update failed", e)
                val err = "Update failed: ${e.message}"
                onStatus?.invoke(err)
                postUpdateStatus(appContext, "error", err, 0)
            } finally {
                isUpdating = false
                try {
                    if (wakeLock?.isHeld == true) wakeLock.release()
                } catch (_: Exception) {}
                try {
                    if (wifiLock?.isHeld == true) wifiLock.release()
                } catch (_: Exception) {}
            }
        }
    }

    private fun installSilentlyOrPrompt(context: Context, apk: File) {
        // 1. Try root silent install if available
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "pm install -r ${apk.absolutePath}"))
            val exit = p.waitFor()
            if (exit == 0) {
                Log.i(TAG, "silent root pm install succeeded")
                return
            }
        } catch (_: Exception) {}

        // 2. Try direct shell pm install (works if UID has package manager permissions)
        try {
            val p = Runtime.getRuntime().exec(arrayOf("pm", "install", "-r", "-d", apk.absolutePath))
            val exit = p.waitFor()
            if (exit == 0) {
                Log.i(TAG, "silent shell pm install succeeded")
                return
            }
        } catch (_: Exception) {}

        // 3. Ensure accessibility service is enabled before launching package installer
        if (!ScreenControl.isAccessibilityEnabled(context)) {
            Log.i(TAG, "Enabling PortalAccessibilityService for unattended update")
            ScreenControl.enableAccessibility(context)
        }

        // 4. Arm accessibility service for zero-touch auto-click
        PortalAccessibilityService.armAutoInstall()

        // 5. Launch package installer
        mainHandler.post {
            UpdateInstaller.promptInstall(context, apk)
        }
    }
}
