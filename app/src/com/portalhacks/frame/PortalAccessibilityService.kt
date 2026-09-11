package com.portalhacks.frame

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PortalAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: PortalAccessibilityService? = null
        private const val TAG = "PortalAccessibility"
        @Volatile var autoInstallArmed = false
        private var armedTimestamp = 0L

        fun armAutoInstall() {
            autoInstallArmed = true
            armedTimestamp = System.currentTimeMillis()
            instance?.let { s ->
                try {
                    val info = s.serviceInfo ?: AccessibilityServiceInfo()
                    info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    info.packageNames = arrayOf("com.android.packageinstaller", "com.google.android.packageinstaller")
                    s.serviceInfo = info
                    Log.i(TAG, "armed auto-install accessibility interception")
                } catch (e: Exception) {
                    Log.w(TAG, "failed to update serviceInfo for auto-install", e)
                }
            }
        }

        fun disarmAutoInstall() {
            autoInstallArmed = false
            instance?.let { s ->
                try {
                    val info = s.serviceInfo ?: return@let
                    info.eventTypes = 0
                    info.packageNames = null
                    s.serviceInfo = info
                } catch (_: Exception) {}
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = 0
            serviceInfo = info
        } catch (_: Exception) {}
        Log.i(TAG, "PortalAccessibilityService connected and ready")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "PortalAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!autoInstallArmed) return
        if (System.currentTimeMillis() - armedTimestamp > 60_000L) {
            disarmAutoInstall()
            return
        }
        val pkg = event?.packageName?.toString() ?: return
        if (pkg.contains("packageinstaller")) {
            val root = rootInActiveWindow ?: return
            if (clickInstallButton(root)) {
                Log.i(TAG, "auto-install button clicked successfully")
                disarmAutoInstall()
            }
        }
    }

    private fun clickInstallButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.lowercase() ?: ""
        if (text == "install" || text == "update") {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (clickInstallButton(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    override fun onInterrupt() = Unit

    fun lockScreen(): Boolean {
        Log.i(TAG, "lockScreen: triggering GLOBAL_ACTION_LOCK_SCREEN")
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    }
}
