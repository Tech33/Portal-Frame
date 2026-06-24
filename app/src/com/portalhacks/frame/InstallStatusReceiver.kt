package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager

/**
 * Receives PackageInstaller session commit callbacks from [UpdateInstaller.installViaShizuku].
 * Re-broadcasts the result locally so [SettingsActivity] can update its UI.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""

        val uiMessage = when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Silent install succeeded")
                "Update installed successfully! Restart Frame to use it."
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.w(TAG, "Silent install aborted: $message")
                "Install cancelled."
            }
            else -> {
                Log.e(TAG, "Silent install failed (status=$status): $message")
                "Install failed ($message). Try running provision.command again."
            }
        }

        // Forward to SettingsActivity via local broadcast
        LocalBroadcastManager.getInstance(context).sendBroadcast(
            Intent(UpdateInstaller.ACTION_INSTALL_STATUS).apply {
                putExtra(UpdateInstaller.EXTRA_STATUS, status)
                putExtra(UpdateInstaller.EXTRA_MESSAGE, uiMessage)
            }
        )
    }

    companion object {
        private const val TAG = "PortalFrame"
    }
}
