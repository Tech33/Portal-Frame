package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        AlbumServer.startServer(ctx)
        MqttManager.startIfEnabled(ctx)
        ScreensaverGuardService.startIfEnabled(ctx)

        if (intent?.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                val launchIntent = Intent(ctx, SlideshowComposeActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                ctx.startActivity(launchIntent)
            } catch (_: Exception) {}
        }
    }
}
