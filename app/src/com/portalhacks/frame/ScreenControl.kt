package com.portalhacks.frame

import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log

/**
 * Coordinates physical screen power state, hardware sleep/wake actions, and auto-enabling
 * of [PortalAccessibilityService] using granted WRITE_SECURE_SETTINGS.
 */
object ScreenControl {
    private const val TAG = "ScreenControl"

    @Volatile
    var isAsleep: Boolean = false

    /**
     * Wakes the display hardware using a PowerManager WakeLock.
     */
    fun wake(context: Context) {
        isAsleep = false
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            val wl = pm?.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "com.portalhacks.frame:wake"
            )
            wl?.acquire(1000L)
            Log.i(TAG, "wake: WakeLock acquired, screen turned ON")
        } catch (e: Exception) {
            Log.w(TAG, "wake: failed to acquire WakeLock", e)
        }
    }

    /**
     * Puts the physical screen to sleep by triggering GLOBAL_ACTION_LOCK_SCREEN
     * via [PortalAccessibilityService].
     */
    fun sleep(context: Context): Boolean {
        isAsleep = true
        val svc = PortalAccessibilityService.instance
        return if (svc != null) {
            val res = svc.lockScreen()
            Log.i(TAG, "sleep: lockScreen executed -> $res")
            res
        } else {
            Log.w(TAG, "sleep: PortalAccessibilityService not active; attempting self-enable")
            enableAccessibility(context)
            false
        }
    }

    /**
     * Checks whether the device display is currently interactive (screen powered on).
     */
    fun isInteractive(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isInteractive == true
    }

    /**
     * Checks if PortalAccessibilityService is listed in secure settings.
     */
    fun isAccessibilityEnabled(context: Context): Boolean {
        val flat = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val target = "${context.packageName}/${PortalAccessibilityService::class.java.name}"
        val targetShort = "${context.packageName}/.PortalAccessibilityService"
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        for (component in splitter) {
            if (component.equals(target, ignoreCase = true) || component.equals(targetShort, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    /**
     * Self-enables [PortalAccessibilityService] using WRITE_SECURE_SETTINGS.
     * Portal-Frame is already granted this permission via ADB during setup.
     */
    fun enableAccessibility(context: Context): Boolean {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "enableAccessibility: WRITE_SECURE_SETTINGS not granted")
            return false
        }
        return try {
            val target = "${context.packageName}/${PortalAccessibilityService::class.java.name}"
            val current = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (!current.contains(target) && !current.contains("${context.packageName}/.PortalAccessibilityService")) {
                val updated = if (current.isEmpty()) target else "$current:$target"
                Settings.Secure.putString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated
                )
                Log.i(TAG, "enableAccessibility: added service to ENABLED_ACCESSIBILITY_SERVICES")
            }
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.i(TAG, "enableAccessibility: ACCESSIBILITY_ENABLED set to 1")
            true
        } catch (e: Exception) {
            Log.w(TAG, "enableAccessibility: failed to write secure settings", e)
            false
        }
    }
}
