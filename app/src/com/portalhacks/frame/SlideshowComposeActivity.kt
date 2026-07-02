package com.portalhacks.frame

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Calendar

/**
 * The live slideshow screensaver, hosted in Jetpack Compose.
 *
 * The slideshow's rendering is a deeply imperative, custom-animated View stack —
 * crossfading [android.widget.ImageView]s, a [android.animation.ValueAnimator]
 * Ken Burns engine, a `Canvas` shimmer, an `ImageSpan` weather glyph, distance-based
 * touch gestures — exactly the case Compose's `AndroidView` interop exists for. So we
 * keep the battle-tested [SlideshowController] and bridge it into `setContent`, rather
 * than re-deriving the animation engine in Compose (which would risk regressing the
 * marquee features for no user-facing gain). The album fetch/cache/refresh and
 * night-dimming logic live here in Kotlin.
 *
 * This is the screensaver target ([FrameDreamService] launches it).
 */
class SlideshowComposeActivity : ComponentActivity() {

    private lateinit var loader: ImageLoader
    private lateinit var controller: SlideshowController
    private var flipWebView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentAlbums: List<String> = emptyList()
    private var currentIds: List<String> = ArrayList()
    private var lowLightClockOnly = false
    private var scheduledClockOnly = false
    private var useFlipClock = false

    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val lightSensor: Sensor? by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) }

    // Low-light "clock only" mode (mirrors the Portal night-mode option). When enabled and the
    // room is dark, drop to a clock-only screen; restore the photos when the light returns.
    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val lux = e.values.firstOrNull() ?: return
            when {
                lux <= LOW_LUX -> {
                    lowLightClockOnly = true
                    applyClockOnlyMode()
                }
                lux >= HIGH_LUX -> {
                    lowLightClockOnly = false
                    applyClockOnlyMode()
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
    }

    private val scheduleTick = object : Runnable {
        override fun run() {
            updateScheduledClockOnly()
            applyClockOnlyMode()
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
        )
        // Honor the Portal's own brightness: don't override the window brightness, so the
        // system's adaptive/manual brightness (and its light sensor) governs the frame.
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }

        loader = ImageLoader(this)
        val root = FrameLayout(this)

        // 1. Build the slideshow's View hierarchy.
        val slideshowContainer = FrameLayout(this)
        controller = SlideshowController(this, slideshowContainer, loader).apply {
            setOnDismiss {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                finishAndRemoveTask()
            }
            setOnSettings {
                startActivity(Intent(this@SlideshowComposeActivity, SettingsActivity::class.java))
            }
        }
        root.addView(slideshowContainer)

        // 2. Build the WebView for the Immortal Flip clock style (hidden by default)
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                displayZoomControls = false
                builtInZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    return false
                }
            }
            loadUrl("file:///android_asset/immortal_clock/index.html")
            visibility = View.GONE
        }
        flipWebView = webView
        root.addView(webView)

        // GestureDetector to dismiss/exit screensaver or open settings from the WebView flip clock
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
                finishAndRemoveTask()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                startActivity(Intent(this@SlideshowComposeActivity, SettingsActivity::class.java))
            }
        })

        webView.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            true
        }

        setContent {
            AndroidView(factory = { root }, modifier = Modifier.fillMaxSize())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The manifest declares configChanges for orientation, so the Activity (and the hosted
        // SlideshowController) survive a rotation. Tell the controller to recompute its screen
        // dimensions / pairing axis so side-by-side ↔ top/bottom follows the new orientation.
        if (::controller.isInitialized) {
            controller.onScreenConfigChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        if (flipWebView?.visibility == View.VISIBLE) {
            flipWebView?.onResume()
        }
        // Clear any photo retained from a previous run so re-entering the frame
        // doesn't flash the old image before the first new frame loads.
        controller.blank()
        // Re-apply the clock position/size (picks up a Settings "reset" done while away).
        controller.applyClockTransform()
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)

        // "Only show clock in low light": watch the ambient light sensor when enabled.
        sensorManager.unregisterListener(lightListener)
        lowLightClockOnly = false
        val low = lightSensor
        if (prefs.getBoolean(ConfigReceiver.KEY_CLOCK_LOW_LIGHT, ConfigReceiver.DEFAULT_CLOCK_LOW_LIGHT) &&
            low != null
        ) {
            sensorManager.registerListener(lightListener, low, SensorManager.SENSOR_DELAY_NORMAL)
        }
        updateScheduledClockOnly()
        handler.removeCallbacks(scheduleTick)
        if (prefs.getBoolean(ConfigReceiver.KEY_NIGHT_CLOCK, ConfigReceiver.DEFAULT_NIGHT_CLOCK)) {
            handler.postDelayed(scheduleTick, 60000 - System.currentTimeMillis() % 60000)
        }

        currentAlbums = Albums.enabled(prefs)

        if (currentAlbums.isEmpty()) {
            // No albums playing (none configured, or all stopped): show the bundled samples.
            controller.start()
            applyClockOnlyMode()
            return
        }

        // Albums configured: start straight from their merged caches if we have them
        // (disk-cached images make the first photo appear near-instantly); otherwise
        // show a black "Loading…" screen — never the samples.
        val cached = mergedSlides(prefs, currentAlbums)
        if (cached.isNotEmpty()) {
            currentIds = idsOf(cached)
            controller.setItems(cached)
        } else {
            currentIds = ArrayList()
            controller.setStatusHint("Loading photos…")
        }

        // Refresh now, then keep checking periodically while we're on screen.
        fetchAllAndApply(cached.isEmpty())
        handler.removeCallbacks(refreshTick)
        handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)

        // Apply clock only mode at the end to override slideshow if needed
        applyClockOnlyMode()
    }

    override fun onPause() {
        super.onPause()
        flipWebView?.onPause()
        sensorManager.unregisterListener(lightListener)
        handler.removeCallbacks(refreshTick)
        handler.removeCallbacks(scheduleTick)
        controller.stop()
    }

    private fun applyClockOnlyMode() {
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        val clockOnlyActive = lowLightClockOnly || scheduledClockOnly
        val useFlipForNight = prefs.getBoolean(ConfigReceiver.KEY_CLOCK_FLIP, ConfigReceiver.DEFAULT_CLOCK_FLIP)

        if (clockOnlyActive) {
            if (useFlipForNight) {
                // Show WebView Flip Clock
                controller.setClockOnly(false)
                controller.blank()
                controller.stop()
                flipWebView?.visibility = View.VISIBLE
                flipWebView?.onResume()
            } else {
                // Show Classic Native Clock
                flipWebView?.visibility = View.GONE
                flipWebView?.onPause()
                if (!controller.running) {
                    controller.start()
                }
                controller.setClockOnly(true)
            }
        } else {
            // Normal Slideshow Mode
            flipWebView?.visibility = View.GONE
            flipWebView?.onPause()
            controller.setClockOnly(false)
            if (!controller.running) {
                controller.start()
            }
        }
    }

    private fun updateScheduledClockOnly() {
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(ConfigReceiver.KEY_NIGHT_CLOCK, ConfigReceiver.DEFAULT_NIGHT_CLOCK)) {
            scheduledClockOnly = false
            return
        }
        val start = prefs.getInt(
            ConfigReceiver.KEY_NIGHT_CLOCK_START_MIN,
            ConfigReceiver.DEFAULT_NIGHT_CLOCK_START_MIN,
        )
        val end = prefs.getInt(
            ConfigReceiver.KEY_NIGHT_CLOCK_END_MIN,
            ConfigReceiver.DEFAULT_NIGHT_CLOCK_END_MIN,
        )
        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        scheduledClockOnly = isMinuteInRange(minute, start, end)
    }

    private val refreshTick = object : Runnable {
        override fun run() {
            fetchAllAndApply(false)
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    /**
     * Fetch every configured album in the background; cache each and re-apply the merged
     * photo set as each one lands (only when the set actually changed, to avoid flicker).
     */
    private fun fetchAllAndApply(showHint: Boolean) {
        val albums = currentAlbums
        if (albums.isEmpty()) {
            return
        }
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        for (url in albums) {
            loader.executor().execute {
                try {
                    val album = PhotoSources.fetch(url)
                    if (album.slides.isNotEmpty()) {
                        AlbumCache.write(prefs, url, album.slides, album.title)
                    }
                    runOnUiThread { rebuildFromCaches(showHint) }
                } catch (e: Exception) {
                    Log.e(TAG, "album fetch failed: $url", e)
                    if (showHint) {
                        runOnUiThread { rebuildFromCaches(true) }
                    }
                }
            }
        }
    }

    /** Recompute the slideshow from all albums' caches and apply it if it changed. */
    private fun rebuildFromCaches(showHint: Boolean) {
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        if (currentAlbums != Albums.enabled(prefs)) {
            return // the playing album set changed while fetching
        }
        val merged = mergedSlides(prefs, currentAlbums)
        if (merged.isEmpty()) {
            if (showHint) controller.setStatusHint("Couldn't load photos — retrying later")
            return
        }
        val ids = idsOf(merged)
        if (ids != currentIds) {
            currentIds = ids
            controller.setItems(merged)
        }
    }

    private fun mergedSlides(
        prefs: android.content.SharedPreferences,
        albums: List<String>,
    ): List<Slide> {
        val buckets = ArrayList<List<Slide>>(albums.size)
        for (url in albums) {
            buckets.add(AlbumCache.read(prefs, url) ?: emptyList())
        }
        return when (
            prefs.getString(
                ConfigReceiver.KEY_ALBUM_PLAYBACK,
                ConfigReceiver.DEFAULT_ALBUM_PLAYBACK,
            ) ?: ConfigReceiver.DEFAULT_ALBUM_PLAYBACK
        ) {
            "album_priority" -> buckets.flatten()
            else -> interleaveSlides(buckets)
        }
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val REFRESH_INTERVAL_MS = 20 * 60 * 1000L // 20 min

        // Lux thresholds for clock-only mode, with hysteresis to avoid flicker near the edge.
        private const val LOW_LUX = 8f
        private const val HIGH_LUX = 25f

        private fun isMinuteInRange(minute: Int, start: Int, end: Int): Boolean {
            if (start == end) return true
            return if (start < end) minute in start until end else minute >= start || minute < end
        }

        private fun idsOf(slides: List<Slide>): List<String> {
            val ids = ArrayList<String>(slides.size)
            for (s in slides) {
                ids.add(s.id)
            }
            return ids
        }

        private fun interleaveSlides(groups: List<List<Slide>>): List<Slide> {
            val merged = ArrayList<Slide>(groups.sumOf { it.size })
            var added: Boolean
            var idx = 0
            do {
                added = false
                for (group in groups) {
                    if (idx < group.size) {
                        merged.add(group[idx])
                        added = true
                    }
                }
                idx++
            } while (added)
            return merged
        }
    }
}
