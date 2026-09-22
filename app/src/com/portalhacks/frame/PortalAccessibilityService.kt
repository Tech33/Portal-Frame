package com.portalhacks.frame

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PortalAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: PortalAccessibilityService? = null
        private const val TAG = "PortalAccessibility"
        @Volatile var autoInstallArmed = false
        @Volatile var hasClickedInstall = false
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
                        val pkg = root.packageName?.toString() ?: ""
                        if (isTargetInstallerPackage(pkg)) {
                            if (s.clickInstallButton(root)) {
                                Log.i(TAG, "polling auto-install performed action on $pkg")
                            }
                        }
                    }
                }
                mainHandler.postDelayed(this, 500L)
            }
        }

        @Volatile var notificationAccessArmed = false
        private var notificationArmedTimestamp = 0L

        fun isFramePackage(pkg: CharSequence?): Boolean {
            val name = pkg?.toString()?.lowercase() ?: return false
            return name == "com.portalhacks.frame" || name.contains("portalhacks")
        }

        fun isTargetInstallerPackage(pkg: CharSequence?): Boolean {
            val name = pkg?.toString()?.lowercase() ?: return false
            if (isFramePackage(name)) return false
            return name.contains("packageinstaller") ||
                   name.contains("installer") ||
                   name == "com.android.settings" ||
                   name == "com.google.android.settings"
        }

        fun isTargetSettingsPackage(pkg: CharSequence?): Boolean {
            val name = pkg?.toString()?.lowercase() ?: return false
            if (isFramePackage(name)) return false
            return name.contains("settings")
        }

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
            hasClickedInstall = false
            armedTimestamp = System.currentTimeMillis()
            mainHandler.removeCallbacks(pollRunnable)
            pollRunnable.attempts = 0
            mainHandler.postDelayed(pollRunnable, 500L)
            Log.i(TAG, "armed auto-install with window polling and event interception")
        }

        fun disarmAutoInstall() {
            autoInstallArmed = false
            hasClickedInstall = false
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
        val pkg = (root.packageName ?: event?.packageName)?.toString() ?: ""

        // NEVER interact with Frame's own UI through accessibility automation!
        if (isFramePackage(pkg)) return

        if (notificationAccessArmed) {
            if (System.currentTimeMillis() - notificationArmedTimestamp > 30_000L) {
                disarmNotificationAccess()
            } else if (isTargetSettingsPackage(pkg) && handleNotificationAccessWindow(root)) {
                return
            }
        }

        if (!autoInstallArmed) return
        if (System.currentTimeMillis() - armedTimestamp > 90_000L) {
            disarmAutoInstall()
            return
        }
        if (isTargetInstallerPackage(pkg)) {
            clickInstallButton(root)
        }
    }

    fun handleNotificationAccessWindow(node: AccessibilityNodeInfo): Boolean {
        if (isFramePackage(node.packageName)) return false

        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. Check for Confirmation Dialog "Allow" / "OK" button
        val isAllowDialogBtn = (text == "allow" || text == "ok" || text == "turn on" ||
                desc == "allow" || desc == "ok" || desc == "turn on" ||
                viewId.endsWith(":id/button1") || viewId.endsWith(":id/ok_button"))
        if (isAllowDialogBtn && performClick(node)) {
            Log.i(TAG, "auto-enabled notification access: confirmed dialog [Allow]")
            disarmNotificationAccess()
            mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 300L)
            return true
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

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 3) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            cur = cur.parent
            depth++
        }
        return false
    }

    fun clickInstallButton(node: AccessibilityNodeInfo): Boolean {
        if (isFramePackage(node.packageName)) return false

        val text = node.text?.toString()?.trim()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        // 1. Check for Unknown Sources Toggle ("Allow from this source")
        val isAllowSourceSwitch = (viewId.contains("switch") || text.contains("allow from this source") || desc.contains("allow from this source")) &&
                node.isCheckable && !node.isChecked
        if (isAllowSourceSwitch) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "auto-install enabled unknown sources switch")
                mainHandler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 300L)
                return true
            }
        }

        // 2. Check for Done / Open button (installation complete)
        val isDoneOrOpen = (
            text == "open" || text == "done" ||
            desc == "open" || desc == "done" ||
            viewId.endsWith(":id/done_button") ||
            viewId.endsWith(":id/launch_button")
        )
        if (isDoneOrOpen && performClick(node)) {
            Log.i(TAG, "auto-install clicked completion button: text=$text, id=$viewId")
            disarmAutoInstall()
            return true
        }

        // 3. Check for Install / Update button
        val isInstallTarget = !hasClickedInstall && (
            text == "install" || text == "update" || text == "install update" ||
            desc == "install" || desc == "update" || desc == "install update" ||
            viewId.endsWith(":id/ok_button") ||
            viewId.endsWith(":id/button1") ||
            viewId.endsWith(":id/install_confirm_button") ||
            viewId.endsWith(":id/package_installer_install_button")
        )
        if (isInstallTarget && performClick(node)) {
            Log.i(TAG, "auto-install clicked install/update button: text=$text, id=$viewId")
            hasClickedInstall = true
            return true
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
