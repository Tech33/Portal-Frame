package com.portalhacks.frame

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors room occupancy using Meta Portal's native hardware-accelerated computer vision
 * service (`PresenceManager` / Aloha).
 *
 * In stock Meta firmware, the SmartCamera DSP continuously performs on-device face/person
 * presence tracking and emits heartbeats into logcat. PresenceDetector tails these logs
 * with zero extra CPU or camera power overhead.
 *
 * When no human presence is detected for [timeoutMinutes] (default 10m), it triggers
 * onPresenceChanged(false), allowing Portal-Frame to smoothly dim and switch to Clock-Only.
 * As soon as someone enters the room, it immediately fires onPresenceChanged(true) to resume photos.
 */
class PresenceDetector(
    private val context: Context,
    private val onPresenceChanged: (isPresent: Boolean) -> Unit
) {
    companion object {
        private const val TAG = "PresenceDetector"
        private const val CHECK_INTERVAL_MS = 5000L
    }

    private val running = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var logcatProcess: Process? = null

    @Volatile
    private var lastPresenceMs: Long = System.currentTimeMillis()

    @Volatile
    private var isPresent: Boolean = true

    private val prefs by lazy { context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE) }

    private val checkerRunnable = object : Runnable {
        override fun run() {
            if (!running.get()) return
            checkTimeout()
            mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        val enabled = prefs.getBoolean(ConfigReceiver.KEY_PRESENCE_ENABLED, ConfigReceiver.DEFAULT_PRESENCE_ENABLED)
        if (!enabled) {
            Log.i(TAG, "Presence detection disabled in settings")
            running.set(false)
            return
        }

        lastPresenceMs = System.currentTimeMillis()
        isPresent = true

        startLogcatTailing()
        mainHandler.postDelayed(checkerRunnable, CHECK_INTERVAL_MS)
        Log.i(TAG, "PresenceDetector started (timeout: ${getTimeoutMinutes()}m)")
    }

    fun stop() {
        running.set(false)
        mainHandler.removeCallbacks(checkerRunnable)
        try {
            logcatProcess?.destroy()
        } catch (ignored: Exception) {}
        logcatProcess = null
        Log.i(TAG, "PresenceDetector stopped")
    }

    fun isCurrentlyPresent(): Boolean = isPresent

    fun lastSeenSecondsAgo(): Long = (System.currentTimeMillis() - lastPresenceMs) / 1000L

    /** External trigger (e.g. from MQTT, camera fallback, or ADB) */
    fun setPresenceState(present: Boolean) {
        if (present) {
            lastPresenceMs = System.currentTimeMillis()
            if (!isPresent) {
                isPresent = true
                dispatchState(true)
            }
        } else {
            if (isPresent) {
                isPresent = false
                dispatchState(false)
            }
        }
    }

    private fun getTimeoutMinutes(): Int {
        return prefs.getInt(
            ConfigReceiver.KEY_PRESENCE_TIMEOUT_MIN,
            ConfigReceiver.DEFAULT_PRESENCE_TIMEOUT_MIN
        ).coerceIn(1, 120)
    }

    private fun checkTimeout() {
        val timeoutMs = getTimeoutMinutes() * 60 * 1000L
        val elapsed = System.currentTimeMillis() - lastPresenceMs

        if (isPresent && elapsed >= timeoutMs) {
            Log.i(TAG, "No presence detected for ${elapsed / 1000}s -> Switching to absent")
            isPresent = false
            dispatchState(false)
        }
    }

    private fun dispatchState(present: Boolean) {
        mainHandler.post {
            onPresenceChanged(present)
        }
    }

    private fun startLogcatTailing() {
        val hasPermission = context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            Log.w(TAG, "READ_LOGS permission not granted. Grant via: adb shell pm grant ${context.packageName} android.permission.READ_LOGS")
        }

        Thread({
            try {
                // Clear logcat buffer or tail starting from now
                val process = ProcessBuilder(
                    "logcat",
                    "-v", "brief",
                    "-s",
                    "PresenceManager:*",
                    "AlohaPresence:*",
                    "SmartCamera:*",
                    "AlohaSmartCamera:*"
                ).redirectErrorStream(true).start()

                logcatProcess = process
                val reader = BufferedReader(InputStreamReader(process.inputStream))

                while (running.get()) {
                    val line = reader.readLine() ?: break
                    val lower = line.lowercase(java.util.Locale.US)

                    // Check for heartbeat / presence indicators from Aloha PresenceManager
                    if (lower.contains("presence") || lower.contains("heartbeat") || lower.contains("face")) {
                        // If line explicitly specifies false / absent / empty:
                        val isExplicitAbsent = lower.contains("present=false") ||
                                lower.contains("state=0") ||
                                lower.contains("empty") ||
                                lower.contains("vacant")

                        if (!isExplicitAbsent) {
                            lastPresenceMs = System.currentTimeMillis()
                            if (!isPresent) {
                                Log.i(TAG, "Presence detected via system logcat: $line")
                                isPresent = true
                                dispatchState(true)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Logcat tailing ended or failed: ${e.message}")
                }
            }
        }, "portal-presence-detector").start()
    }
}
