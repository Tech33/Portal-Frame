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
                    info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                            AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    info.packageNames = null // Capture all package installers and system dialog overlays
                    info.flags = info.flags or
                            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
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
        if (System.currentTimeMillis() - armedTimestamp > 90_000L) {
            disarmAutoInstall()
            return
        }
        val root = rootInActiveWindow ?: return
        if (clickInstallButton(root)) {
            Log.i(TAG, "auto-install target action performed")
        }
    }

    private fun clickInstallButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isInstallTarget = text == "install" || text == "update" ||
                desc == "install" || desc == "update" ||
                viewId.endsWith(":id/ok_button") ||
                viewId.endsWith(":id/button1")

        val isDoneOrOpen = text == "open" || text == "done" ||
                desc == "open" || desc == "done" ||
                viewId.endsWith(":id/done_button") ||
                viewId.endsWith(":id/launch_button")

        if (isInstallTarget || isDoneOrOpen) {
            var target: AccessibilityNodeInfo? = node
            while (target != null) {
                if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "auto-install clicked (isInstall=$isInstallTarget, isDoneOrOpen=$isDoneOrOpen, id=$viewId, text=$text)")
                    if (isDoneOrOpen) {
                        disarmAutoInstall()
                    }
                    return true
                }
                target = target.parent
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
