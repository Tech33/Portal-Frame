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
        private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        private val pollRunnable = object : Runnable {
            var attempts = 0
            override fun run() {
                if (!autoInstallArmed || attempts++ > 60) {
                    disarmAutoInstall()
                    return
                }
                instance?.let { s ->
                    val root = s.rootInActiveWindow
                    if (root != null) {
                        if (s.clickInstallButton(root)) {
                            Log.i(TAG, "polling auto-install performed action")
                        }
                    }
                }
                mainHandler.postDelayed(this, 400L)
            }
        }

        fun armAutoInstall() {
            autoInstallArmed = true
            armedTimestamp = System.currentTimeMillis()
            mainHandler.removeCallbacks(pollRunnable)
            pollRunnable.attempts = 0
            mainHandler.post(pollRunnable)
            Log.i(TAG, "armed auto-install with window polling and event interception")
        }

        fun disarmAutoInstall() {
            autoInstallArmed = false
            mainHandler.removeCallbacks(pollRunnable)
            Log.i(TAG, "disarmed auto-install")
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "PortalAccessibilityService connected and ready")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        mainHandler.removeCallbacks(pollRunnable)
        Log.i(TAG, "PortalAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!autoInstallArmed) return
        if (System.currentTimeMillis() - armedTimestamp > 90_000L) {
            disarmAutoInstall()
            return
        }
        val root = rootInActiveWindow ?: event?.source ?: return
        clickInstallButton(root)
    }

    fun clickInstallButton(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. Check for Unknown Sources Toggle ("Allow from this source")
        val isAllowSourceSwitch = (viewId.contains("switch") || text.contains("allow from this source") || desc.contains("allow from this source")) &&
                node.isCheckable && !node.isChecked
        if (isAllowSourceSwitch) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "auto-install enabled unknown sources switch")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return true
            }
        }

        // 2. Check for Install / Update button
        val isInstallTarget = text == "install" || text == "update" ||
                text.contains("install") || text.contains("update") ||
                desc == "install" || desc == "update" ||
                desc.contains("install") || desc.contains("update") ||
                viewId.endsWith(":id/ok_button") ||
                viewId.endsWith(":id/button1") ||
                viewId.endsWith(":id/install_confirm_button") ||
                viewId.endsWith(":id/package_installer_install_button")

        // 3. Check for Done / Open button
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
