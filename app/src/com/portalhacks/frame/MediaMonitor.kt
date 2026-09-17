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
        val source: String = "", // "Spotify", "AirPlay", "YouTube Music", etc.
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
        source: String = ""
    ) {
        val safeTitle = title.trim().ifEmpty {
            if (isPlaying) (if (source.isNotEmpty()) source else "Playing Media") else ""
        }
        val safeArtist = artist.trim().ifEmpty {
            if (safeTitle.isNotEmpty()) "Audio Playback" else ""
        }
        val newState = State(
            isPlaying = isPlaying,
            title = safeTitle,
            artist = safeArtist,
            album = album.trim(),
            art = art ?: if (isPlaying && safeTitle == currentState.title) currentState.art else null,
            source = source,
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
