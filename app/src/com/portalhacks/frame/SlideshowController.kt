package com.portalhacks.frame

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ImageSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.IOException
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.HashSet
import java.util.LinkedList
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.media.AudioTrack
import android.media.AudioFormat
import android.media.AudioAttributes
import android.media.AudioManager

/**
 * Crossfading slideshow. Items are image IDs — either bundled asset paths
 * ("slides/01.png") or remote URLs ("https://...") — loaded asynchronously via
 * [ImageLoader]. The source can be swapped at runtime with
 * [setItems] (used to switch from bundled samples to a Google Photos
 * shared album once it loads).
 *
 * Touch handling is distance-based (works for slow finger swipes):
 *   - drag left  -> next image
 *   - drag right -> previous image
 *   - tap        -> dismiss (runs onDismiss)
 */
class SlideshowController(
    private val context: Context,
    root: FrameLayout,
    private val loader: ImageLoader,
) {

    private val back: ImageView
    private val front: ImageView
    private val status: TextView
    private val info: TextView
    private val clock: TextView
    private val clockBox: LinearLayout
    private val bigClock: FlipClockView // centered, animated flip clock for low-light mode
    private val bigDate: TextView
    private val clockOnlyBox: LinearLayout
    private val clockExit: TextView
    private val dateLine: TextView
    private val clockEditHint: TextView // "drag/pinch/tap" hint shown while editing the clock
    private val shimmer: ShimmerView
    private val timeFmt: DateFormat
    private val bigTimeFmt: DateFormat
    private val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
    private val bigDateFmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())
    private val monthYearFmt = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    private val fahrenheit: Boolean
    private val nightTint: View // warm overlay that fades in at night (Ambient-EQ-lite)
    private val ambientGlow: View // edge vignette tinted to the photo's mood color
    private var weather: Weather.Now? = null // current reading; null until loaded
    private val moonDrawable: Drawable // blue crescent, clear nights
    private val handler = Handler(Looper.getMainLooper())
    private var reqW: Int
    private var reqH: Int
    private var screenPortrait: Boolean // screen taller than wide → pair/stack opposite axis

    // Smart-shuffle memory: ids shown recently (most-recent first) so a reshuffle
    // doesn't immediately replay them, and the last id to avoid a back-to-back repeat.
    private val recentIds = LinkedList<String>()
    private var lastShownId: String? = null
    private var shimmerHidden = false

    // User-tunable settings (read from prefs in the constructor; see PhotosActivity).
    private val intervalMs: Long // time each slide is held
    private val transitionDurationMs: Long // transition animation duration
    private val transitionMode: String // single selected slideshow transition mode
    private val use24Hour: Boolean
    private val shuffle: Boolean // play photos in random order
    private val pairs: Boolean // pair two photos to fill the screen (side-by-side or stacked)
    private val kenBurns: Boolean // cinematic slow pan + zoom while held
    private val showClock: Boolean // clock + weather overlay
    private val nightMode: Boolean // warm night dimming
    private val onThisDay: Boolean // surface "N years ago today" memories
    private val captions: Boolean // photo date captions (lower-right)
    private val faceFraming: Boolean // bias Ken Burns toward detected faces
    private val ambientColor: Boolean // tint chrome to each photo's palette
    private val enhance: Boolean // on-device auto-levels + vibrance
    private val zoomFill: Boolean // single photos: crop to fill (vs whole photo + blurred fill).
                                  // Pairs always fill their half regardless.

    // Ken Burns animation state.
    private val rnd = Random()
    private var kbAnim: ValueAnimator? = null
    private var kbPath: KenBurns? = null
    private var enhanceFilter: ColorMatrixColorFilter? = null // per-slide, or null

    private var items: MutableList<Slide> = ArrayList()
    private var remote = false
    private var index = 0
    private var curIsPair = false // current frame shows a paired (two-photo) composite
    private var running = false
    private var slideshowPaused = false
    private var animGen = 0L
    private var onDismiss: Runnable? = null
    private var onSettings: Runnable? = null
    private var clockOnly = false // low-light mode: black screen, clock only
    private var lastChimedHour = -1

    // Clock widget transform (long-press the clock to edit; drag to move, pinch to resize). dx/dy
    // are translation as a fraction of screen W/H; scale is a size multiplier. Persisted to prefs.
    private var editingClock = false
    private var clockDx = 0f
    private var clockDy = 0f
    private var clockScale = 1f
    private var editingDate = false  // editing date/weather overlay
    private var dateDx = 0f
    private var dateDy = 0f
    private var dateScale = 1f
    private val actionMenuBackdrop: View

    // Battery status state.
    private var batteryLevel = -1
    private var batteryIsCharging = false
    private var batteryReceiverRegistered = false
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            batteryIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL
            updateClock()
        }
    }

    // Play/Pause Overlay View
    private lateinit var playButtonOverlay: FrameLayout
    private val actionMenuCard: LinearLayout
    private val actionMenuTitle: TextView
    private val actionMenuPauseResume: TextView
    private val actionMenuSettings: TextView
    private val actionMenuClose: TextView

    init {
        val dm = context.resources.displayMetrics
        reqW = if (dm.widthPixels > 0) dm.widthPixels else 1280
        reqH = if (dm.heightPixels > 0) dm.heightPixels else 800
        screenPortrait = reqH > reqW

        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        intervalMs = prefs.getLong(ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS)
        transitionDurationMs = prefs.getLong(ConfigReceiver.KEY_FADE_MS, ConfigReceiver.DEFAULT_FADE_MS)
        transitionMode = prefs.getString(ConfigReceiver.KEY_TRANSITION, ConfigReceiver.DEFAULT_TRANSITION)
            ?: ConfigReceiver.DEFAULT_TRANSITION
        use24Hour = if (prefs.contains(ConfigReceiver.KEY_CLOCK_24H)) {
            prefs.getBoolean(ConfigReceiver.KEY_CLOCK_24H, ConfigReceiver.DEFAULT_CLOCK_24H)
        } else {
            android.text.format.DateFormat.is24HourFormat(context)
        }
        shuffle = prefs.getBoolean(ConfigReceiver.KEY_SHUFFLE, false)
        pairs = prefs.getBoolean(ConfigReceiver.KEY_PAIRS, ConfigReceiver.DEFAULT_PAIRS)
        kenBurns = prefs.getBoolean(ConfigReceiver.KEY_KEN_BURNS, ConfigReceiver.DEFAULT_KEN_BURNS)
        showClock = prefs.getBoolean(ConfigReceiver.KEY_CLOCK, ConfigReceiver.DEFAULT_CLOCK)
        fahrenheit = prefs.getBoolean(
            ConfigReceiver.KEY_WEATHER_FAHRENHEIT,
            ConfigReceiver.DEFAULT_WEATHER_FAHRENHEIT,
        )
        nightMode = prefs.getBoolean(ConfigReceiver.KEY_NIGHT, ConfigReceiver.DEFAULT_NIGHT)
        onThisDay = prefs.getBoolean(ConfigReceiver.KEY_ON_THIS_DAY, ConfigReceiver.DEFAULT_ON_THIS_DAY)
        captions = prefs.getBoolean(ConfigReceiver.KEY_CAPTIONS, ConfigReceiver.DEFAULT_CAPTIONS)
        faceFraming = prefs.getBoolean(ConfigReceiver.KEY_FACE, ConfigReceiver.DEFAULT_FACE)
        ambientColor = prefs.getBoolean(ConfigReceiver.KEY_AMBIENT, ConfigReceiver.DEFAULT_AMBIENT)
        enhance = prefs.getBoolean(ConfigReceiver.KEY_ENHANCE, ConfigReceiver.DEFAULT_ENHANCE)
        zoomFill = prefs.getBoolean(ConfigReceiver.KEY_ZOOM_FILL, ConfigReceiver.DEFAULT_ZOOM_FILL)
        clockDx = prefs.getFloat(ConfigReceiver.KEY_CLOCK_DX, ConfigReceiver.DEFAULT_CLOCK_DX)
        clockDy = prefs.getFloat(ConfigReceiver.KEY_CLOCK_DY, ConfigReceiver.DEFAULT_CLOCK_DY)
        clockScale = prefs.getFloat(ConfigReceiver.KEY_CLOCK_SCALE, ConfigReceiver.DEFAULT_CLOCK_SCALE)
        dateDx = prefs.getFloat(ConfigReceiver.KEY_DATE_DX, ConfigReceiver.DEFAULT_DATE_DX)
        dateDy = prefs.getFloat(ConfigReceiver.KEY_DATE_DY, ConfigReceiver.DEFAULT_DATE_DY)
        dateScale = prefs.getFloat(ConfigReceiver.KEY_DATE_SCALE, ConfigReceiver.DEFAULT_DATE_SCALE)
        monthYearFmt.timeZone = TimeZone.getTimeZone("UTC")

        root.setBackgroundColor(Color.BLACK)
        // Pinch-resize and per-line transforms draw outside layout bounds; don't clip the overlay.
        root.clipChildren = false
        root.clipToPadding = false
        back = newImageView()
        front = newImageView()
        front.alpha = 0f

        val margin = Ui.dp(context, 28f)
        // Height the clock box (and, matching it, the photo-date caption) sit off the
        // bottom — so "2 months ago" lines up with the clock's "Sun, Jun 14" date line.
        val clockBottom = Ui.dp(context, 95f)

        // Gradient scrims so the white system-overlay pills (top) and our caption
        // text (bottom) stay legible over bright photos — per the Portal design rules.
        val topScrim = View(context)
        topScrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x99000000.toInt(), 0x00000000),
        )
        val tsp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 96f),
        )
        tsp.gravity = Gravity.TOP
        topScrim.layoutParams = tsp

        val bottomScrim = View(context)
        bottomScrim.background = GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xB3000000.toInt(), 0x00000000),
        )
        val bsp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 150f),
        )
        bsp.gravity = Gravity.BOTTOM
        bottomScrim.layoutParams = bsp

        // Loading / error hint — moved to the top so it doesn't fight the clock.
        status = TextView(context)
        status.setTextColor(Ui.TEXT_MUTED)
        status.typeface = Ui.medium(context)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        status.setShadowLayer(6f, 0f, 1f, Color.BLACK)
        val sp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        sp.gravity = Gravity.TOP or Gravity.START
        sp.leftMargin = margin
        sp.topMargin = Ui.dp(context, 24f)
        status.layoutParams = sp

        // Lower-right: photo date (and location when available).
        info = TextView(context)
        info.setTextColor(0xFFF0F0F0.toInt())
        info.typeface = Ui.medium(context)
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        info.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        val ip = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        ip.gravity = Gravity.BOTTOM or Gravity.END
        ip.rightMargin = margin
        ip.bottomMargin = clockBottom // align with the clock's date line, not the screen edge
        info.layoutParams = ip

        // Bottom-left: a large clock with a "day, date · weather" line beneath it
        // (Nest/Portal photo-frame style). Time uses a clean, AM/PM-free format.
        timeFmt = SimpleDateFormat(
            if (use24Hour) "H:mm" else "h:mm",
            Locale.getDefault(),
        )
        bigTimeFmt = SimpleDateFormat(if (use24Hour) "HH:mm" else "h:mm a", Locale.getDefault())
        clock = TextView(context)
        clock.setTextColor(Color.WHITE)
        clock.typeface = Ui.clockFace(context) // match the Portal native clock
        clock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80f)
        clock.setShadowLayer(12f, 0f, 2f, Color.BLACK)
        clock.includeFontPadding = false
        val moonPx = Ui.dp(context, 22f)
        val md = BitmapDrawable(context.resources, Ui.crescent(moonPx, 0xFF5FA8FF.toInt()))
        md.setBounds(0, 0, moonPx, moonPx)
        moonDrawable = md
        dateLine = TextView(context)
        dateLine.setTextColor(0xFFF0F0F0.toInt())
        dateLine.typeface = Ui.medium(context)
        dateLine.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        dateLine.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        clockBox = LinearLayout(context)
        clockBox.orientation = LinearLayout.VERTICAL
        clockBox.clipChildren = false
        clockBox.clipToPadding = false
        clockBox.addView(clock)
        clockBox.addView(dateLine)
        val cbp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        cbp.gravity = Gravity.BOTTOM or Gravity.START
        cbp.leftMargin = margin
        cbp.bottomMargin = clockBottom // match the Portal home clock's height off the bottom
        clockBox.layoutParams = cbp

        // Instruction shown only while the clock is being moved/resized (long-press to enter).
        clockEditHint = TextView(context)
        clockEditHint.text = "Drag to move · pinch to resize · tap to finish"
        clockEditHint.setTextColor(0xFFF0F0F0.toInt())
        clockEditHint.typeface = Ui.medium(context)
        clockEditHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        clockEditHint.gravity = Gravity.CENTER_HORIZONTAL
        clockEditHint.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        clockEditHint.visibility = View.GONE
        val cehp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        cehp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        cehp.topMargin = Ui.dp(context, 40f)
        clockEditHint.layoutParams = cehp

        // Warm overlay over the photo that fades in at night (Ambient-EQ-lite): the
        // image is dimmed by the window brightness and tinted cozy-warm here.
        nightTint = View(context)
        nightTint.setBackgroundColor(0xFFFF8A2A.toInt())
        nightTint.alpha = 0f
        nightTint.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

        // Edge vignette tinted to each photo's mood color (Ambilight-for-photos).
        ambientGlow = View(context)
        ambientGlow.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        if (!ambientColor) {
            ambientGlow.visibility = View.GONE
        }

        // Shimmer over the dark first frame so it never looks "stuck" while loading.
        shimmer = ShimmerView(context)
        shimmer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )

        // Centered, larger, weather-less clock for low-light "clock only" mode — a touch
        // dimmer than the overlay clock. Hidden until setClockOnly(true).
        bigClock = FlipClockView(context, Ui.clockFace(context), Ui.medium(context))
        bigDate = TextView(context)
        bigDate.setTextColor(0xFF9AA0AE.toInt())
        bigDate.typeface = Ui.medium(context)
        bigDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        bigDate.gravity = Gravity.CENTER_HORIZONTAL
        bigDate.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        bigDate.setSingleLine(true)
        clockOnlyBox = LinearLayout(context)
        clockOnlyBox.orientation = LinearLayout.VERTICAL
        clockOnlyBox.gravity = Gravity.CENTER_HORIZONTAL
        clockOnlyBox.clipChildren = false
        clockOnlyBox.clipToPadding = false
        clockOnlyBox.addView(
            bigClock,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        val bdlp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        bdlp.topMargin = Ui.dp(context, 4f)
        clockOnlyBox.addView(bigDate, bdlp)
        // Full-width box so the centered clock never clips; vertically centred on screen.
        val colp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        colp.gravity = Gravity.CENTER_VERTICAL
        clockOnlyBox.layoutParams = colp
        clockOnlyBox.visibility = View.GONE

        clockExit = TextView(context)
        clockExit.text = "Exit"
        clockExit.setTextColor(Color.WHITE)
        clockExit.typeface = Ui.medium(context)
        clockExit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        clockExit.gravity = Gravity.CENTER
        clockExit.background = Ui.roundRect(0x33000000, Ui.dp(context, 14f)).apply {
            setStroke(Ui.dp(context, 1f), 0x55FFFFFF)
        }
        clockExit.setPadding(Ui.dp(context, 18f), Ui.dp(context, 10f), Ui.dp(context, 18f), Ui.dp(context, 10f))
        clockExit.visibility = View.GONE
        clockExit.setOnClickListener { onDismiss?.run() }
        val exp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        )
        exp.gravity = Gravity.TOP or Gravity.END
        exp.topMargin = Ui.dp(context, 28f)
        exp.rightMargin = Ui.dp(context, 28f)
        clockExit.layoutParams = exp

        if (!showClock) {
            clockBox.visibility = View.GONE
        }
        if (!captions) {
            info.visibility = View.GONE
        }

        actionMenuBackdrop = View(context).apply {
            setBackgroundColor(0xA0000000.toInt())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { hideActionMenu() }
        }
        actionMenuCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 24f), Ui.dp(context, 22f), Ui.dp(context, 24f), Ui.dp(context, 22f))
            background = Ui.roundRect(0xFF12161E.toInt(), Ui.dp(context, 24f)).apply {
                setStroke(Ui.dp(context, 1f), 0x2FFFFFFF)
            }
            visibility = View.GONE
        }
        actionMenuTitle = TextView(context).apply {
            text = "Slideshow menu"
            setTextColor(0xFFF0F0F0.toInt())
            typeface = Ui.medium(context)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        actionMenuPauseResume = menuActionButton()
        actionMenuSettings = menuActionButton()
        actionMenuClose = menuActionButton()
        actionMenuPauseResume.setOnClickListener {
            if (slideshowPaused) resumeSlideshow() else pauseSlideshow()
            hideActionMenu()
        }
        actionMenuSettings.setOnClickListener {
            hideActionMenu()
            onSettings?.run()
        }
        actionMenuClose.setOnClickListener { hideActionMenu() }
        refreshActionMenuLabels()
        actionMenuCard.addView(actionMenuTitle)
        actionMenuCard.addView(menuSpacer(12))
        actionMenuCard.addView(actionMenuPauseResume)
        actionMenuCard.addView(menuSpacer(8))
        actionMenuCard.addView(actionMenuSettings)
        actionMenuCard.addView(menuSpacer(8))
        actionMenuCard.addView(actionMenuClose)
        val menuLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.CENTER
        }
        actionMenuCard.layoutParams = menuLp

        root.addView(back)
        root.addView(front)
        root.addView(ambientGlow)
        root.addView(nightTint)
        root.addView(shimmer)
        root.addView(topScrim)
        root.addView(bottomScrim)
        root.addView(status)
        root.addView(info)
        root.addView(clockBox)
        root.addView(clockOnlyBox)
        root.addView(clockEditHint)
        root.addView(buildTouchOverlay())
        initPlayButton()
        root.addView(playButtonOverlay)
        root.addView(clockExit)
        root.addView(actionMenuBackdrop)
        root.addView(actionMenuCard)
        clockBox.post { applyClockTransformNow() } // apply saved position/size once laid out
        dateLine.post { applyDateTransformNow() } // apply saved date position/size once laid out

        // Run clock/night + weather + shimmer from the start so they're alive even
        // during the initial "Loading…" wait before the first photo arrives.
        startClock()
        startWeather()
        shimmer.startSweep()
    }

    fun setOnDismiss(onDismiss: Runnable?) {
        this.onDismiss = onDismiss
    }

    /** Long-press anywhere on the slideshow runs this (used to open Photos setup). */
    fun setOnSettings(onSettings: Runnable?) {
        this.onSettings = onSettings
    }

    fun setStatusHint(text: String?) {
        status.text = text
    }

    fun pauseSlideshow() {
        if (slideshowPaused) {
            return
        }
        slideshowPaused = true
        handler.removeCallbacks(autoTick)
        refreshActionMenuLabels()
        if (running && items.isNotEmpty()) {
            status.text = "Paused on current photo"
        }
        showPlayButtonOverlay()
    }

    fun resumeSlideshow() {
        if (!slideshowPaused) {
            return
        }
        slideshowPaused = false
        refreshActionMenuLabels()
        if (running && items.isNotEmpty()) {
            status.text = ""
            scheduleAuto()
        }
        hidePlayButtonOverlay()
    }

    private fun showActionMenu() {
        if (editingClock || clockOnly) {
            return
        }
        refreshActionMenuLabels()
        actionMenuBackdrop.visibility = View.VISIBLE
        actionMenuCard.visibility = View.VISIBLE
    }

    private fun hideActionMenu() {
        actionMenuBackdrop.visibility = View.GONE
        actionMenuCard.visibility = View.GONE
    }

    private fun refreshActionMenuLabels() {
        actionMenuPauseResume.text = if (slideshowPaused) "Resume slideshow" else "Pause on current photo"
        actionMenuSettings.text = "Settings"
        actionMenuClose.text = "Close"
    }

    private fun menuActionButton(): TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        typeface = Ui.medium(context)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setPadding(Ui.dp(context, 18f), Ui.dp(context, 14f), Ui.dp(context, 18f), Ui.dp(context, 14f))
        background = Ui.roundRect(0xFF1E2530.toInt(), Ui.dp(context, 16f)).apply {
            setStroke(Ui.dp(context, 1f), 0x44FFFFFF)
        }
    }

    private fun menuSpacer(h: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            Ui.dp(context, h.toFloat()),
        )
    }

    // ------------------------------------------------ clock move/resize

    /** Re-read the clock transform from prefs and apply it (e.g. after a Settings "reset"). */
    fun applyClockTransform() {
        val p = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        clockDx = p.getFloat(ConfigReceiver.KEY_CLOCK_DX, ConfigReceiver.DEFAULT_CLOCK_DX)
        clockDy = p.getFloat(ConfigReceiver.KEY_CLOCK_DY, ConfigReceiver.DEFAULT_CLOCK_DY)
        clockScale = p.getFloat(ConfigReceiver.KEY_CLOCK_SCALE, ConfigReceiver.DEFAULT_CLOCK_SCALE)
        clockBox.post { applyClockTransformNow() }
    }

    private fun applyClockTransformNow() {
        clockBox.pivotX = clockBox.width / 2f
        clockBox.pivotY = clockBox.height / 2f
        clockBox.scaleX = clockScale
        clockBox.scaleY = clockScale
        clockBox.translationX = clockDx * reqW
        clockBox.translationY = clockDy * reqH
    }

    private fun persistClockTransform() {
        context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(ConfigReceiver.KEY_CLOCK_DX, clockDx)
            .putFloat(ConfigReceiver.KEY_CLOCK_DY, clockDy)
            .putFloat(ConfigReceiver.KEY_CLOCK_SCALE, clockScale)
            .apply()
    }

    fun applyDateTransform() {
        val p = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        dateDx = p.getFloat(ConfigReceiver.KEY_DATE_DX, ConfigReceiver.DEFAULT_DATE_DX)
        dateDy = p.getFloat(ConfigReceiver.KEY_DATE_DY, ConfigReceiver.DEFAULT_DATE_DY)
        dateScale = p.getFloat(ConfigReceiver.KEY_DATE_SCALE, ConfigReceiver.DEFAULT_DATE_SCALE)
        dateLine.post { applyDateTransformNow() }
    }

    private fun applyDateTransformNow() {
        dateLine.pivotX = dateLine.width / 2f
        dateLine.pivotY = dateLine.height / 2f
        dateLine.scaleX = dateScale
        dateLine.scaleY = dateScale
        dateLine.translationX = dateDx * reqW
        dateLine.translationY = dateDy * reqH
    }

    private fun persistDateTransform() {
        context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putFloat(ConfigReceiver.KEY_DATE_DX, dateDx)
            .putFloat(ConfigReceiver.KEY_DATE_DY, dateDy)
            .putFloat(ConfigReceiver.KEY_DATE_SCALE, dateScale)
            .apply()
    }

    /** True if (x,y) in root coords falls on the clock (its scaled bounds, padded for easy grab). */
    private fun isOnClock(x: Float, y: Float): Boolean {
        if (clockBox.width == 0) return false
        val pad = Ui.dp(context, 24f)
        val cx = clockBox.left + clockBox.translationX + clockBox.width / 2f
        val cy = clockBox.top + clockBox.translationY + clockBox.height / 2f
        val halfW = clockBox.width / 2f * clockScale + pad
        val halfH = clockBox.height / 2f * clockScale + pad
        return x >= cx - halfW && x <= cx + halfW && y >= cy - halfH && y <= cy + halfH
    }

    /** True if (x,y) in root coords falls on the date/weather line. */
    private fun isOnDate(x: Float, y: Float): Boolean {
        if (dateLine.width == 0) return false
        val pad = Ui.dp(context, 16f)
        // dateLine is a child of clockBox, so account for clockBox's transform when converting to root coords
        val boxCx = clockBox.left + clockBox.translationX + clockBox.width / 2f
        val boxCy = clockBox.top + clockBox.translationY + clockBox.height / 2f
        val dateLocalCx = dateLine.left + dateLine.translationX + dateLine.width / 2f
        val dateLocalCy = dateLine.top + dateLine.translationY + dateLine.height / 2f
        // Scale and translate from parent box space to root space
        val cx = boxCx + (dateLocalCx - clockBox.width / 2f) * clockScale
        val cy = boxCy + (dateLocalCy - clockBox.height / 2f) * clockScale
        val halfW = dateLine.width / 2f * dateScale * clockScale + pad
        val halfH = dateLine.height / 2f * dateScale * clockScale + pad
        return x >= cx - halfW && x <= cx + halfW && y >= cy - halfH && y <= cy + halfH
    }

    private fun enterClockEdit() {
        editingClock = true
        clockBox.background = Ui.roundRect(0x33000000, Ui.dp(context, 14f)).apply {
            setStroke(Ui.dp(context, 2f), Ui.BLUE)
        }
        clockEditHint.animate().cancel()
        clockEditHint.text = "Drag to move · pinch to resize · tap to finish"
        clockEditHint.alpha = 1f
        clockEditHint.visibility = View.VISIBLE
    }

    private fun exitClockEdit() {
        editingClock = false
        clockBox.background = null
        clockEditHint.animate().cancel()
        clockEditHint.visibility = View.GONE
        clockEditHint.alpha = 1f
        persistClockTransform()
    }

    private fun initPlayButton() {
        playButtonOverlay = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            visibility = View.GONE
            setOnClickListener {
                resumeSlideshow()
            }
        }

        val menuContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = Ui.dp(context, 24f)
                rightMargin = Ui.dp(context, 24f)
            }
            clipChildren = false
            clipToPadding = false
        }

        val settingsBtn = TextView(context).apply {
            text = "Settings"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            typeface = Ui.medium(context)
            gravity = Gravity.CENTER
            val paddingH = Ui.dp(context, 20f)
            val paddingV = Ui.dp(context, 10f)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x55000000)
                cornerRadius = Ui.dp(context, 22f).toFloat()
                setStroke(Ui.dp(context, 1f), 0x80FFFFFF.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onSettings?.run()
            }
        }

        val exitBtn = TextView(context).apply {
            text = "Exit"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            typeface = Ui.medium(context)
            gravity = Gravity.CENTER
            val paddingH = Ui.dp(context, 24f)
            val paddingV = Ui.dp(context, 10f)
            setPadding(paddingH, paddingV, paddingH, paddingV)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x55FF3B30.toInt())
                cornerRadius = Ui.dp(context, 22f).toFloat()
                setStroke(Ui.dp(context, 1f), 0x80FF3B30.toInt())
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onDismiss?.run()
            }
        }

        val lpSettings = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val lpExit = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = Ui.dp(context, 12f)
        }

        menuContainer.addView(settingsBtn, lpSettings)
        menuContainer.addView(exitBtn, lpExit)
        playButtonOverlay.addView(menuContainer)
    }

    private fun showPlayButtonOverlay() {
        playButtonOverlay.animate().cancel()
        playButtonOverlay.alpha = 0f
        playButtonOverlay.visibility = View.VISIBLE
        playButtonOverlay.animate().alpha(1f).setDuration(300).start()
    }

    private fun hidePlayButtonOverlay() {
        playButtonOverlay.animate().cancel()
        playButtonOverlay.animate().alpha(0f).setDuration(200).withEndAction {
            playButtonOverlay.visibility = View.GONE
        }.start()
    }

    private fun enterDateEdit() {
        editingDate = true
        dateLine.background = Ui.roundRect(0x33000000, Ui.dp(context, 8f)).apply {
            setStroke(Ui.dp(context, 1f), Ui.BLUE)
        }
    }

    private fun exitDateEdit() {
        editingDate = false
        dateLine.background = null
        persistDateTransform()
    }

    /** Move the date line by a touch delta, clamped so its centre stays on screen. */
    private fun dragDateBy(dx: Float, dy: Float) {
        val baseCx = dateLine.left + dateLine.width / 2f
        val baseCy = dateLine.top + dateLine.height / 2f
        val edge = Ui.dp(context, 8f).toFloat()
        val w = if (reqW > 0) reqW.toFloat() else dateLine.rootView.width.toFloat()
        val h = if (reqH > 0) reqH.toFloat() else dateLine.rootView.height.toFloat()
        val tx = (dateLine.translationX + dx).coerceIn(edge - baseCx, w - edge - baseCx)
        val ty = (dateLine.translationY + dy).coerceIn(edge - baseCy, h - edge - baseCy)
        dateLine.translationX = tx
        dateLine.translationY = ty
        if (w > 0) dateDx = tx / w
        if (h > 0) dateDy = ty / h
    }

    private fun applyDateScale() {
        dateLine.pivotX = dateLine.width / 2f
        dateLine.pivotY = dateLine.height / 2f
        dateLine.scaleX = dateScale
        dateLine.scaleY = dateScale
    }

    /** Move the clock by a touch delta, clamped so its centre stays on screen. */
    private fun dragClockBy(dx: Float, dy: Float) {
        val baseCx = clockBox.left + clockBox.width / 2f
        val baseCy = clockBox.top + clockBox.height / 2f
        val edge = Ui.dp(context, 8f).toFloat()
        val w = if (reqW > 0) reqW.toFloat() else clockBox.rootView.width.toFloat()
        val h = if (reqH > 0) reqH.toFloat() else clockBox.rootView.height.toFloat()
        val tx = (clockBox.translationX + dx).coerceIn(edge - baseCx, w - edge - baseCx)
        val ty = (clockBox.translationY + dy).coerceIn(edge - baseCy, h - edge - baseCy)
        clockBox.translationX = tx
        clockBox.translationY = ty
        if (w > 0) clockDx = tx / w
        if (h > 0) clockDy = ty / h
    }

    private fun applyClockScale() {
        clockBox.pivotX = clockBox.width / 2f
        clockBox.pivotY = clockBox.height / 2f
        clockBox.scaleX = clockScale
        clockBox.scaleY = clockScale
    }

    private fun newImageView(): ImageView {
        val iv = ImageView(context)
        iv.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        iv.scaleType = ImageView.ScaleType.CENTER_CROP
        return iv
    }

    private fun buildTouchOverlay(): View {
        val overlay = View(context)
        overlay.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        overlay.isClickable = true
        overlay.isFocusable = true
        overlay.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var downTime = 0L
            private var lastX = 0f
            private var lastY = 0f
            private var handled = false
            private var moved = false
            private var pinching = false
            private var pinchStartDist = 0f
            private var pinchBaseScale = 1f
            private var pendingLong: Runnable? = null
            private var datePinching = false  // track pinch on date layer
            private var datePinchStartDist = 0f
            private var datePinchBaseScale = 1f

            private fun cancelLong() {
                pendingLong?.let {
                    handler.removeCallbacks(it)
                    pendingLong = null
                }
            }

            private fun twoPointerDist(e: MotionEvent): Float {
                if (e.pointerCount < 2) return 0f
                return Math.hypot(
                    (e.getX(0) - e.getX(1)).toDouble(), (e.getY(0) - e.getY(1)).toDouble(),
                ).toFloat()
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                        downTime = e.eventTime
                        handled = false
                        moved = false
                        pinching = false
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                        cancelLong()
                        if (!editingClock && !editingDate) {
                            // Long-press ON the clock → grab it for edit; ON date → edit date; elsewhere → settings.
                            val onClock = isOnClock(e.x, e.y)
                            val onDate = isOnDate(e.x, e.y)
                            if (onClock || onDate || onSettings != null) {
                                val pl = Runnable {
                                    pendingLong = null
                                    handled = true // suppress the tap-dismiss on release
                                    if (onClock) enterClockEdit() else if (onDate) enterDateEdit() else pauseSlideshow()
                                }
                                pendingLong = pl
                                handler.postDelayed(pl, LONG_PRESS_MS)
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (editingClock) {
                            cancelLong()
                            pinching = true
                            pinchStartDist = twoPointerDist(e)
                            pinchBaseScale = clockScale
                        } else if (editingDate) {
                            cancelLong()
                            datePinching = true
                            datePinchStartDist = twoPointerDist(e)
                            datePinchBaseScale = dateScale
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (editingClock) {
                            if (pinching && e.pointerCount >= 2) {
                                val d = twoPointerDist(e)
                                if (pinchStartDist > 0f) {
                                    clockScale = (pinchBaseScale * d / pinchStartDist)
                                        .coerceIn(CLOCK_SCALE_MIN, CLOCK_SCALE_MAX)
                                    applyClockScale()
                                }
                            } else {
                                dragClockBy(e.x - lastX, e.y - lastY)
                                lastX = e.x; lastY = e.y
                                if (abs(e.x - downX) > TAP_SLOP || abs(e.y - downY) > TAP_SLOP) moved = true
                            }
                        } else if (editingDate) {
                            if (datePinching && e.pointerCount >= 2) {
                                val d = twoPointerDist(e)
                                if (datePinchStartDist > 0f) {
                                    dateScale = (datePinchBaseScale * d / datePinchStartDist)
                                        .coerceIn(CLOCK_SCALE_MIN, CLOCK_SCALE_MAX)
                                    applyDateScale()
                                }
                            } else {
                                dragDateBy(e.x - lastX, e.y - lastY)
                                lastX = e.x; lastY = e.y
                                if (abs(e.x - downX) > TAP_SLOP || abs(e.y - downY) > TAP_SLOP) moved = true
                            }
                        } else if (!handled) {
                            val dx = e.x - downX
                            val dy = e.y - downY
                            if (abs(dx) > TAP_SLOP || abs(dy) > TAP_SLOP) {
                                cancelLong() // finger moved — not a long press
                            }
                            if (abs(dx) > SWIPE_MIN_DISTANCE && abs(dx) > abs(dy)) {
                                handled = true
                                if (dx < 0) showNext() else showPrevious()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (editingClock && pinching) {
                            persistClockTransform()
                            pinching = false
                            // Continue dragging with whichever pointer remains (avoid a jump).
                            val rem = if (e.actionIndex == 0) 1 else 0
                            if (e.pointerCount > rem) { lastX = e.getX(rem); lastY = e.getY(rem) }
                            moved = true
                        } else if (editingDate && datePinching) {
                            persistDateTransform()
                            datePinching = false
                            val rem = if (e.actionIndex == 0) 1 else 0
                            if (e.pointerCount > rem) { lastX = e.getX(rem); lastY = e.getY(rem) }
                            moved = true
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        cancelLong()
                        if (editingClock) {
                            val dt = e.eventTime - downTime
                            if (!moved && !pinching && abs(e.x - downX) < TAP_SLOP &&
                                abs(e.y - downY) < TAP_SLOP && dt < TAP_TIMEOUT_MS
                            ) {
                                exitClockEdit() // a clean tap finishes editing
                            } else {
                                persistClockTransform() // a drag/pinch ended — stay in edit mode
                            }
                            pinching = false
                        } else if (editingDate) {
                            val dt = e.eventTime - downTime
                            if (!moved && !datePinching && abs(e.x - downX) < TAP_SLOP &&
                                abs(e.y - downY) < TAP_SLOP && dt < TAP_TIMEOUT_MS
                            ) {
                                exitDateEdit() // a clean tap finishes editing
                            } else {
                                persistDateTransform() // a drag/pinch ended — stay in edit mode
                            }
                            datePinching = false
                        } else if (!handled) {
                            val dx = e.x - downX
                            val dy = e.y - downY
                            val dt = e.eventTime - downTime
                            if (abs(dx) > SWIPE_MIN_DISTANCE && abs(dx) > abs(dy)) {
                                if (dx < 0) showNext() else showPrevious()
                            } else if (abs(dx) < TAP_SLOP && abs(dy) < TAP_SLOP &&
                                dt < TAP_TIMEOUT_MS
                            ) {
                                if (slideshowPaused) {
                                    resumeSlideshow()
                                } else {
                                    pauseSlideshow()
                                }
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        cancelLong()
                        if (editingClock) { pinching = false; persistClockTransform() }
                        else if (editingDate) { datePinching = false; persistDateTransform() }
                        return true
                    }
                    else -> return true
                }
            }
        })
        return overlay
    }

    fun start() {
        running = true
        registerBatteryReceiver()
        startClock()
        applyDateTransform()  // apply saved date overlay transform
        startWeather()
        if (!shimmerHidden) {
            shimmer.startSweep()
        }
        if (items.isEmpty()) {
            items = assetItems()
            remote = false
            if (shuffle) {
                smartShuffle(items)
            }
        }
        if (onThisDay) {
            promoteOnThisDay(items) // no-op for bundled samples (no dates)
        }
        if (items.isEmpty()) {
            status.text = "No slides found in assets/$SLIDES_DIR"
            back.setBackgroundColor(Color.DKGRAY)
            return
        }
        index = 0
        Log.i(
            TAG,
            "Slideshow started with " + items.size +
                (if (remote) " album photos" else " bundled slides"),
        )
        if (clockOnly) {
            startClock()
            return
        }
        showImmediate(0)

        // Show discoverability hint for the clock if at default transform
        if (clockDx == 0f && clockDy == 0f && clockScale == 1f) {
            clockEditHint.text = "💡 Long-press the clock to move or resize it"
            clockEditHint.animate().cancel()
            clockEditHint.alpha = 0f
            clockEditHint.visibility = View.VISIBLE
            clockEditHint.animate().alpha(1f).setDuration(500).withEndAction {
                clockEditHint.animate().alpha(0f).setStartDelay(8000).setDuration(1000).withEndAction {
                    clockEditHint.visibility = View.GONE
                    clockEditHint.alpha = 1f
                }.start()
            }.start()
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(autoTick)
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(weatherTick)
        shimmer.stopSweep()
        front.animate().cancel()
        kbAnim?.let {
            it.cancel()
            kbAnim = null
        }
        if (batteryReceiverRegistered) {
            try {
                context.unregisterReceiver(batteryReceiver)
            } catch (e: Exception) {
                // ignore
            }
            batteryReceiverRegistered = false
        }
        hidePlayButtonOverlay()
    }

    /**
     * Re-read the screen size/orientation after a device rotation. The host Activity handles
     * orientation config changes itself (it isn't recreated), so without this the controller keeps
     * the dimensions and pairing axis it captured at construction — leaving e.g. top/bottom stacks
     * on a now-landscape screen. Updates the Ken Burns target size and the pairing axis, then
     * re-renders the current slide so side-by-side ↔ top/bottom flips to match the new orientation.
     */
    fun onScreenConfigChanged() {
        val dm = context.resources.displayMetrics
        val w = if (dm.widthPixels > 0) dm.widthPixels else reqW
        val h = if (dm.heightPixels > 0) dm.heightPixels else reqH
        if (w == reqW && h == reqH) {
            return // no real dimension change
        }
        reqW = w
        reqH = h
        screenPortrait = reqH > reqW
        if (running && items.isNotEmpty() && index in items.indices) {
            showImmediate(index) // rebuild the current frame for the new orientation
        }
    }

    /**
     * Clear both image layers to black immediately. Called when the frame comes to
     * the foreground so a resumed/relaunched instance doesn't briefly show the
     * PREVIOUS run's last photo (retained in the ImageView) before the new first
     * frame loads. Bumps the anim generation so any in-flight load is discarded.
     */
    fun blank() {
        animGen++
        kbAnim?.let {
            it.cancel()
            kbAnim = null
        }
        front.animate().cancel()
        back.animate().cancel()
        front.setImageDrawable(null)
        front.alpha = 0f
        front.scaleX = 1f
        front.scaleY = 1f
        front.translationX = 0f
        front.translationY = 0f
        back.setImageDrawable(null)
        back.alpha = 1f
        back.scaleX = 1f
        back.scaleY = 1f
        back.translationX = 0f
        back.translationY = 0f
        back.colorFilter = null
        front.colorFilter = null
        info.text = ""
        ambientGlow.alpha = 0f
    }

    /**
     * Low-light "clock only" mode: a black screen showing just the clock (photos paused).
     * Mirrors the Portal night-mode "only show clock in low light" behaviour. Driven by
     * the ambient light sensor in [SlideshowComposeActivity].
     */
    fun setClockOnly(on: Boolean) {
        if (clockOnly == on) {
            return
        }
        clockOnly = on
        if (on) {
            handler.removeCallbacks(autoTick) // pause advancing
            shimmer.stopSweep()
            blank() // photos -> black
            clockBox.visibility = View.GONE // hide the bottom overlay clock
            clockOnlyBox.visibility = View.VISIBLE // big centered clock instead
            clockExit.visibility = View.VISIBLE
            startClock() // ensure ticking + populate the big clock now
        } else {
            clockOnlyBox.visibility = View.GONE
            clockExit.visibility = View.GONE
            if (!shimmerHidden) {
                shimmer.startSweep()
            }
            clockBox.visibility = if (showClock) View.VISIBLE else View.GONE
            if (running && items.isNotEmpty()) {
                showImmediate(index) // resume photos + auto-advance
            }
        }
    }

    /** Swap the photo source at runtime (e.g. bundled samples -> album). */
    fun setItems(newItems: List<Slide>?) {
        if (newItems == null || newItems.isEmpty()) {
            return
        }
        items = ArrayList(newItems)
        if (shuffle) {
            smartShuffle(items)
        }
        if (onThisDay) {
            promoteOnThisDay(items)
        }
        remote = true
        if (clockOnly) {
            return // keep showing the clock; the new photos display when light returns
        }
        handler.removeCallbacks(autoTick)
        registerBatteryReceiver()
        startClock()
        if (showClock) {
            startWeather()
        }
        if (!shimmerHidden) {
            shimmer.startSweep()
        }
        front.animate().cancel()
        front.alpha = 0f
        index = 0
        running = true
        Log.i(TAG, "Source switched to " + items.size + " album photos")
        showImmediate(0)
    }

    fun showNext() {
        if (items.isNotEmpty()) {
            transitionTo(nextStart(index, curIsPair), transitionDurationMs)
        }
    }

    fun showPrevious() {
        if (items.isNotEmpty()) {
            transitionTo((index - 1 + items.size) % items.size, transitionDurationMs)
        }
    }

    private fun scheduleAuto() {
        handler.removeCallbacks(autoTick)
        if (running && !slideshowPaused && items.size > 1) {
            handler.postDelayed(autoTick, intervalMs)
        }
    }

    private val autoTick = Runnable {
        if (running && items.isNotEmpty()) {
            val step = if (curIsPair) 2 else 1
            val next: Int
            if (index + step >= items.size) {
                // Wrapped a full loop: reshuffle so the next loop differs and
                // doesn't open with a photo we just showed.
                if (shuffle && items.size > 2) {
                    smartShuffle(items)
                }
                next = 0
            } else {
                next = index + step
            }
            transitionTo(next, transitionDurationMs)
        }
    }

    /** Show item i directly (no crossfade) — used for the first frame. */
    private fun showImmediate(i: Int) {
        val gen = ++animGen
        val j = pairWith(i)
        val isPair = j >= 0
        val cb = ImageLoader.Callback { b ->
            if (gen != animGen) {
                return@Callback
            }
            if (b != null) {
                back.setImageBitmap(b)
                front.setImageDrawable(null)
                front.alpha = 0f
                index = i
                curIsPair = isPair
                status.text = ""
                info.text = captionOf(i)
                noteShown(i)
                if (isPair) {
                    noteShown(j)
                }
                hideShimmer()
                kbPath = newKenBurnsPath(b)
                applyKenBurnsStart(back, kbPath)
                startKenBurnsOnBack(gen)
                updateAmbient(b)
                enhanceFilter = if (enhance) makeEnhance(b) else null
                back.colorFilter = enhanceFilter
            }
            prefetchNext(nextStart(i, isPair))
            scheduleAuto()
        }
        if (isPair) {
            loader.loadPair(items[i].id, items[j].id, reqW, reqH, screenPortrait, cb)
        } else {
            loader.load(items[i].id, reqW, reqH, zoomFill, cb)
        }
    }

    /** Transition to start item [next]; loads async, safe to call mid-animation. */
    private fun transitionTo(next: Int, durationMs: Long) {
        if (items.isEmpty()) {
            return
        }
        handler.removeCallbacks(autoTick)
        val gen = ++animGen
        val j = pairWith(next)
        val isPair = j >= 0
        val cb = ImageLoader.Callback { bmp ->
            if (gen != animGen) {
                return@Callback // superseded by a newer request
            }
            if (bmp == null) {
                index = next
                curIsPair = isPair
                scheduleAuto()
                return@Callback
            }
            front.animate().cancel()
            back.animate().cancel()
            front.setImageBitmap(bmp)
            index = next
            curIsPair = isPair
            status.text = ""
            info.text = captionOf(next)
            noteShown(next)
            if (isPair) {
                noteShown(j)
            }
            hideShimmer()
            // Incoming image shows the path's START transform during the fade; when it
            // settles onto `back` we hand off at the same transform and animate to the end.
            kbPath = newKenBurnsPath(bmp)
            applyKenBurnsStart(front, kbPath)
            enhanceFilter = if (enhance) makeEnhance(bmp) else null
            front.colorFilter = enhanceFilter
            prepareTransitionStart(durationMs)
            runTransition(durationMs) {
                if (gen != animGen) {
                    return@runTransition
                }
                back.setImageBitmap(bmp)
                applyKenBurnsStart(back, kbPath)
                back.colorFilter = enhanceFilter
                back.alpha = 1f
                back.translationX = 0f
                back.translationY = 0f
                back.scaleX = 1f
                back.scaleY = 1f
                front.alpha = 0f
                front.translationX = 0f
                front.translationY = 0f
                front.scaleX = 1f
                front.scaleY = 1f
                applyKenBurnsStart(front, null) // reset incoming view for reuse
                front.colorFilter = null
                startKenBurnsOnBack(gen)
                updateAmbient(bmp)
                prefetchNext(nextStart(next, isPair))
                scheduleAuto()
            }
        }
        if (isPair) {
            loader.loadPair(items[next].id, items[j].id, reqW, reqH, screenPortrait, cb)
        } else {
            loader.load(items[next].id, reqW, reqH, zoomFill, cb)
        }
    }

    private fun prepareTransitionStart(durationMs: Long) {
        front.alpha = if (transitionMode == TRANSITION_INSTANT || transitionMode == TRANSITION_ZOOM) 1f else 0f
        front.translationX = 0f
        front.translationY = 0f
        front.scaleX = 1f
        front.scaleY = 1f
        back.alpha = 1f
        back.translationX = 0f
        back.translationY = 0f
        when (transitionMode) {
            TRANSITION_SLIDE, TRANSITION_PUSH -> front.translationX = reqW.toFloat()
            TRANSITION_ZOOM, TRANSITION_ZOOM_FADE -> {
                front.scaleX = ZOOM_START_SCALE
                front.scaleY = ZOOM_START_SCALE
            }
        }
        if (durationMs <= 0L) {
            front.alpha = 1f
            front.translationX = 0f
            front.scaleX = 1f
            front.scaleY = 1f
        }
    }

    private fun runTransition(durationMs: Long, onEnd: () -> Unit) {
        if (transitionMode == TRANSITION_INSTANT || durationMs <= 0L) {
            onEnd()
            return
        }
        val anim = front.animate().setDuration(durationMs)
        when (transitionMode) {
            TRANSITION_SLIDE -> anim.alpha(1f).translationX(0f)
            TRANSITION_PUSH -> {
                back.animate().translationX(-reqW.toFloat()).setDuration(durationMs)
                anim.alpha(1f).translationX(0f)
            }
            TRANSITION_ZOOM -> anim.alpha(1f).scaleX(1f).scaleY(1f)
            TRANSITION_ZOOM_FADE -> anim.alpha(1f).scaleX(1f).scaleY(1f)
            else -> anim.alpha(1f)
        }
        anim.withEndAction(onEnd)
    }

    /**
     * Index this slide pairs with, or -1 if it doesn't pair. We pair two consecutive photos whose
     * orientation is OPPOSITE the screen's, so together they fill it: portrait photos side-by-side
     * on a landscape screen, landscape photos stacked top/bottom on a vertical screen. A photo that
     * matches the screen orientation already fills it, so it's shown alone (full-screen).
     */
    private fun pairWith(start: Int): Int {
        if (!pairs || items.size < 2 || start < 0 || start >= items.size) {
            return -1
        }
        if (items[start].portrait == screenPortrait) {
            return -1 // already fills the screen on its own
        }
        val j = start + 1
        if (j >= items.size || items[j].portrait == screenPortrait) {
            return -1 // next can't pair (fills screen alone, or loop wrap)
        }
        return j
    }

    /** The start index after this one, stepping over a pair, wrapping to 0. */
    private fun nextStart(start: Int, isPair: Boolean): Int {
        val n = start + if (isPair) 2 else 1
        return if (n >= items.size) 0 else n
    }

    private fun prefetchNext(startIndex: Int) {
        if (items.size > 1 && startIndex >= 0 && startIndex < items.size) {
            loader.prefetch(items[startIndex].id, reqW, reqH, zoomFill)
        }
    }

    // ---------------------------------------------------------------- ambient color

    /** Tint the edge vignette to the photo's mood color (no-op when disabled). */
    private fun updateAmbient(bmp: Bitmap?) {
        if (!ambientColor || bmp == null) {
            return
        }
        val c = AmbientColor.extract(bmp)
        if (c == null) {
            ambientGlow.animate().alpha(0f).setDuration(600)
            return
        }
        val edge = (c and 0x00FFFFFF) or 0x70000000.toInt() // ~44% alpha at the edges
        val g = GradientDrawable()
        g.gradientType = GradientDrawable.RADIAL_GRADIENT
        g.gradientRadius = max(reqW, reqH) * 0.62f
        g.setGradientCenter(0.5f, 0.5f)
        g.colors = intArrayOf(0x00000000, 0x00000000, edge) // clear core -> color rim
        ambientGlow.background = g
        ambientGlow.animate().alpha(1f).setDuration(600)
    }

    // ---------------------------------------------------------------- auto-enhance

    /** Per-photo auto-levels + vibrance color filter (null when off / not needed). */
    private fun makeEnhance(bmp: Bitmap?): ColorMatrixColorFilter? {
        val cm = PhotoEnhance.compute(bmp) ?: return null
        return ColorMatrixColorFilter(cm)
    }

    // ---------------------------------------------------------------- Ken Burns

    /** Put a view at the start of the current path (or reset to identity if off). */
    private fun applyKenBurnsStart(v: View, p: KenBurns?) {
        if (p == null) {
            v.scaleX = 1f
            v.scaleY = 1f
            v.translationX = 0f
            v.translationY = 0f
        } else {
            p.applyAt(v, 0f)
        }
    }

    /** Animate the settled image ([back]) along the path over the hold time. */
    private fun startKenBurnsOnBack(gen: Long) {
        kbAnim?.let {
            it.cancel()
            kbAnim = null
        }
        val p = kbPath ?: return
        val a = ValueAnimator.ofFloat(0f, 1f)
        // Run the pan/zoom over the hold (+ the outgoing fade), but cap it: with long "time per
        // photo" values (up to a day) an uncapped animator would run a multi-hour ValueAnimator at
        // ~60fps. Past the cap the motion has finished and the image simply holds at its end frame.
        a.duration = min(max(intervalMs, 1200L) + transitionDurationMs, KEN_BURNS_MAX_MS)
        a.interpolator = LinearInterpolator()
        a.addUpdateListener { va ->
            if (gen != animGen) {
                va.cancel()
                return@addUpdateListener
            }
            p.applyAt(back, va.animatedValue as Float)
        }
        kbAnim = a
        a.start()
    }

    /** Build the path for the slide just loaded (face-biased when available). */
    private fun newKenBurnsPath(bmp: Bitmap?): KenBurns? {
        if (!kenBurns) {
            return null
        }
        val focus = if (faceFraming && bmp != null) FaceFocus.find(bmp) else null
        return KenBurns.random(reqW, reqH, rnd, focus)
    }

    /**
     * A slow zoom-in + gentle pan applied to the settled image while it's held — the cinematic
     * "Ken Burns" motion. Each photo STARTS at exactly minimum fill (scale 1.0, centred) so it's
     * first shown zoomed the minimum amount, then eases gently inward. The pan starts at zero and
     * grows with the scale, so the image always covers the view (no edge reveal). When a focal
     * point is given (e.g. a detected face), the drift eases toward it.
     */
    private class KenBurns private constructor(
        val s0: Float,
        val s1: Float,
        val tx0: Float,
        val tx1: Float,
        val ty0: Float,
        val ty1: Float,
    ) {

        fun applyAt(v: View, f: Float) {
            val s = s0 + (s1 - s0) * f
            v.scaleX = s
            v.scaleY = s
            v.translationX = tx0 + (tx1 - tx0) * f
            v.translationY = ty0 + (ty1 - ty0) * f
        }

        companion object {
            // The path always starts at 1.0 (exact minimum fill) and zooms gently toward a target
            // in this range — so a photo is first shown zoomed the minimum amount, not pre-zoomed.
            private const val END_ZOOM_MIN = 1.04f
            private const val END_ZOOM_MAX = 1.10f

            /**
             * @param focus optional FaceFocusResult to drift toward and zoom out for; null = random.
             */
            fun random(w: Int, h: Int, r: Random, focus: FaceFocusResult?): KenBurns {
                // Start at exact fill (scale 1.0, centred) and zoom IN to a gentle target. Pan
                // starts at zero (1.0 has no cover slack) and grows linearly with the scale toward
                // the end scale's slack — edge-safe at every point along the path.
                var s1 = END_ZOOM_MIN + r.nextFloat() * (END_ZOOM_MAX - END_ZOOM_MIN)
                if (focus != null) {
                    // Zoom out a bit depending on the zoomFactor (fewer zoom-in scale = "zooming out")
                    s1 = 1.0f + (s1 - 1f) * focus.zoomFactor
                }

                val slackX = (s1 - 1f) / 2f * w * 0.9f // 90% of the end scale's cover slack
                val slackY = (s1 - 1f) / 2f * h * 0.9f
                val tx1: Float
                val ty1: Float
                if (focus != null) {
                    // Drift toward the focal point (e.g. centroid of detected faces) as it zooms in.
                    tx1 = clamp((0.5f - focus.fx) * 2f, -1f, 1f) * slackX
                    ty1 = clamp((0.5f - focus.fy) * 2f, -1f, 1f) * slackY
                } else {
                    tx1 = (r.nextFloat() * 2f - 1f) * slackX
                    ty1 = (r.nextFloat() * 2f - 1f) * slackY
                }
                return KenBurns(1f, s1, 0f, tx1, 0f, ty1)
            }

            private fun clamp(v: Float, lo: Float, hi: Float): Float =
                if (v < lo) lo else if (v > hi) hi else v
        }
    }

    private fun captionOf(i: Int): String {
        val s = items[i]
        if (s.caption != null) {
            return s.caption // explicit override (e.g. an "On this day" badge)
        }
        return if (s.timeMs == Slide.NO_DATE) "" else relativeTime(s.timeMs)
    }

    /** "Today" / "Yesterday" / "N days|weeks|months ago", or "MMM yyyy" past a year. */
    private fun relativeTime(timeMs: Long): String {
        val now = System.currentTimeMillis()
        val todayDays = (now + TimeZone.getDefault().getOffset(now)) / 86400000L
        val days = todayDays - timeMs / 86400000L // timeMs is already a UTC wall clock
        if (days <= 0) {
            return "Today"
        }
        if (days == 1L) {
            return "Yesterday"
        }
        if (days < 7) {
            return "$days days ago"
        }
        if (days < 45) {
            val w = Math.round(days / 7.0)
            return if (w <= 1) "1 week ago" else "$w weeks ago"
        }
        if (days < 365) {
            val m = Math.round(days / 30.0)
            return if (m <= 1) "1 month ago" else "$m months ago"
        }
        return monthYearFmt.format(Date(timeMs))
    }

    // ---------------------------------------------------------------- smart shuffle

    /** Remember a shown id so a reshuffle de-weights it (most-recent first). */
    private fun noteShown(i: Int) {
        if (items.isEmpty()) {
            return
        }
        val id = items[i].id
        lastShownId = id
        recentIds.remove(id)
        recentIds.addFirst(id)
        val cap = max(1, items.size / 3)
        while (recentIds.size > cap) {
            recentIds.removeLast()
        }
    }

    /**
     * Shuffle, then push recently shown photos toward the back (stable sort keeps
     * the shuffled order within each group) and make sure we don't open with the
     * photo just shown — so it never feels like "didn't I just see that?".
     */
    private fun smartShuffle(list: MutableList<Slide>) {
        Collections.shuffle(list)
        if (recentIds.isEmpty() && lastShownId == null) {
            return
        }
        val recent: Set<String> = HashSet(recentIds)
        Collections.sort(list) { a, b ->
            Integer.compare(
                if (recent.contains(a.id)) 1 else 0,
                if (recent.contains(b.id)) 1 else 0,
            )
        }
        if (list.size > 1 && lastShownId != null && list[0].id == lastShownId) {
            var swap = 1
            for (i in 1 until list.size) {
                if (!recent.contains(list[i].id)) {
                    swap = i
                    break
                }
            }
            val tmp = list[0]
            list[0] = list[swap]
            list[swap] = tmp
        }
    }

    // ---------------------------------------------------------------- On this day

    /**
     * Move photos taken on today's month+day in a past year to the front (most
     * recent memory first), re-captioned "N years ago today ✨". No-op for items
     * without a capture date (bundled samples).
     */
    private fun promoteOnThisDay(list: MutableList<Slide>) {
        val now = Calendar.getInstance()
        val curMonth = now.get(Calendar.MONTH)
        val curDay = now.get(Calendar.DAY_OF_MONTH)
        val curYear = now.get(Calendar.YEAR)

        // timeMs is the capture wall-clock expressed in UTC, so read it back in UTC.
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val memories = ArrayList<Slide>()
        val it = list.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (s.timeMs == Slide.NO_DATE) {
                continue
            }
            c.timeInMillis = s.timeMs
            if (c.get(Calendar.MONTH) != curMonth || c.get(Calendar.DAY_OF_MONTH) != curDay) {
                continue
            }
            val yearsAgo = curYear - c.get(Calendar.YEAR)
            if (yearsAgo < 1) {
                continue // taken today this year, not a memory
            }
            val badge = if (yearsAgo == 1) {
                "1 year ago today ✨"
            } else {
                "$yearsAgo years ago today ✨"
            }
            memories.add(Slide(s.id, badge, s.timeMs, s.portrait))
            it.remove()
        }
        if (memories.isEmpty()) {
            return
        }
        Collections.sort(memories) { a, b ->
            java.lang.Long.compare(b.timeMs, a.timeMs) // most recent first
        }
        list.addAll(0, memories)
        Log.i(TAG, "On this day: promoted " + memories.size + " memory photo(s)")
    }

    // ---------------------------------------------------------------- clock + date

    private val clockTick = object : Runnable {
        override fun run() {
            // Briefly pause Ken Burns to smooth the clock update visual
            val wasAnimating = kbAnim?.isRunning ?: false
            kbAnim?.pause()
            updateClock()
            if (wasAnimating) {
                kbAnim?.resume()
            }
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000)
        }
    }

    private fun startClock() {
        handler.removeCallbacks(clockTick)
        updateClock()
        handler.postDelayed(clockTick, 60000 - System.currentTimeMillis() % 60000)
    }

    private fun getBatterySuffix(): String {
        val showBattery = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ConfigReceiver.KEY_BATTERY, ConfigReceiver.DEFAULT_BATTERY)
        if (!showBattery || batteryLevel < 0) return ""
        val icon = if (batteryIsCharging) "⚡" else "🔋"
        return "  ·  $batteryLevel% $icon"
    }

    private fun updateClock() {
        val c = Calendar.getInstance()
        nightTint.alpha = if (nightMode) {
            warmthForHour(c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f)
        } else {
            0f
        }
        val hour = c.get(Calendar.HOUR_OF_DAY)
        val minute = c.get(Calendar.MINUTE)
        if (minute == 0) {
            if (lastChimedHour != hour) {
                triggerHourlyChime(c)
                lastChimedHour = hour
            }
        }
        val time = timeFmt.format(c.time)
        val date = dateFmt.format(c.time)
        // Centered low-light clock (no weather) — kept current even when the overlay is off.
        bigClock.setTime(
            c.get(Calendar.HOUR_OF_DAY),
            c.get(Calendar.MINUTE),
            use24Hour,
        )
        bigDate.text = buildBigDateLine(c, weather)
        if (!showClock) {
            return // night tint still updates above; the overlay clock/weather text is off
        }

        clock.text = time
        trimLeftBearing(clock)

        val w = weather
        if (w == null) {
            dateLine.text = date + getBatterySuffix()
        } else if (w.moon) {
            // Clear night: draw a blue crescent (color emoji can't be tinted) + temp.
            val sb = SpannableStringBuilder("$date   ")
            val s = sb.length
            sb.append(" ")
            sb.setSpan(
                ImageSpan(moonDrawable, ImageSpan.ALIGN_CENTER),
                s, s + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            sb.append("  ").append(w.temp.toString()).append("°")
            sb.append(getBatterySuffix())
            dateLine.text = sb
        } else {
            dateLine.text = date + "   " + w.label() + getBatterySuffix()
        }
        trimLeftBearing(dateLine)
    }

    private fun buildBigDateLine(c: Calendar, w: Weather.Now?): String {
        val date = bigDateFmt.format(c.time)
        val base = if (w == null) date else "$date  ${w.temp}°  ${w.summary()}"
        return base + getBatterySuffix()
    }

    /**
     * Remove a TextView's first-glyph left side-bearing by setting a matching negative left padding,
     * so the visible text starts flush at the view's left edge. Applied to the clock and the date
     * line (which use different fonts) so their left edges line up exactly.
     */
    private fun trimLeftBearing(tv: TextView) {
        val s = tv.text?.toString() ?: return
        if (s.isEmpty()) return
        val r = Rect()
        tv.paint.getTextBounds(s, 0, 1, r)
        tv.setPadding(-r.left, 0, 0, 0)
    }

    private fun registerBatteryReceiver() {
        val showBattery = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
            .getBoolean(ConfigReceiver.KEY_BATTERY, ConfigReceiver.DEFAULT_BATTERY)
        if (showBattery && !batteryReceiverRegistered) {
            try {
                val stickyIntent = context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (stickyIntent != null) {
                    val level = stickyIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = stickyIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    val status = stickyIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    batteryIsCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                        status == BatteryManager.BATTERY_STATUS_FULL
                }
                batteryReceiverRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register battery receiver", e)
            }
        }
    }

    private fun triggerHourlyChime(c: Calendar) {
        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(ConfigReceiver.KEY_CHIME, ConfigReceiver.DEFAULT_CHIME)
        if (!enabled) return

        val startMin = prefs.getInt(ConfigReceiver.KEY_CHIME_START_MIN, ConfigReceiver.DEFAULT_CHIME_START_MIN)
        val endMin = prefs.getInt(ConfigReceiver.KEY_CHIME_END_MIN, ConfigReceiver.DEFAULT_CHIME_END_MIN)

        val currentMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)

        if (!isMinuteInRangeChime(currentMin, startMin, endMin)) return

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        if (am.mode == AudioManager.MODE_IN_CALL ||
            am.mode == AudioManager.MODE_IN_COMMUNICATION ||
            am.mode == AudioManager.MODE_RINGTONE) {
            return
        }

        playChimeSound()
    }

    private fun isMinuteInRangeChime(minute: Int, start: Int, end: Int): Boolean {
        if (start == end) return true
        return if (start < end) {
            minute in start..end
        } else {
            minute >= start || minute <= end
        }
    }

    private fun playChimeSound() {
        val sampleRate = 44100
        val duration = 1.5 // seconds
        val numSamples = (duration * sampleRate).toInt()
        val sample = DoubleArray(numSamples)
        val buffer = ShortArray(numSamples)

        val freq1 = 880.0
        val freq2 = 1320.0
        val freq3 = 1760.0

        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val decay = Math.exp(-3.0 * t)
            val value = (0.6 * Math.sin(2.0 * Math.PI * freq1 * t) +
                         0.25 * Math.sin(2.0 * Math.PI * freq2 * t) +
                         0.15 * Math.sin(2.0 * Math.PI * freq3 * t)) * decay
            buffer[i] = (value * Short.MAX_VALUE).toInt().toShort()
        }

        try {
            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setBufferSizeInBytes(numSamples * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(buffer, 0, numSamples)
            audioTrack.play()

            handler.postDelayed({
                try {
                    audioTrack.stop()
                    audioTrack.release()
                } catch (e: Exception) {
                    // ignore
                }
            }, (duration * 1000).toLong() + 500)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play synthesized chime", e)
        }
    }

    // ---------------------------------------------------------------- weather

    private val weatherTick = object : Runnable {
        override fun run() {
            refreshWeather()
            handler.postDelayed(this, WEATHER_INTERVAL_MS)
        }
    }

    private fun startWeather() {
        handler.removeCallbacks(weatherTick)
        // Fetch immediately if we have nothing yet; otherwise keep the periodic cadence.
        handler.postDelayed(weatherTick, if (weather == null) 0 else WEATHER_INTERVAL_MS)
    }

    private fun refreshWeather() {
        loader.executor().execute {
            val now = Weather.fetch(fahrenheit) ?: return@execute
            handler.post {
                weather = now
                updateClock()
            }
        }
    }

    // ---------------------------------------------------------------- loading shimmer

    private fun hideShimmer() {
        if (shimmerHidden) {
            return
        }
        shimmerHidden = true
        shimmer.animate().alpha(0f).setDuration(400).withEndAction {
            shimmer.stopSweep()
            shimmer.visibility = View.GONE
        }
    }

    /** A soft diagonal light band sweeping across a dark surface — a calm "loading". */
    private class ShimmerView(c: Context) : View(c) {
        private val paint = Paint()
        private val anim: ValueAnimator
        private var pos = -0.3f // sweep centre as a fraction of width

        init {
            setBackgroundColor(0xFF1A1A1A.toInt())
            anim = ValueAnimator.ofFloat(-0.3f, 1.3f)
            anim.duration = 1600
            anim.repeatCount = ValueAnimator.INFINITE
            anim.interpolator = LinearInterpolator()
            anim.addUpdateListener { a ->
                pos = a.animatedValue as Float
                invalidate()
            }
        }

        fun startSweep() {
            if (!anim.isStarted) {
                anim.start()
            }
        }

        fun stopSweep() {
            anim.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width
            val h = height
            if (w == 0 || h == 0) {
                return
            }
            val band = w * 0.35f
            val cx = pos * w
            paint.shader = LinearGradient(
                cx - band, 0f, cx + band, 0f,
                intArrayOf(0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        }
    }

    private class FlipClockView(
        c: Context,
        digitTypeface: android.graphics.Typeface,
        labelTypeface: android.graphics.Typeface,
    ) : View(c) {
        private val minCardW = Ui.dp(c, 126f).toFloat()
        private val minCardH = Ui.dp(c, 146f).toFloat()
        private val minGroupGap = Ui.dp(c, 40f).toFloat()
        private val minLabelGap = Ui.dp(c, 34f).toFloat()
        private val groupBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F1419.toInt() }
        private val groupStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x1CFFFFFF
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(c, 1.5f).toFloat()
        }
        private val cardBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1E27.toInt() }
        private val cardTop = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF252A34.toInt() }
        private val cardBottom = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A1E27.toInt() }
        private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x2CFFFFFF
            style = Paint.Style.STROKE
            strokeWidth = Ui.dp(c, 1.2f).toFloat()
        }
        private val splitLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x3CFFFFFF
            strokeWidth = Ui.dp(c, 2f).toFloat()
        }
        private val flapShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x4A000000 }
        private val digitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFAFBFC.toInt()
            textAlign = Paint.Align.CENTER
            typeface = digitTypeface
            setShadowLayer(14f, 0f, 4f, 0xFF000000.toInt())
        }
        private val ampmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF9AA0AE.toInt()
            textAlign = Paint.Align.CENTER
            typeface = labelTypeface
            textSize = Ui.dp(c, 18f).toFloat()
        }
        private val currentDigits = charArrayOf('0', '0', '0', '0')
        private val oldDigits = charArrayOf('0', '0', '0', '0')
        private val nextDigits = charArrayOf('0', '0', '0', '0')
        private val progress = FloatArray(4)
        private val animators = arrayOfNulls<ValueAnimator>(4)
        private var lastRendered = ""
        private var ampm = ""
        private var showAmPm = true
        private var cardRadius = Ui.dp(c, 15f).toFloat()
        private var groupRadius = Ui.dp(c, 26f).toFloat()
        private var cardGap = Ui.dp(c, 14f).toFloat()
        private var groupGap = Ui.dp(c, 56f).toFloat()
        private var groupPadH = Ui.dp(c, 24f).toFloat()
        private var groupPadV = Ui.dp(c, 22f).toFloat()
        private var cardW = minCardW
        private var cardH = minCardH
        private var panelW = 0f
        private var panelH = 0f
        private var labelGap = minLabelGap

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        fun setTime(hour24: Int, minute: Int, use24Hour: Boolean) {
            showAmPm = !use24Hour
            ampm = if (use24Hour) "" else if (hour24 < 12) "AM" else "PM"
            val hour = if (use24Hour) hour24 else ((hour24 + 11) % 12) + 1
            val digits = String.format(Locale.US, "%02d%02d", hour, minute)
            if (lastRendered.isEmpty()) {
                for (i in currentDigits.indices) {
                    currentDigits[i] = digits[i]
                    oldDigits[i] = digits[i]
                    nextDigits[i] = digits[i]
                    progress[i] = 1f
                }
                lastRendered = digits
                invalidate()
                return
            }
            if (digits == lastRendered) return
            for (i in currentDigits.indices) {
                val next = digits[i]
                if (currentDigits[i] != next) {
                    animators[i]?.cancel()
                    oldDigits[i] = currentDigits[i]
                    nextDigits[i] = next
                    progress[i] = 0f
                    animators[i] = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 380L  // smooth flip animation
                        interpolator = LinearInterpolator()
                        addUpdateListener {
                            progress[i] = it.animatedValue as Float
                            invalidate()
                        }
                        start()
                    }
                } else {
                    oldDigits[i] = currentDigits[i]
                    nextDigits[i] = currentDigits[i]
                    progress[i] = 1f
                }
            }
            lastRendered = digits
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            for (i in animators.indices) {
                animators[i]?.cancel()
                animators[i] = null
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthMode = MeasureSpec.getMode(widthMeasureSpec)
            val widthSize = MeasureSpec.getSize(widthMeasureSpec)
            val heightMode = MeasureSpec.getMode(heightMeasureSpec)
            val heightSize = MeasureSpec.getSize(heightMeasureSpec)

            val availW = when (widthMode) {
                MeasureSpec.UNSPECIFIED -> (minCardW * 4f + minGroupGap + Ui.dp(context, 96f)).toInt()
                else -> widthSize
            }.toFloat()
            val availH = when (heightMode) {
                MeasureSpec.UNSPECIFIED -> (availW * 0.46f).toInt()
                else -> heightSize
            }.toFloat()

            updateGeometry(availW, availH)
            val wantedH = (panelH + if (showAmPm) labelGap + ampmPaint.textSize else 0f).toInt()
            val finalH = when (heightMode) {
                MeasureSpec.EXACTLY -> heightSize
                MeasureSpec.AT_MOST -> minOf(wantedH, heightSize)
                else -> wantedH
            }
            setMeasuredDimension(availW.toInt(), finalH)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            updateGeometry(width.toFloat(), height.toFloat())
            val contentW = panelW * 2f + groupGap
            val contentH = panelH + if (showAmPm && ampm.isNotEmpty()) labelGap + ampmPaint.textSize else 0f
            val left = (width - contentW) / 2f
            val top = (height - contentH) / 2f
            val hourGroup = RectF(left, top, left + panelW, top + panelH)
            val minuteGroup = RectF(
                hourGroup.right + groupGap,
                top,
                hourGroup.right + groupGap + panelW,
                top + panelH,
            )
            drawGroup(canvas, hourGroup, 0)
            drawGroup(canvas, minuteGroup, 2)
            if (showAmPm && ampm.isNotEmpty()) {
                val baseY = hourGroup.bottom + labelGap
                canvas.drawText(ampm, minuteGroup.centerX(), baseY, ampmPaint)
            }
        }

        private fun updateGeometry(availW: Float, availH: Float) {
            val contentW = (availW - paddingLeft - paddingRight).coerceAtLeast(minCardW * 4f)
            val contentH = (availH - paddingTop - paddingBottom).coerceAtLeast(minCardH + minLabelGap)
            val usableW = contentW * 0.92f
            val usableH = if (showAmPm) contentH * 0.78f else contentH * 0.86f
            val widthScale = usableW / (minCardW * 4f + minGroupGap + Ui.dp(context, 96f))
            val heightScale = usableH / (minCardH + Ui.dp(context, 44f))
            val scale = max(1f, min(widthScale, heightScale))

            cardW = minCardW * scale
            cardH = minCardH * scale
            cardGap = Ui.dp(context, 14f) * scale
            groupGap = max(minGroupGap, Ui.dp(context, 56f) * scale)
            groupPadH = Ui.dp(context, 24f) * scale
            groupPadV = Ui.dp(context, 22f) * scale
            cardRadius = Ui.dp(context, 15f) * scale
            groupRadius = Ui.dp(context, 26f) * scale
            labelGap = max(minLabelGap, Ui.dp(context, 24f) * scale)
            panelW = groupPadH * 2f + cardW * 2f + cardGap
            panelH = groupPadV * 2f + cardH
            digitPaint.textSize = cardH * 0.7f
            ampmPaint.textSize = max(Ui.dp(context, 18f).toFloat(), Ui.dp(context, 18f) * scale * 0.9f)
        }

        private fun drawGroup(canvas: Canvas, group: RectF, startIdx: Int) {
            canvas.drawRoundRect(group, groupRadius, groupRadius, groupBg)
            canvas.drawRoundRect(group, groupRadius, groupRadius, groupStroke)
            val cardTopY = group.top + groupPadV
            val first = RectF(
                group.left + groupPadH,
                cardTopY,
                group.left + groupPadH + cardW,
                cardTopY + cardH,
            )
            val second = RectF(
                first.right + cardGap,
                cardTopY,
                first.right + cardGap + cardW,
                cardTopY + cardH,
            )
            drawCard(canvas, first, startIdx)
            drawCard(canvas, second, startIdx + 1)
        }

        private fun drawCard(canvas: Canvas, rect: RectF, idx: Int) {
            val mid = rect.centerY()
            canvas.drawRoundRect(rect, cardRadius, cardRadius, cardBg)
            val topRect = RectF(rect.left, rect.top, rect.right, mid)
            val bottomRect = RectF(rect.left, mid, rect.right, rect.bottom)
            canvas.drawRoundRect(topRect, cardRadius, cardRadius, cardTop)
            canvas.drawRoundRect(bottomRect, cardRadius, cardRadius, cardBottom)
            canvas.drawRoundRect(rect, cardRadius, cardRadius, cardStroke)
            canvas.drawLine(rect.left, mid, rect.right, mid, splitLine)

            val p = progress[idx]
            if (p >= 1f) {
                currentDigits[idx] = nextDigits[idx]
                drawDigitHalf(canvas, rect, currentDigits[idx], true)
                drawDigitHalf(canvas, rect, currentDigits[idx], false)
                return
            }

            val oldChar = oldDigits[idx]
            val newChar = nextDigits[idx]
            if (p < 0.5f) {
                drawDigitHalf(canvas, rect, oldChar, true)
                drawDigitHalf(canvas, rect, oldChar, false)
                val flap = 1f - (p / 0.5f)
                drawAnimatedHalf(canvas, rect, oldChar, true, flap)
            } else {
                drawDigitHalf(canvas, rect, newChar, true)
                drawDigitHalf(canvas, rect, oldChar, false)
                val flap = (p - 0.5f) / 0.5f
                drawAnimatedHalf(canvas, rect, newChar, false, flap)
            }
        }

        private fun drawAnimatedHalf(canvas: Canvas, rect: RectF, ch: Char, topHalf: Boolean, scaleY: Float) {
            val mid = rect.centerY()
            val half = if (topHalf) RectF(rect.left, rect.top, rect.right, mid) else RectF(rect.left, mid, rect.right, rect.bottom)
            canvas.save()
            canvas.clipRect(half)
            canvas.scale(1f, scaleY.coerceAtLeast(0.02f), rect.centerX(), mid)
            drawDigitHalf(canvas, rect, ch, topHalf)
            canvas.drawRect(half, flapShade)
            canvas.restore()
        }

        private fun drawDigitHalf(canvas: Canvas, rect: RectF, ch: Char, topHalf: Boolean) {
            val mid = rect.centerY()
            canvas.save()
            if (topHalf) {
                canvas.clipRect(rect.left, rect.top, rect.right, mid)
            } else {
                canvas.clipRect(rect.left, mid, rect.right, rect.bottom)
            }
            val fm = digitPaint.fontMetrics
            val baseline = rect.centerY() - (fm.ascent + fm.descent) / 2f
            canvas.drawText(ch.toString(), rect.centerX(), baseline, digitPaint)
            canvas.restore()
        }
    }

    private fun assetItems(): MutableList<Slide> {
        val names = ArrayList<String>()
        try {
            val am = context.assets
            val list = am.list(SLIDES_DIR)
            if (list != null) {
                for (n in list) {
                    val lower = n.lowercase()
                    if (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                        lower.endsWith(".jpeg") || lower.endsWith(".webp")
                    ) {
                        names.add("$SLIDES_DIR/$n")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to list slides", e)
        }
        Collections.sort(names)
        val found = ArrayList<Slide>()
        for (n in names) {
            found.add(Slide(n, null))
        }
        return found
    }

    companion object {
        private const val TAG = "PortalFrame"
        private const val SLIDES_DIR = "slides"
        // Cap the Ken Burns animation length so long "time per photo" values (up to a day) don't
        // run a multi-hour ValueAnimator; past this the motion holds at its end frame.
        private const val KEN_BURNS_MAX_MS = 30_000L
        private const val CLOCK_SCALE_MIN = 0.5f // pinch-to-resize bounds for the clock widget
        private const val CLOCK_SCALE_MAX = 3.0f
        private const val SWIPE_FADE_MS = 300L // manual swipe fade
        private const val SWIPE_MIN_DISTANCE = 60f
        private const val TAP_SLOP = 30f
        private const val TAP_TIMEOUT_MS = 350L
        private const val LONG_PRESS_MS = 700L // hold to open Photos setup
        private const val WEATHER_INTERVAL_MS = 30 * 60 * 1000L // refresh weather
        private const val ZOOM_START_SCALE = 1.08f
        private const val TRANSITION_CROSSFADE = "crossfade"
        private const val TRANSITION_SLIDE = "slide"
        private const val TRANSITION_ZOOM = "zoom"
        private const val TRANSITION_ZOOM_FADE = "zoom_fade"
        private const val TRANSITION_INSTANT = "instant"
        private const val TRANSITION_PUSH = "push"

        /**
         * Warm-overlay strength by time of day (Ambient-EQ-lite): none in daylight,
         * easing in 20:00→23:00, full overnight, easing out 06:00→08:00.
         */
        private fun warmthForHour(h: Float): Float {
            val max = 0.14f
            if (h >= 8f && h < 20f) {
                return 0f
            }
            if (h >= 20f && h < 23f) {
                return max * (h - 20f) / 3f
            }
            if (h >= 23f || h < 6f) {
                return max
            }
            return max * (8f - h) / 2f // 06:00–08:00
        }
    }
}
