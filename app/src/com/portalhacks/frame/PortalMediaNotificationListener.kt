package com.portalhacks.frame

import android.app.Notification
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Listens for system media playback notifications from apps like Spotify, YouTube Music,
 * SmartTube, etc., extracting track metadata, album art, and transport controls.
 */
class PortalMediaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "PortalMediaListener"
    }

    private var currentController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateFromController(currentController)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateFromController(currentController)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val notif = sbn.notification ?: return
        val extras = notif.extras ?: return

        val sessionToken = extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        if (sessionToken != null) {
            try {
                if (currentController?.sessionToken != sessionToken) {
                    currentController?.unregisterCallback(controllerCallback)
                    val ctrl = MediaController(this, sessionToken)
                    currentController = ctrl
                    ctrl.registerCallback(controllerCallback)
                }
                updateFromController(currentController, sbn.packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating media controller from session token", e)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val token = sbn?.notification?.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        if (token != null && currentController?.sessionToken == token) {
            currentController?.unregisterCallback(controllerCallback)
            currentController = null
            MediaMonitor.activeController = null
            if (!MediaMonitor.currentState.source.equals("AirPlay", ignoreCase = true)) {
                MediaMonitor.clear()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
    }

    private fun updateFromController(ctrl: MediaController?, pkgName: String = "") {
        ctrl ?: return
        val pbState = ctrl.playbackState
        val metadata = ctrl.metadata

        val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: ctrl.queueTitle?.toString() ?: ""
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val sourceName = when {
            pkgName.contains("spotify") -> "Spotify"
            pkgName.contains("youtube") || pkgName.contains("smarttube") -> "YouTube Music"
            else -> "Media"
        }

        if (title.isNotEmpty()) {
            MediaMonitor.activeController = ctrl
            MediaMonitor.update(
                isPlaying = isPlaying,
                title = title,
                artist = artist,
                album = album,
                art = art,
                source = sourceName
            )
        }
    }
}
