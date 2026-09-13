package com.portalhacks.frame

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File

object AutoUpdateWorker {
    private const val TAG = "AutoUpdateWorker"
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isUpdating = false

    fun triggerNow(context: Context, onStatus: ((String) -> Unit)? = null) {
        if (isUpdating) {
            onStatus?.invoke("Update already in progress")
            return
        }
        isUpdating = true
        ImageLoader(context).executor().execute {
            try {
                onStatus?.invoke("Checking for updates…")
                val manifest = UpdateChecker.fetchManifest()
                if (manifest == null) {
                    onStatus?.invoke("Could not check for updates")
                    isUpdating = false
                    return@execute
                }
                if (!UpdateChecker.isUpdateAvailable(context, manifest)) {
                    onStatus?.invoke("You are on the latest version")
                    isUpdating = false
                    return@execute
                }
                onStatus?.invoke("Downloading version ${manifest.versionName}…")
                val result = UpdateInstaller.download(context, manifest) { pct, _, _ ->
                    onStatus?.invoke("Downloading update ($pct%)…")
                }
                when (result) {
                    is UpdateInstaller.Result.Ready -> {
                        onStatus?.invoke("Installing update…")
                        installSilentlyOrPrompt(context, result.file)
                    }
                    is UpdateInstaller.Result.Error -> {
                        onStatus?.invoke("Download failed: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "update failed", e)
                onStatus?.invoke("Update failed")
            } finally {
                isUpdating = false
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
