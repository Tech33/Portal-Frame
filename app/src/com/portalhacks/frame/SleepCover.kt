package com.portalhacks.frame

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/**
 * Hides what the screen wakes onto.
 *
 * When the Meta Portal screen sleeps and later wakes, the underlying launcher or daydream
 * may briefly draw behind the dark screen, causing a visible flash of the launcher before
 * our activity brings its UI forward.
 *
 * This overlay covers the display with an opaque black window at sleep, and smoothly
 * fades out once the slideshow activity has redrawn.
 */
class SleepCover(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val wm get() = context.getSystemService(WindowManager::class.java)

    @Volatile
    private var view: View? = null

    val isShowing: Boolean get() = view != null

    fun show() {
        if (!Settings.canDrawOverlays(context)) return
        main.post {
            if (view != null) return@post
            runCatching {
                val v = View(context).apply {
                    setBackgroundColor(Color.BLACK)
                }
                val lp = WindowManager.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE
                )
                view = v
                wm?.addView(v, lp)
                Log.i(TAG, "SleepCover: shown")
                // Safety net: auto-hide after 15s in case hide is never triggered
                main.postDelayed({ hide() }, MAX_COVER_MS)
            }.onFailure {
                view = null
            }
        }
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            view = null
            Log.i(TAG, "SleepCover: fading out")
            v.animate()
                .alpha(0f)
                .setDuration(FADE_MS)
                .withEndAction {
                    runCatching { wm?.removeView(v) }
                }
                .start()
        }
    }

    private companion object {
        private const val TAG = "SleepCover"
        private const val FADE_MS = 220L
        private const val MAX_COVER_MS = 15_000L
    }
}
