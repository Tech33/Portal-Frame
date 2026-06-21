package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        ScreensaverGuardService.startIfEnabled(ctx)
    }
}
