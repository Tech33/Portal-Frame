package com.portalhacks.frame

import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Unified media monitor and transport hub for Portal-Frame.
 *
 * Consolidates media updates from:
 * 1. Background Android media apps (Spotify Connect, SmartTube, YouTube Music) via NotificationListenerService.
 * 2. Native AirPlay 1 audio receiver streams from Apple devices.
 *
 * Provides media state to SlideshowController's Now Playing pill and AlbumServer's fleet dashboard.
 */
object MediaMonitor {

    private const val TAG = "MediaMonitor"

    data class State(
        val isPlaying: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val art: Bitmap? = null,
        val source: String = "", // "Spotify", "Sonos", "AirPlay", "YouTube", etc.
        val packageName: String = "",
        val deviceName: String = "",
        val volume: Int = -1, // 0 - 100, -1 if unknown
        val timestamp: Long = System.currentTimeMillis()
    )

    interface Listener {
        fun onMediaStateChanged(state: State)
    }

    @Volatile
    var currentState: State = State()
        private set

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var isListenerConnected: Boolean = false

    @Volatile
    var activeController: MediaController? = null

    @Volatile
    var airPlayActionCallback: ((action: String) -> Unit)? = null

    @Volatile
    var sonosActionCallback: ((action: String, arg: Any?) -> Unit)? = null

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener.onMediaStateChanged(currentState)
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun update(
        isPlaying: Boolean,
        title: String,
        artist: String,
        album: String = "",
        art: Bitmap? = null,
        source: String = "",
        packageName: String = "",
        deviceName: String = "",
        volume: Int = -1
    ) {
        val isSonos = source.contains("Sonos", ignoreCase = true)
        val cleanTitle = title.trim()
        val isDummySonos = isSonos && (
            cleanTitle.isEmpty() ||
            cleanTitle.equals("Playing on Sonos", ignoreCase = true) ||
            cleanTitle.equals("Sonos", ignoreCase = true) ||
            cleanTitle.equals("TV", ignoreCase = true) ||
            cleanTitle.equals("Audio In", ignoreCase = true) ||
            cleanTitle.equals("Line-in", ignoreCase = true) ||
            cleanTitle.equals("NOT_IMPLEMENTED", ignoreCase = true)
        )

        val safeTitle = if (isDummySonos) {
            ""
        } else {
            cleanTitle.ifEmpty {
                if (isPlaying) (if (source.isNotEmpty()) source else "Playing Media") else ""
            }
        }
        val effectiveIsPlaying = if (isSonos && (safeTitle.isEmpty() || isDummySonos)) false else isPlaying
        val safeArtist = artist.trim().ifEmpty {
            if (safeTitle.isNotEmpty()) "Audio Playback" else ""
        }
        val effectiveDevice = deviceName.ifEmpty {
            if (isSonos) "Sonos Speaker" else "Portal Living Room"
        }
        val newState = State(
            isPlaying = effectiveIsPlaying,
            title = safeTitle,
            artist = safeArtist,
            album = album.trim(),
            art = art ?: if (isPlaying && safeTitle == currentState.title) currentState.art else null,
            source = source,
            packageName = packageName,
            deviceName = effectiveDevice,
            volume = if (volume >= 0) volume else currentState.volume,
            timestamp = System.currentTimeMillis()
        )
        currentState = newState
        mainHandler.post {
            for (l in listeners) {
                try {
                    l.onMediaStateChanged(newState)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in media listener", e)
                }
            }
        }
    }

    fun clear() {
        currentState = State()
        mainHandler.post {
            for (l in listeners) {
                l.onMediaStateChanged(currentState)
            }
        }
    }

    fun playPause(context: Context? = null) {
        if (currentState.source.contains("Sonos", ignoreCase = true)) {
            sonosActionCallback?.invoke("play_pause", null)
            return
        }
        if (currentState.source.equals("AirPlay", ignoreCase = true)) {
            airPlayActionCallback?.invoke("play_pause")
            return
        }
        val ctrl = activeController
        if (ctrl != null) {
            val pbState = ctrl.playbackState?.state
            if (pbState == PlaybackState.STATE_PLAYING) {
                ctrl.transportControls.pause()
            } else {
                ctrl.transportControls.play()
            }
        } else if (context != null) {
            sendMediaKeyEvent(context, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        }
    }

    fun next(context: Context? = null) {
        if (currentState.source.contains("Sonos", ignoreCase = true)) {
            sonosActionCallback?.invoke("next", null)
            return
        }
        if (currentState.source.equals("AirPlay", ignoreCase = true)) {
            airPlayActionCallback?.invoke("next")
            return
        }
        if (activeController != null) {
            activeController?.transportControls?.skipToNext()
        } else if (context != null) {
            sendMediaKeyEvent(context, android.view.KeyEvent.KEYCODE_MEDIA_NEXT)
        }
    }

    fun prev(context: Context? = null) {
        if (currentState.source.contains("Sonos", ignoreCase = true)) {
            sonosActionCallback?.invoke("prev", null)
            return
        }
        if (currentState.source.equals("AirPlay", ignoreCase = true)) {
            airPlayActionCallback?.invoke("prev")
            return
        }
        if (activeController != null) {
            activeController?.transportControls?.skipToPrevious()
        } else if (context != null) {
            sendMediaKeyEvent(context, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
    }

    fun setVolume(volPercent: Int, context: Context? = null) {
        val safeVol = volPercent.coerceIn(0, 100)
        if (currentState.source.contains("Sonos", ignoreCase = true)) {
            sonosActionCallback?.invoke("volume", safeVol)
            currentState = currentState.copy(volume = safeVol)
            notifyListeners()
            return
        }
        if (context != null) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (am != null) {
                    val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                    val target = (safeVol / 100f * max).toInt().coerceIn(0, max)
                    am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting audio stream volume", e)
            }
        }
        currentState = currentState.copy(volume = safeVol)
        notifyListeners()
    }

    fun getStreamVolumePercent(context: Context): Int {
        if (currentState.source.contains("Sonos", ignoreCase = true) && currentState.volume >= 0) {
            return currentState.volume
        }
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return 50
            val cur = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            if (max > 0) ((cur.toFloat() / max) * 100).toInt() else 50
        } catch (_: Exception) {
            50
        }
    }

    fun launchApp(context: Context) {
        try {
            val ctrl = activeController
            val sessionAct = ctrl?.sessionActivity
            if (sessionAct != null) {
                sessionAct.send()
                return
            }
            val installedSpotify = CompanionAppInstaller.getInstalledSpotifyPackage(context)
            val targetPkg = currentState.packageName.ifEmpty {
                installedSpotify ?: if (currentState.source.contains("Spotify", ignoreCase = true)) "com.spotify.music" else ""
            }
            if (targetPkg.isNotEmpty()) {
                val intent = context.packageManager.getLaunchIntentForPackage(targetPkg)
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            }
            // Fallback for Spotify standalone on Portal
            val fallbackSpotify = (if (installedSpotify != null) context.packageManager.getLaunchIntentForPackage(installedSpotify) else null)
                ?: context.packageManager.getLaunchIntentForPackage("com.facebook.aloha.spotifystandalone")
                ?: context.packageManager.getLaunchIntentForPackage("com.spotify.music")
                ?: context.packageManager.getLaunchIntentForPackage("com.spotify.tv.android")
                ?: context.packageManager.getLaunchIntentForPackage("com.spotify.lite")
            if (fallbackSpotify != null) {
                fallbackSpotify.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallbackSpotify)
            } else {
                android.widget.Toast.makeText(context, "Spotify is not installed on this Portal", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed launching media app", e)
        }
    }

    private fun notifyListeners() {
        mainHandler.post {
            for (l in listeners) {
                try {
                    l.onMediaStateChanged(currentState)
                } catch (_: Exception) {}
            }
        }
    }

    private fun sendMediaKeyEvent(context: Context, keyCode: Int) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
            am?.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        } catch (e: Exception) {
            Log.w(TAG, "Failed dispatching media key event $keyCode", e)
        }
    }
}
