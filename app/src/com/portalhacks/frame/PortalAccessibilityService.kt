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

        @Volatile var notificationAccessArmed = false
        private var notificationArmedTimestamp = 0L

        fun armNotificationAccessEnabler(context: Context) {
            if (ScreenControl.isNotificationListenerEnabled(context)) return
            notificationAccessArmed = true
            notificationArmedTimestamp = System.currentTimeMillis()
            mainHandler.post {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "Launched ACTION_NOTIFICATION_LISTENER_SETTINGS for auto-enabling")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed launching notification listener settings", e)
                }
            }
        }

        fun disarmNotificationAccess() {
            notificationAccessArmed = false
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
        // Check if notification access needs auto-granting
        if (!ScreenControl.isNotificationListenerEnabled(this)) {
            armNotificationAccessEnabler(this)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        mainHandler.removeCallbacks(pollRunnable)
        Log.i(TAG, "PortalAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: event?.source ?: return

        if (notificationAccessArmed) {
            if (System.currentTimeMillis() - notificationArmedTimestamp > 30_000L) {
                disarmNotificationAccess()
            } else if (handleNotificationAccessWindow(root)) {
                return
            }
        }

        if (!autoInstallArmed) return
        if (System.currentTimeMillis() - armedTimestamp > 90_000L) {
            disarmAutoInstall()
            return
        }
        clickInstallButton(root)
    }

    fun handleNotificationAccessWindow(node: AccessibilityNodeInfo): Boolean {
        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. Check for Confirmation Dialog "Allow" / "OK" button
        val isAllowDialogBtn = (text == "allow" || text == "ok" || text == "turn on" ||
                desc == "allow" || desc == "ok" || desc == "turn on" ||
                viewId.endsWith(":id/button1") || viewId.endsWith(":id/ok_button"))
        if (isAllowDialogBtn && node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "auto-enabled notification access: confirmed dialog [Allow]")
                disarmNotificationAccess()
                mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 300L)
                return true
            }
        }

        // 2. Check for "Frame" / "Frame Media Controller" row switch
        val isFrameEntry = text.contains("frame") || desc.contains("frame") ||
                text.contains("portalhacks") || desc.contains("portalhacks")
        if (isFrameEntry) {
            val switchNode = findSwitchInRowOrParent(node)
            if (switchNode != null && switchNode.isCheckable && !switchNode.isChecked) {
                if (switchNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    Log.i(TAG, "auto-enabled notification access: clicked switch for Frame")
                    return true
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (handleNotificationAccessWindow(child)) {
                child.recycle()
                return true
            }
            child.recycle()
        }
        return false
    }

    private fun findSwitchInRowOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Look in siblings/children of the parent
        val parent = node.parent ?: return null
        val found = findCheckable(parent)
        if (found != null) return found
        val grandParent = parent.parent ?: return null
        return findCheckable(grandParent)
    }

    private fun findCheckable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val res = findCheckable(child)
            if (res != null) return res
            child.recycle()
        }
        return null
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
