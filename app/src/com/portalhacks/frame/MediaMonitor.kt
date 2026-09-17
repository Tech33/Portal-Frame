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
        val newState = State(
            isPlaying = isPlaying,
            title = title.trim(),
            artist = artist.trim(),
            album = album.trim(),
            art = art ?: if (isPlaying && title == currentState.title) currentState.art else null,
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

    fun playPause() {
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
        }
    }

    fun next() {
        if (currentState.source.equals("AirPlay", ignoreCase = true)) {
            airPlayActionCallback?.invoke("next")
            return
        }
        activeController?.transportControls?.skipToNext()
    }

    fun prev() {
        if (currentState.source.equals("AirPlay", ignoreCase = true)) {
            airPlayActionCallback?.invoke("prev")
            return
        }
        activeController?.transportControls?.skipToPrevious()
    }
}
