package com.portalhacks.frame

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import kotlin.system.exitProcess

/**
 * Custom Application class for Portal-Frame.
 * Implements a 24/7 crash recovery watchdog ensuring that any unhandled exception
 * logs diagnostics and automatically revives the slideshow within 1.5s, preventing
 * the kiosk from getting stuck on the Meta Portal home screen during long runs (200+ days).
 */
class PortalFrameApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        AlbumCache.setContext(this)
        ScreensaverGuardService.startIfEnabled(this)
        setupCrashWatchdog()
    }

    private fun setupCrashWatchdog() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("PortalFrameWatchdog", "Uncaught exception on ${thread.name} — auto-recovering", throwable)
                try {
                    val logFile = File(cacheDir, "last_crash.txt")
                    logFile.writeText("Time: ${System.currentTimeMillis()}\nThread: ${thread.name}\n${Log.getStackTraceString(throwable)}")
                } catch (_: Exception) {}

                val restartIntent = Intent(this, SlideshowComposeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this,
                    999,
                    restartIntent,
                    android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
                val alarmMgr = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager
                alarmMgr?.set(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1500L,
                    pendingIntent
                )
            } catch (e: Exception) {
                Log.e("PortalFrameWatchdog", "Failed to schedule relaunch", e)
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }
}
