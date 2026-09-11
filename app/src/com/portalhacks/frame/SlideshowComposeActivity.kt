package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.net.http.SslError
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.util.Calendar
import java.util.Locale

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
    private var slideshowContainer: FrameLayout? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentAlbums: List<String> = emptyList()
    private var currentIds: List<String> = ArrayList()
    private var lowLightClockOnly = false
    private var scheduledClockOnly = false
    private var useFlipClock = false
    private val prefs by lazy { getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE) }

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

    private var haWebView: WebView? = null
    private var haContainer: FrameLayout? = null
    private val haIdleHandler = Handler(Looper.getMainLooper())
    private val haIdleRunnable = Runnable { hideHomeAssistant() }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ConfigReceiver.ACTION_SHOW_DASHBOARD -> showHomeAssistant()
                ConfigReceiver.ACTION_SHOW_SLIDESHOW -> hideHomeAssistant()
                ConfigReceiver.ACTION_NEXT_PHOTO -> {
                    if (!isClockModeActive()) controller.next()
                }
                ConfigReceiver.ACTION_PREV_PHOTO -> {
                    if (!isClockModeActive()) controller.prev()
                }
                ConfigReceiver.ACTION_SET_MESSAGE -> {
                    val msg = intent?.getStringExtra("message")
                        ?: prefs.getString(ConfigReceiver.KEY_CUSTOM_MESSAGE, "") ?: ""
                    handleIncomingBroadcastMessage(msg)
                }
                ConfigReceiver.ACTION_CLEAR_MESSAGE -> handleClearBroadcastMessage()
                ConfigReceiver.ACTION_SLEEP -> sleepScreen(lockHardware = true)
                Intent.ACTION_SCREEN_OFF -> sleepScreen(lockHardware = false)
                ConfigReceiver.ACTION_WAKE,
                Intent.ACTION_SCREEN_ON -> wakeScreen()
                ConfigReceiver.ACTION_SET_SHOWCASE -> {
                    val mode = intent?.getStringExtra("mode")
                    val loc = intent?.getStringExtra("location")
                    applyShowcase(mode, loc)
                }
            }
        }
    }

    private fun isClockModeActive(): Boolean =
        (flipWebView?.visibility == View.VISIBLE) || controller.isClockOnly() || lowLightClockOnly || scheduledClockOnly

    private fun handleIncomingBroadcastMessage(msg: String) {
        val trimmed = msg.trim()
        if (trimmed.isEmpty()) return

        // 0. Remote Update Command: #update or #ota
        if (trimmed.equals("#update", ignoreCase = true) || trimmed.equals("#ota", ignoreCase = true) ||
            trimmed.contains("#update") || trimmed.contains("#ota")) {
            controller.showTemporaryBanner("⬇️ Checking and downloading update in background...")
            AutoUpdateWorker.triggerNow(this) { status ->
                controller.showTemporaryBanner(status)
            }
            return
        }

        // 1. Album Sync: #setalbum:<url>, #replacealbum:<url>, #addalbum:<url>, #album:<url>, or raw Google/iCloud Photos URL
        val albumUrlRegex = Regex("""https?://(?:photos\.app\.goo\.gl/[^\s]+|photos\.google\.com/[^\s]+|share\.icloud\.com/photos/[^\s]+)""")
        val setAlbumMatch = Regex("""#(?:setalbum|replacealbum):([^\s]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        val addAlbumMatch = Regex("""#(?:addalbum|album):([^\s]+)""", RegexOption.IGNORE_CASE).find(trimmed)
        val rawUrlMatch = albumUrlRegex.find(trimmed)

        if (setAlbumMatch != null || addAlbumMatch != null || (rawUrlMatch != null && !trimmed.contains("#showcase:"))) {
            val replace = setAlbumMatch != null || currentAlbums.isEmpty()
            val targetUrl = setAlbumMatch?.groupValues?.get(1)
                ?: addAlbumMatch?.groupValues?.get(1)
                ?: rawUrlMatch?.value ?: ""

            if (targetUrl.isNotEmpty()) {
                if (replace) {
                    Albums.clear(prefs)
                }
                Albums.add(prefs, targetUrl)
                currentAlbums = Albums.enabled(prefs)
                controller.showTemporaryBanner("🖼️ Syncing family album...")
                controller.setStatusHint("Loading new album photos…")
                fetchAllAndApply(showHint = true)
                return
            }
        }

        var displayMsg = trimmed
        var targetMode: String? = null
        var targetLoc: String? = null

        // 2. Check for explicit #showcase: tag (e.g. #showcase:portugal, #showcase:2024-09, #showcase:recent_trip, #showcase:all)
        val hashMatch = Regex("""#(?:showcase:|location:)?([A-Za-z0-9_.-]+)""").find(trimmed)
        if (hashMatch != null) {
            val fullTag = hashMatch.groupValues[0]
            val tag = hashMatch.groupValues[1].trim()
            displayMsg = trimmed.replace(fullTag, "").trim()
            val lowerTag = tag.lowercase(Locale.US).replace("-", "_")
            when {
                lowerTag == "all" -> {
                    targetMode = "all"
                    targetLoc = ""
                }
                lowerTag in listOf("recent", "recent_trip", "trip") -> {
                    targetMode = "recent_trip"
                    targetLoc = ""
                }
                lowerTag in listOf("last_7_days", "7_days", "7days") -> {
                    targetMode = "last_7_days"
                    targetLoc = ""
                }
                lowerTag in listOf("last_30_days", "30_days", "30days") -> {
                    targetMode = "last_30_days"
                    targetLoc = ""
                }
                DateRangeParser.parse(tag) != null -> {
                    targetMode = "date_range"
                    targetLoc = tag
                }
                else -> {
                    targetMode = "location"
                    targetLoc = tag.replace("_", " ")
                }
            }
        }

        // 3. If no explicit showcase tag was given, check for date ranges in the natural text
        if (targetMode == null) {
            val dateRange = DateRangeParser.parse(displayMsg)
            if (dateRange != null) {
                targetMode = "date_range"
                targetLoc = dateRange.label
            }
        }

        // 4. If still no target, extract location clue from the natural text
        if (targetMode == null) {
            val locationClue = LocationExtractor.extractLocation(displayMsg)
            if (locationClue != null) {
                targetMode = "location"
                targetLoc = locationClue.primary
            }
        }

        prefs.edit().putString(ConfigReceiver.KEY_CUSTOM_MESSAGE, displayMsg).apply()
        controller.checkCustomMessage()

        if (targetMode != null) {
            Log.i(TAG, "Applying showcase from broadcast: mode=$targetMode, location=$targetLoc")
            applyShowcase(mode = targetMode, location = targetLoc)
        }

        // Immediately trigger a hard background refresh of albums so newly uploaded trip photos land without waiting
        fetchAllAndApply(showHint = false)
    }

    private fun handleClearBroadcastMessage() {
        prefs.edit().remove(ConfigReceiver.KEY_CUSTOM_MESSAGE).apply()
        controller.checkCustomMessage()
        applyShowcase(mode = "all", location = "")
    }

    private var isScreenAsleep = false
    private var sleepCover: SleepCover? = null

    private fun sleepScreen(lockHardware: Boolean = false) {
        if (isScreenAsleep) return
        isScreenAsleep = true
        ScreenControl.isAsleep = true
        sleepCover?.show()
        window.clearFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        val lp = window.attributes
        lp.screenBrightness = 0.001f
        window.attributes = lp
        controller.blank()
        if (lockHardware) {
            ScreenControl.sleep(this)
        }
    }

    private fun wakeScreen() {
        isScreenAsleep = false
        ScreenControl.isAsleep = false
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(android.app.KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        if (!isClockModeActive()) {
            if (!controller.running) {
                controller.start()
            } else {
                controller.next()
            }
        } else {
            applyClockOnlyMode()
        }
        sleepCover?.hide()
    }

    private fun applyShowcase(mode: String?, location: String?) {
        val p = prefs
        val editor = p.edit()
        if (!mode.isNullOrEmpty()) editor.putString(ConfigReceiver.KEY_SHOWCASE_MODE, mode)
        if (location != null) editor.putString(ConfigReceiver.KEY_SHOWCASE_LOCATION, location)
        editor.apply()
        val albums = if (currentAlbums.isNotEmpty()) currentAlbums else Albums.enabled(p)
        val updated = mergedSlides(p, albums)
        if (updated.isNotEmpty()) {
            currentIds = idsOf(updated)
            controller.setItems(updated)
            val effectiveMode = mode ?: p.getString(ConfigReceiver.KEY_SHOWCASE_MODE, ConfigReceiver.DEFAULT_SHOWCASE_MODE)
            val effectiveLoc = location ?: p.getString(ConfigReceiver.KEY_SHOWCASE_LOCATION, ConfigReceiver.DEFAULT_SHOWCASE_LOCATION) ?: ""
            val hasCustomMsg = !p.getString(ConfigReceiver.KEY_CUSTOM_MESSAGE, "").isNullOrBlank()
            if (!hasCustomMsg) {
                val msg = when {
                    effectiveMode == "date_range" || DateRangeParser.parse(effectiveLoc) != null -> {
                        val parsed = DateRangeParser.parse(effectiveLoc)
                        val label = parsed?.label ?: effectiveLoc
                        "📍 $label Showcase (${updated.size} photos)"
                    }
                    effectiveMode == "location" -> "📍 $effectiveLoc Showcase (${updated.size} photos)"
                    effectiveMode == "date_descending" -> "📍 Recent Photos Showcase (${updated.size} photos)"
                    effectiveMode == "recent_trip" -> "📍 Recent Visit Showcase (${updated.size} photos)"
                    effectiveMode == "last_7_days" -> "📍 Last 7 Days Showcase (${updated.size} photos)"
                    effectiveMode == "last_30_days" -> "📍 Last 30 Days Showcase (${updated.size} photos)"
                    else -> "Showing All Photos (${updated.size} photos)"
                }
                controller.showTemporaryBanner(msg)
            }
        } else {
            // Revert showcase mode so subsequent cycles don't remain filtered out
            editor.putString(ConfigReceiver.KEY_SHOWCASE_MODE, "all").apply()
            val effectiveLoc = location ?: p.getString(ConfigReceiver.KEY_SHOWCASE_LOCATION, "") ?: ""
            controller.showTemporaryBanner("⚠️ No photos found for '$effectiveLoc' in album")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getStringExtra("action") == "wake" || intent.action == ConfigReceiver.ACTION_WAKE) {
            wakeScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(android.app.KeyguardManager::class.java)
            km?.requestDismissKeyguard(this, null)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        window.attributes = window.attributes.apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        sleepCover = SleepCover(this)

        loader = ImageLoader(this)
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // 1. Build the slideshow's View hierarchy.
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        slideshowContainer = container
        controller = SlideshowController(this, container, loader).apply {
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
            setOnOpenHomeAssistant {
                showHomeAssistant()
            }
        }
        root.addView(slideshowContainer)

        // 2. Build the WebView for the Immortal Flip clock style (hidden by default)
        val webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
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
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
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

        // 3. Build Embedded Home Assistant Dashboard Container & WebView
        buildHomeAssistantView(root)

        // Register Command Receiver
        val cmdFilter = IntentFilter().apply {
            addAction(ConfigReceiver.ACTION_SHOW_DASHBOARD)
            addAction(ConfigReceiver.ACTION_SHOW_SLIDESHOW)
            addAction(ConfigReceiver.ACTION_NEXT_PHOTO)
            addAction(ConfigReceiver.ACTION_PREV_PHOTO)
            addAction(ConfigReceiver.ACTION_SET_MESSAGE)
            addAction(ConfigReceiver.ACTION_CLEAR_MESSAGE)
            addAction(ConfigReceiver.ACTION_WAKE)
            addAction(ConfigReceiver.ACTION_SLEEP)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(ConfigReceiver.ACTION_SET_SHOWCASE)
        }
        registerReceiver(commandReceiver, cmdFilter)

        // Start MQTT if enabled
        MqttManager.startIfEnabled(this)

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

    private var haLoadingView: ProgressBar? = null
    private var haErrorView: TextView? = null

    private fun buildHomeAssistantView(root: FrameLayout) {
        val container = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
            alpha = 0f
            setBackgroundColor(Color.BLACK)
        }

        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                displayZoomControls = false
                builtInZoomControls = false
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = true
                allowContentAccess = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 80) {
                        haLoadingView?.visibility = View.GONE
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                    Log.w("HAWebView", "SSL error encountered, proceeding: $error")
                    handler?.proceed()
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    haLoadingView?.visibility = View.GONE
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    Log.e("HAWebView", "Error $errorCode: $description on $failingUrl")
                    haLoadingView?.visibility = View.GONE
                    haErrorView?.text = "Unable to connect to Home Assistant:\n$description\n\nTap to retry or check URL in Settings."
                    haErrorView?.visibility = View.VISIBLE
                }

                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = false
            }
        }

        // Loading spinner
        val loading = ProgressBar(this).apply {
            val lp = FrameLayout.LayoutParams(Ui.dp(this@SlideshowComposeActivity, 48f), Ui.dp(this@SlideshowComposeActivity, 48f)).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            visibility = View.GONE
        }
        haLoadingView = loading

        // Error message view if HA is unreachable
        val errorText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            setPadding(Ui.dp(this@SlideshowComposeActivity, 32f), 0, Ui.dp(this@SlideshowComposeActivity, 32f), 0)
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER
            }
            layoutParams = lp
            visibility = View.GONE
            setOnClickListener {
                val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
                val raw = prefs.getString(ConfigReceiver.KEY_HA_URL, ConfigReceiver.DEFAULT_HA_URL)?.trim() ?: ""
                if (raw.isNotEmpty()) {
                    visibility = View.GONE
                    haLoadingView?.visibility = View.VISIBLE
                    val finalUrl = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
                    webView.loadUrl(finalUrl)
                }
            }
        }
        haErrorView = errorText

        // Floating pill-shaped "✕ Photos" button to return to slideshow
        val closeBtn = TextView(this).apply {
            text = "✕ Photos"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Ui.bold(this@SlideshowComposeActivity)
            background = Ui.roundRect(0xEE1C1C1E.toInt(), Ui.dp(this@SlideshowComposeActivity, 24f)).apply {
                setStroke(Ui.dp(this@SlideshowComposeActivity, 1.5f), 0x55FFFFFF)
            }
            setPadding(
                Ui.dp(this@SlideshowComposeActivity, 22f),
                Ui.dp(this@SlideshowComposeActivity, 12f),
                Ui.dp(this@SlideshowComposeActivity, 22f),
                Ui.dp(this@SlideshowComposeActivity, 12f)
            )
            val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = Ui.dp(this@SlideshowComposeActivity, 24f)
                rightMargin = Ui.dp(this@SlideshowComposeActivity, 24f)
            }
            layoutParams = lp
            elevation = Ui.dp(this@SlideshowComposeActivity, 6f).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener { hideHomeAssistant() }
        }

        container.addView(webView)
        container.addView(loading)
        container.addView(errorText)
        container.addView(closeBtn)
        root.addView(container)

        haContainer = container
        haWebView = webView

        // Touch resets HA idle watchdog
        webView.setOnTouchListener { _, _ ->
            resetHaIdleTimer()
            false
        }

        // Preload Home Assistant in background on startup if enabled for instant 0ms access
        preloadHomeAssistant()
    }

    private fun preloadHomeAssistant() {
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        val haEnabled = prefs.getBoolean(ConfigReceiver.KEY_HA_EMBEDDED, ConfigReceiver.DEFAULT_HA_EMBEDDED)
        val rawUrl = prefs.getString(ConfigReceiver.KEY_HA_URL, ConfigReceiver.DEFAULT_HA_URL)?.trim() ?: ""
        if (!haEnabled || rawUrl.isEmpty()) return
        val haUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "http://$rawUrl"
        val webView = haWebView ?: return
        if (webView.url != haUrl) {
            haLoadingView?.visibility = View.VISIBLE
            webView.loadUrl(haUrl)
        }
    }

    private fun showHomeAssistant() {
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        val rawUrl = prefs.getString(ConfigReceiver.KEY_HA_URL, ConfigReceiver.DEFAULT_HA_URL)?.trim() ?: ""
        if (rawUrl.isEmpty()) {
            return
        }
        val haUrl = if (rawUrl.startsWith("http://") || rawUrl.startsWith("https://")) rawUrl else "http://$rawUrl"

        val container = haContainer ?: return
        val webView = haWebView ?: return

        container.bringToFront()
        haErrorView?.visibility = View.GONE

        if (webView.url != haUrl) {
            haLoadingView?.visibility = View.VISIBLE
            webView.loadUrl(haUrl)
        }

        container.visibility = View.VISIBLE
        container.animate().alpha(1f).setDuration(300).start()
        resetHaIdleTimer()
    }

    private fun hideHomeAssistant() {
        haIdleHandler.removeCallbacks(haIdleRunnable)
        haContainer?.animate()?.alpha(0f)?.setDuration(300)?.withEndAction {
            haContainer?.visibility = View.GONE
        }?.start()
    }

    private fun resetHaIdleTimer() {
        haIdleHandler.removeCallbacks(haIdleRunnable)
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        val timeoutSec = prefs.getInt(ConfigReceiver.KEY_HA_IDLE_TIMEOUT_SEC, ConfigReceiver.DEFAULT_HA_IDLE_TIMEOUT_SEC)
        if (timeoutSec > 0) {
            haIdleHandler.postDelayed(haIdleRunnable, timeoutSec * 1000L)
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
        if (isScreenAsleep || ScreenControl.isAsleep) {
            wakeScreen()
        }
        if (flipWebView?.visibility == View.VISIBLE) {
            flipWebView?.onResume()
        }
        // Clear any photo retained from a previous run so re-entering the frame
        // doesn't flash the old image before the first new frame loads.
        controller.blank()
        // Re-apply the clock position/size (picks up a Settings "reset" done while away).
        controller.applyClockTransform()
        controller.applyClockOnlyTransform()
        preloadHomeAssistant()
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
            controller.setStatusHint("💡 Ready for your photos · Add in Settings or broadcast #album:<url>")
            controller.checkCustomMessage()
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
        haWebView?.onPause()
        sensorManager.unregisterListener(lightListener)
        handler.removeCallbacks(refreshTick)
        handler.removeCallbacks(scheduleTick)
        controller.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(commandReceiver)
        } catch (_: Exception) {}
        haIdleHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        sensorManager.unregisterListener(lightListener)
        haWebView?.apply {
            loadUrl("about:blank")
            stopLoading()
            destroy()
        }
        flipWebView?.apply {
            loadUrl("about:blank")
            stopLoading()
            destroy()
        }
    }

    private fun applyClockOnlyMode() {
        val prefs = getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        val clockOnlyActive = lowLightClockOnly || scheduledClockOnly
        val useFlipForNight = prefs.getBoolean(ConfigReceiver.KEY_CLOCK_FLIP, ConfigReceiver.DEFAULT_CLOCK_FLIP)

        if (clockOnlyActive) {
            if (useFlipForNight) {
                // Show WebView Flip Clock (hide slideshow completely to prevent any photo bleed)
                slideshowContainer?.visibility = View.GONE
                controller.setClockOnly(false)
                controller.blank()
                controller.stop()
                flipWebView?.visibility = View.VISIBLE
                flipWebView?.onResume()
            } else {
                // Show Classic Native Clock
                flipWebView?.visibility = View.GONE
                flipWebView?.onPause()
                slideshowContainer?.visibility = View.VISIBLE
                if (!controller.running) {
                    controller.start()
                }
                controller.setClockOnly(true)
            }
        } else {
            // Normal Slideshow Mode
            flipWebView?.visibility = View.GONE
            flipWebView?.onPause()
            slideshowContainer?.visibility = View.VISIBLE
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

        // Check for private broadcast message prefix in album titles
        var broadcastMsg = ""
        for (url in currentAlbums) {
            val title = AlbumCache.title(prefs, url)
            if (title != null && (title.startsWith("[Msg]", ignoreCase = true) || title.startsWith("[Msg] ", ignoreCase = true))) {
                val idx = title.indexOf(']')
                val msg = title.substring(idx + 1).trim()
                if (msg.isNotEmpty()) {
                    broadcastMsg = msg
                    break // use the first found message
                }
            }
        }
        controller.setBroadcastMessage(broadcastMsg)

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
            val albumTitle = AlbumCache.title(prefs, url)?.trim() ?: ""
            val rawSlides = AlbumCache.read(prefs, url) ?: emptyList()
            val tagged = if (albumTitle.isNotEmpty()) {
                rawSlides.map { s ->
                    if (s.location.isNullOrEmpty()) {
                        Slide(s.id, s.caption, s.timeMs, s.portrait, albumTitle)
                    } else {
                        s
                    }
                }
            } else {
                rawSlides
            }
            buckets.add(tagged)
        }
        val base = when (
            prefs.getString(
                ConfigReceiver.KEY_ALBUM_PLAYBACK,
                ConfigReceiver.DEFAULT_ALBUM_PLAYBACK,
            ) ?: ConfigReceiver.DEFAULT_ALBUM_PLAYBACK
        ) {
            "album_priority" -> buckets.flatten()
            else -> interleaveSlides(buckets)
        }
        val mode = prefs.getString(ConfigReceiver.KEY_SHOWCASE_MODE, ConfigReceiver.DEFAULT_SHOWCASE_MODE) ?: ConfigReceiver.DEFAULT_SHOWCASE_MODE
        val loc = prefs.getString(ConfigReceiver.KEY_SHOWCASE_LOCATION, ConfigReceiver.DEFAULT_SHOWCASE_LOCATION) ?: ConfigReceiver.DEFAULT_SHOWCASE_LOCATION
        return SlideshowController.filterForShowcase(base, mode, loc)
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
