package com.portalhacks.frame

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal accessibility service — no event listening, used strictly for
 * performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) to blank and power off the
 * hardware display without requiring device administrator or system platform signature.
 */
class PortalAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: PortalAccessibilityService? = null
        private const val TAG = "PortalAccessibility"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Override XML configuration so no UI events are intercepted, keeping footprint near-zero.
        serviceInfo = serviceInfo.apply { eventTypes = 0 }
        Log.i(TAG, "PortalAccessibilityService connected and ready")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "PortalAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    fun lockScreen(): Boolean {
        Log.i(TAG, "lockScreen: triggering GLOBAL_ACTION_LOCK_SCREEN")
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }
}
