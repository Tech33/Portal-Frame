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
    private var sessionManager: android.media.session.MediaSessionManager? = null
    private var sessionsListener: android.media.session.MediaSessionManager.OnActiveSessionsChangedListener? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateFromController(currentController)
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateFromController(currentController)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected to system")
        MediaMonitor.isListenerConnected = true
        setupSessionManagerListener()
        scanActiveSessionsAndNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected")
        MediaMonitor.isListenerConnected = false
        sessionsListener?.let { sessionManager?.removeOnActiveSessionsChangedListener(it) }
    }

    private fun setupSessionManagerListener() {
        try {
            val sm = getSystemService(android.content.Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager ?: return
            sessionManager = sm
            val comp = android.content.ComponentName(this, PortalMediaNotificationListener::class.java)
            val listener = android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                if (!controllers.isNullOrEmpty()) {
                    val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                        ?: controllers.first()
                    bindController(active, active.packageName)
                }
            }
            sessionsListener = listener
            sm.addOnActiveSessionsChangedListener(listener, comp)
        } catch (e: Exception) {
            Log.w(TAG, "Failed setting up MediaSessionManager listener", e)
        }
    }

    private fun scanActiveSessionsAndNotifications() {
        try {
            val comp = android.content.ComponentName(this, PortalMediaNotificationListener::class.java)
            val controllers = sessionManager?.getActiveSessions(comp)
            if (!controllers.isNullOrEmpty()) {
                val active = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: controllers.first()
                bindController(active, active.packageName)
                return
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning getActiveSessions", e)
        }

        try {
            val activeNotifs = activeNotifications ?: return
            for (sbn in activeNotifs) {
                val notif = sbn.notification ?: continue
                val token = notif.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                if (token != null) {
                    val ctrl = MediaController(this, token)
                    bindController(ctrl, sbn.packageName, notif)
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning activeNotifications", e)
        }
    }

    private fun bindController(ctrl: MediaController, pkgName: String, notif: Notification? = null) {
        if (currentController?.sessionToken != ctrl.sessionToken) {
            currentController?.unregisterCallback(controllerCallback)
            currentController = ctrl
            ctrl.registerCallback(controllerCallback)
        }
        updateFromController(ctrl, pkgName, notif)
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
                updateFromController(currentController, sbn.packageName, notif)
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating media controller from session token", e)
            }
        } else {
            // Fallback for notifications with media category or from known audio/video apps
            val category = notif.category
            val pkg = sbn.packageName.lowercase()
            val isMedia = category == Notification.CATEGORY_TRANSPORT ||
                category == Notification.CATEGORY_SERVICE ||
                pkg.contains("youtube") ||
                pkg.contains("spotify") ||
                pkg.contains("browser") ||
                pkg.contains("chrome")

            if (isMedia) {
                val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
                    ?: ""
                val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                    ?: ""
                if (title.isNotEmpty()) {
                    var art: Bitmap? = extras.getParcelable(Notification.EXTRA_LARGE_ICON)
                    if (art == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        art = notif.getLargeIcon()?.loadDrawable(this)?.let { d ->
                            if (d is android.graphics.drawable.BitmapDrawable) d.bitmap else null
                        }
                    }
                    val source = when {
                        pkg.contains("spotify") -> "Spotify"
                        pkg.contains("youtube") -> "YouTube"
                        else -> "Media"
                    }
                    MediaMonitor.update(
                        isPlaying = true,
                        title = title,
                        artist = artist,
                        album = "",
                        art = art,
                        source = source
                    )
                }
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
        sessionsListener?.let { sessionManager?.removeOnActiveSessionsChangedListener(it) }
        MediaMonitor.isListenerConnected = false
    }

    private fun updateFromController(ctrl: MediaController?, pkgName: String = "", notif: Notification? = null) {
        ctrl ?: return
        val pbState = ctrl.playbackState
        val metadata = ctrl.metadata

        val isPlaying = pbState?.state == PlaybackState.STATE_PLAYING || (pbState == null && notif != null)
        var title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: ctrl.queueTitle?.toString() ?: ""
        var artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        var art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        // Notification extras fallback for web / YouTube streaming
        if (notif != null) {
            val extras = notif.extras
            if (title.isEmpty() && extras != null) {
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            }
            if (artist.isEmpty() && extras != null) {
                artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            }
            if (art == null && extras != null) {
                art = extras.getParcelable(Notification.EXTRA_LARGE_ICON)
                if (art == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    art = notif.getLargeIcon()?.loadDrawable(this)?.let { d ->
                        if (d is android.graphics.drawable.BitmapDrawable) d.bitmap else null
                    }
                }
            }
        }

        val sourceName = when {
            pkgName.contains("spotify") -> "Spotify"
            pkgName.contains("youtube") || pkgName.contains("smarttube") -> "YouTube"
            else -> "Media"
        }

        if (title.isNotEmpty() || isPlaying) {
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
