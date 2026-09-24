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

    private fun isIgnoredPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return true
        val lower = pkg.lowercase(java.util.Locale.US)
        if (lower.contains("spotify")) return false
        if (lower.contains("youtube") || lower.contains("smarttube")) return false
        if (lower.contains("music") || lower.contains("waxrain") || lower.contains("airscreen")) return false
        return lower.startsWith("android") ||
            lower.startsWith("com.android") ||
            lower.startsWith("com.facebook.aloha") ||
            lower.startsWith("com.facebook.katana") ||
            lower.startsWith("com.facebook.orca") ||
            lower.startsWith("com.facebook.wearable") ||
            lower.startsWith("com.oculus") ||
            lower.startsWith("com.google.android.gms") ||
            lower.contains("telecom") ||
            lower.contains("dialer") ||
            lower.contains("contact")
    }

    private fun isMediaPackage(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return false
        val lower = pkg.lowercase(java.util.Locale.US)
        return lower.contains("spotify") ||
            lower.contains("youtube") ||
            lower.contains("smarttube") ||
            lower.contains("music") ||
            lower.contains("waxrain") ||
            lower.contains("airscreen") ||
            lower.contains("pandora") ||
            lower.contains("deezer") ||
            lower.contains("tidal") ||
            lower.contains("soundcloud") ||
            lower.contains("audio") ||
            lower.contains("radio")
    }

    private fun isIgnoredNotification(pkg: String, notif: Notification): Boolean {
        if (isIgnoredPackage(pkg)) return true
        val category = notif.category
        if (category == Notification.CATEGORY_SERVICE ||
            category == Notification.CATEGORY_SYSTEM ||
            category == Notification.CATEGORY_STATUS ||
            category == Notification.CATEGORY_ALARM ||
            category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_EVENT ||
            category == Notification.CATEGORY_EMAIL ||
            category == Notification.CATEGORY_MESSAGE
        ) {
            val hasSession = notif.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION) != null
            if (!hasSession || !isMediaPackage(pkg)) {
                return true
            }
        }
        val title = notif.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.lowercase(java.util.Locale.US) ?: ""
        if (title.contains("contact") || title.contains("syncing") || (title.contains("service") && !isMediaPackage(pkg))) {
            return true
        }
        return false
    }

    private fun setupSessionManagerListener() {
        try {
            val sm = getSystemService(android.content.Context.MEDIA_SESSION_SERVICE) as? android.media.session.MediaSessionManager ?: return
            sessionManager = sm
            val comp = android.content.ComponentName(this, PortalMediaNotificationListener::class.java)
            val listener = android.media.session.MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
                if (!controllers.isNullOrEmpty()) {
                    val mediaControllers = controllers.filter { !isIgnoredPackage(it.packageName) }
                    val active = mediaControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                        ?: mediaControllers.firstOrNull()
                    if (active != null) {
                        bindController(active, active.packageName)
                    }
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
                val mediaControllers = controllers.filter { !isIgnoredPackage(it.packageName) }
                val active = mediaControllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                    ?: mediaControllers.firstOrNull { isMediaPackage(it.packageName) }
                if (active != null) {
                    bindController(active, active.packageName)
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error scanning getActiveSessions", e)
        }

        try {
            val activeNotifs = activeNotifications ?: return
            for (sbn in activeNotifs) {
                val pkg = sbn.packageName ?: continue
                if (isIgnoredPackage(pkg)) continue
                val notif = sbn.notification ?: continue
                if (isIgnoredNotification(pkg, notif)) continue

                val token = notif.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
                if (token != null) {
                    val ctrl = MediaController(this, token)
                    bindController(ctrl, pkg, notif)
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
        val pkg = sbn.packageName ?: return
        if (isIgnoredPackage(pkg)) return

        val notif = sbn.notification ?: return
        if (isIgnoredNotification(pkg, notif)) return

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
                updateFromController(currentController, pkg, notif)
            } catch (e: Exception) {
                Log.w(TAG, "Failed creating media controller from session token", e)
            }
        } else if (isMediaPackage(pkg) && notif.category == Notification.CATEGORY_TRANSPORT) {
            // Fallback only for verified media apps using CATEGORY_TRANSPORT
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
                ?: ""
            val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
                ?: ""
            if (title.isNotEmpty()) {
                var art: Bitmap? = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
                if (art == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    art = notif.getLargeIcon()?.loadDrawable(this)?.let { d ->
                        if (d is android.graphics.drawable.BitmapDrawable) d.bitmap else null
                    }
                }
                val source = when {
                    pkg.contains("spotify") -> "Spotify"
                    pkg.contains("youtube") -> "YouTube"
                    pkg.contains("waxrain") || pkg.contains("airscreen") -> "AirPlay"
                    else -> "Media"
                }
                val actions = notif.actions
                val hasPauseAction = actions?.any {
                    it.title?.toString()?.contains("pause", ignoreCase = true) == true
                } ?: false
                val hasPlayAction = actions?.any {
                    it.title?.toString()?.contains("play", ignoreCase = true) == true
                } ?: false
                val fallbackPlaying = when {
                    hasPauseAction -> true
                    hasPlayAction -> false
                    else -> currentController?.playbackState?.state == PlaybackState.STATE_PLAYING
                }

                MediaMonitor.update(
                    isPlaying = fallbackPlaying,
                    title = title,
                    artist = artist,
                    album = "",
                    art = art,
                    source = source
                )
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val token = sbn.notification?.extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        val matchesCtrl = (token != null && currentController?.sessionToken == token) ||
            (currentController?.packageName == sbn.packageName)
        if (matchesCtrl) {
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
        val effectivePkg = (ctrl.packageName?.ifEmpty { pkgName } ?: pkgName).lowercase(java.util.Locale.US)
        if (isIgnoredPackage(effectivePkg)) return

        val pbState = ctrl.playbackState
        val metadata = ctrl.metadata

        val stateVal = pbState?.state
        val isPlaying = (stateVal == PlaybackState.STATE_PLAYING || stateVal == PlaybackState.STATE_BUFFERING) &&
            (pbState?.playbackSpeed ?: 1f) > 0f

        var title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: ctrl.queueTitle?.toString() ?: ""
        var artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        var art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)

        var pendingArtUrl: String? = null
        if (art == null && metadata != null) {
            val artUriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ART_URI)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            if (!artUriStr.isNullOrBlank()) {
                if (artUriStr.startsWith("http://", ignoreCase = true) || artUriStr.startsWith("https://", ignoreCase = true)) {
                    pendingArtUrl = artUriStr
                } else {
                    try {
                        val uri = android.net.Uri.parse(artUriStr)
                        contentResolver.openInputStream(uri)?.use { stream ->
                            art = android.graphics.BitmapFactory.decodeStream(stream)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // Notification extras fallback for web / YouTube streaming / Spotify large icon
        if (notif != null) {
            val extras = notif.extras
            if (title.isEmpty() && extras != null) {
                title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            }
            if (artist.isEmpty() && extras != null) {
                artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            }
            if (art == null && extras != null) {
                art = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
            }
            if (art == null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                art = notif.getLargeIcon()?.loadDrawable(this)?.let { d ->
                    if (d is android.graphics.drawable.BitmapDrawable) d.bitmap else null
                }
            }
        }

        val lowerTitle = title.lowercase(java.util.Locale.US)
        if (lowerTitle.contains("contact") || lowerTitle.contains("syncing") || (lowerTitle.contains("service") && !isMediaPackage(effectivePkg))) {
            return
        }

        val sourceName = when {
            effectivePkg.contains("spotify") -> "Spotify"
            effectivePkg.contains("youtube") || effectivePkg.contains("smarttube") -> "YouTube"
            effectivePkg.contains("waxrain") || effectivePkg.contains("airscreen") || effectivePkg.contains("airplay") -> "AirPlay"
            effectivePkg.contains("pandora") -> "Pandora"
            effectivePkg.contains("tidal") -> "Tidal"
            effectivePkg.contains("deezer") -> "Deezer"
            effectivePkg.contains("apple") -> "Apple Music"
            effectivePkg.contains("amazon") -> "Amazon Music"
            else -> "Media"
        }

        if (title.isNotEmpty() || isPlaying) {
            MediaMonitor.activeController = ctrl
            val vol = MediaMonitor.getStreamVolumePercent(this)
            MediaMonitor.update(
                isPlaying = isPlaying,
                title = title,
                artist = artist,
                album = album,
                art = art,
                source = sourceName,
                packageName = effectivePkg,
                deviceName = "Portal Living Room",
                volume = vol
            )

            if (art == null && pendingArtUrl != null) {
                fetchWebArtAsync(pendingArtUrl, title, artist, effectivePkg, sourceName, isPlaying)
            }
        }
    }

    private fun fetchWebArtAsync(
        urlStr: String,
        title: String,
        artist: String,
        pkgName: String,
        source: String,
        isPlaying: Boolean
    ) {
        kotlin.concurrent.thread(name = "MediaArtFetch") {
            try {
                val url = java.net.URL(urlStr)
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "PortalFrame/1.0")
                conn.connectTimeout = 3500
                conn.readTimeout = 4500
                if (conn.responseCode in 200..299) {
                    val bmp = android.graphics.BitmapFactory.decodeStream(conn.inputStream)
                    if (bmp != null && MediaMonitor.currentState.title == title) {
                        MediaMonitor.update(
                            isPlaying = isPlaying,
                            title = title,
                            artist = artist,
                            album = MediaMonitor.currentState.album,
                            art = bmp,
                            source = source,
                            packageName = pkgName,
                            deviceName = MediaMonitor.currentState.deviceName,
                            volume = MediaMonitor.currentState.volume
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
