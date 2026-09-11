package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * Registry and launch helper for standalone audio receivers sideloaded via OpenPortal / Immortal.
 *
 * Sideloaded media receivers run as background services or daemon players behind Frame's
 * slideshow screensaver, allowing users to stream music and audio seamlessly without losing focus.
 */
object MediaReceivers {

    data class ReceiverItem(
        val id: String,
        val name: String,
        val protocol: String,
        val shortDesc: String,
        val packageNames: List<String>,
        val setupSteps: List<String>,
        val downloadTip: String,
    )

    val ALL_RECEIVERS = listOf(
        ReceiverItem(
            id = "spotify",
            name = "Spotify",
            protocol = "Spotify Connect",
            shortDesc = "Stream audio directly from the Spotify app on iPhone, iPad, Mac, or Android.",
            packageNames = listOf(
                "com.spotify.music",
                "com.spotify.tv.android",
                "com.spotify.lite",
            ),
            setupSteps = listOf(
                "Sideload standard Spotify APK via OpenPortal or Immortal.",
                "Launch Spotify on Portal once and log into your account.",
                "In Spotify on your phone, tap the 'Connect to a device' icon and select Portal.",
                "Playback runs in the background while Frame continues displaying your photos."
            ),
            downloadTip = "Obtain Spotify APK via APKMirror or Aurora Store on OpenPortal."
        ),
        ReceiverItem(
            id = "smarttube",
            name = "SmartTube (YouTube Music)",
            protocol = "Link with TV Code",
            shortDesc = "Cast YouTube Music or YouTube audio from iOS/Android without losing focus.",
            packageNames = listOf(
                "com.teamsmart.videomanager.tv",
                "com.liskovsoft.smarttubetv.beta",
                "com.google.android.apps.youtube.music",
            ),
            setupSteps = listOf(
                "Sideload SmartTube APK via OpenPortal.",
                "Open SmartTube on Portal → Settings → Link with TV Code.",
                "In YouTube Music on iPhone, open Settings → Watch on TV → Link with TV Code and enter the code.",
                "Stream YouTube Music in the background alongside Immortal and HA Bridge."
            ),
            downloadTip = "Download official APK from github.com/yuliskov/SmartTube/releases via OpenPortal."
        ),
        ReceiverItem(
            id = "airplay",
            name = "AirPlay Speaker",
            protocol = "Apple AirPlay Audio",
            shortDesc = "Advertises your Portal as an AirPlay speaker directly in iOS Control Center.",
            packageNames = listOf(
                "com.waxrain.airplaydmr",
                "com.ionitech.airscreen",
                "com.waxrain.airplaydmr2",
                "com.waxrain.airplaydmr3",
            ),
            setupSteps = listOf(
                "Sideload AirReceiver or AirScreen APK via OpenPortal.",
                "Open the app on Portal and enable the AirPlay Audio background service.",
                "On your iPhone, swipe down Control Center, tap the AirPlay icon, and choose Portal Go.",
                "All audio (Spotify, Apple Music, Podcasts, YouTube) pipes directly to Portal."
            ),
            downloadTip = "AirReceiver or AirScreen APKs can be sideloaded via OpenPortal browser or ADB."
        )
    )

    fun getInstalledPackage(context: Context, item: ReceiverItem): String? {
        val pm = context.packageManager
        for (pkg in item.packageNames) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
            } catch (_: Exception) {
            }
        }
        return null
    }

    fun isInstalled(context: Context, item: ReceiverItem): Boolean {
        return getInstalledPackage(context, item) != null
    }

    fun launch(context: Context, item: ReceiverItem): Boolean {
        val pm = context.packageManager
        val pkg = getInstalledPackage(context, item) ?: return false
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
