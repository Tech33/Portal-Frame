package com.portalhacks.frame

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Portal design-system helpers (see Meta's "portal" skill: design-guidelines.md /
 * compose-theme.md), translated for this dependency-free, view-based app.
 *
 * Dark-forced theme (Portal's white system-overlay pills stay legible, matches the
 * native launcher/Settings), Portal platform palette, bundled Inter typography,
 * generous spacing and room-distance hit targets (64dp min, 96dp primary), a
 * centred max-width column, and a reserved top inset for the system overlay strip.
 */
internal object Ui {

    // ---- Palette: Apple iOS 18 System Dark tokens ----
    const val BLUE = 0xFF0A84FF.toInt()          // iOS System Blue
    const val BLUE_PRESSED = 0xFF0056B3.toInt()  // pressed
    const val GREEN = 0xFF30D158.toInt()         // iOS System Green
    const val ORANGE = 0xFFFF9F0A.toInt()        // iOS System Orange
    const val RED = 0xFFFF453A.toInt()           // iOS System Red
    const val INDIGO = 0xFF5E5CE6.toInt()        // iOS System Indigo / Purple
    const val BG = 0xFF000000.toInt()            // iOS Pure Black (OLED contrast)
    const val SURFACE = 0xFF1C1C1E.toInt()       // iOS Elevated Dark background (cards)
    const val SURFACE_PRESSED = 0xFF2C2C2E.toInt()
    const val FIELD = 0xFF2C2C2E.toInt()         // input fields
    const val ON_PRIMARY = 0xFFFFFFFF.toInt()    // white
    const val TEXT = 0xFFFFFFFF.toInt()          // primary headings
    const val TEXT_BODY = 0xFFE5E5EA.toInt()     // body
    const val TEXT_MUTED = 0xFF8E8E93.toInt()    // captions / secondary
    const val HAIRLINE = 0x338E8E93

    // Illustration palette (icons / decorative)
    const val SLATE = 0xFF8E8E93.toInt()
    const val TEAL = 0xFF64D2FF.toInt()
    const val LIME = 0xFF30D158.toInt()
    const val LEMON = 0xFFFFD60A.toInt()
    const val PINK = 0xFFFF375F.toInt()

    const val TOP_INSET_DP = 72    // reserve for Portal's top system overlay
    const val MAX_W_DP = 760       // centred content column
    const val MAX_W_WIDE_DP = 1160 // wide two-column layouts

    fun fontScale(c: Context): Float {
        return try {
            val prefs = c.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
            val s = prefs.getFloat(ConfigReceiver.KEY_FONT_SCALE, ConfigReceiver.DEFAULT_FONT_SCALE)
            if (s > 0.5f) s else 1.0f
        } catch (_: Exception) {
            1.0f
        }
    }

    fun scaledSp(c: Context, baseSp: Float): Float = baseSp * fontScale(c)

    // ---- Inter typefaces (bundled in assets; graceful fallback) ----
    private var sRegular: Typeface? = null
    private var sMedium: Typeface? = null
    private var sBold: Typeface? = null

    fun regular(c: Context): Typeface {
        if (sRegular == null) sRegular = load(c, "fonts/inter_regular.ttf", "sans-serif")
        return sRegular!!
    }

    fun medium(c: Context): Typeface {
        if (sMedium == null) sMedium = load(c, "fonts/inter_medium.ttf", "sans-serif-medium")
        return sMedium!!
    }

    fun bold(c: Context): Typeface {
        if (sBold == null) sBold = load(c, "fonts/inter_bold.ttf", "sans-serif-medium")
        return sBold!!
    }

    private fun load(c: Context, asset: String, fallbackFamily: String): Typeface {
        return try {
            Typeface.createFromAsset(c.assets, asset)
        } catch (e: Exception) {
            Typeface.create(fallbackFamily, Typeface.NORMAL)
        }
    }

    private var sClock: Typeface? = null

    /**
     * The Portal system clock font — Meta's "Optimistic" (Display, Light), loaded
     * straight from /system/fonts (world-readable) so our clock matches the native
     * home/screensaver clock exactly. Falls back to the bundled regular face on
     * non-Portal devices.
     */
    fun clockFace(c: Context): Typeface {
        if (sClock == null) {
            sClock = loadFile("/system/fonts/Optimistic_Display_A_Lt.ttf", c)
        }
        return sClock!!
    }

    private fun loadFile(path: String, c: Context): Typeface {
        try {
            val f = File(path)
            if (f.exists()) {
                return Typeface.createFromFile(f)
            }
        } catch (ignored: Exception) {
        }
        return regular(c)
    }

    fun dp(c: Context, v: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics
        ).roundToInt()
    }

    /** Int convenience (Kotlin won't auto-widen Int→Float at call sites). */
    fun dp(c: Context, v: Int): Int = dp(c, v.toFloat())

    // ---- Text styles (sp sizes per Portal type scale) ----
    fun title(c: Context, t: String): TextView = text(c, t, 30, bold(c), TEXT)
    fun heading(c: Context, t: String): TextView = text(c, t, 24, bold(c), TEXT)
    fun body(c: Context, t: String): TextView {
        val tv = text(c, t, 18, medium(c), TEXT_BODY)
        tv.setLineSpacing(dp(c, 5).toFloat(), 1f)
        return tv
    }

    fun caption(c: Context, t: String): TextView = text(c, t, 16, medium(c), TEXT_MUTED)
    fun sectionLabel(c: Context, t: String): TextView {
        val tv = text(c, t.uppercase(), 14, bold(c), TEXT_MUTED)
        tv.letterSpacing = 0.14f
        return tv
    }

    private fun text(c: Context, t: String, sp: Int, tf: Typeface, color: Int): TextView {
        val tv = TextView(c)
        tv.text = t
        tv.setTextColor(color)
        tv.typeface = tf
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        return tv
    }

    // ---- Buttons ----
    /** Filled Portal-blue primary action (96dp tall). */
    fun primary(c: Context, t: String, onClick: Runnable?): Button {
        return filledButton(c, t, onClick, ON_PRIMARY, BLUE, BLUE_PRESSED, 96)
    }

    /** Neutral surface button (64dp). */
    fun secondary(c: Context, t: String, onClick: Runnable?): Button {
        return filledButton(c, t, onClick, TEXT, SURFACE, SURFACE_PRESSED, 64)
    }

    /** Outlined neutral button (64dp) — secondary actions that sit on a card. */
    fun outline(c: Context, t: String, onClick: Runnable?): Button {
        val b = baseButton(c, t, onClick, TEXT, 64)
        val g = roundRect(0x00000000, dp(c, 16))
        g.setStroke(dp(c, 2), 0x40FFFFFF)
        val p = roundRect(SURFACE_PRESSED, dp(c, 16))
        p.setStroke(dp(c, 2), 0x40FFFFFF)
        b.background = pressable(p, g)
        return b
    }

    /** Outlined red destructive button (64dp). */
    fun destructive(c: Context, t: String, onClick: Runnable?): Button {
        val b = baseButton(c, t, onClick, RED, 64)
        val g = roundRect(0x00000000, dp(c, 16))
        g.setStroke(dp(c, 2), RED)
        val p = roundRect(0x22FA484E, dp(c, 16))
        p.setStroke(dp(c, 2), RED)
        b.background = pressable(p, g)
        return b
    }

    private fun filledButton(
        c: Context, t: String, onClick: Runnable?,
        textColor: Int, fill: Int, pressed: Int, minDp: Int
    ): Button {
        val b = baseButton(c, t, onClick, textColor, minDp)
        b.background = pressable(roundRect(pressed, dp(c, 16)), roundRect(fill, dp(c, 16)))
        return b
    }

    private fun baseButton(
        c: Context, t: String, onClick: Runnable?,
        textColor: Int, minDp: Int
    ): Button {
        val b = Button(c)
        b.text = t
        b.isAllCaps = false
        b.typeface = medium(c)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        b.setTextColor(textColor)
        b.minHeight = dp(c, minDp.toFloat())
        b.minimumHeight = dp(c, minDp.toFloat())
        b.stateListAnimator = null
        b.contentDescription = t
        val padH = dp(c, 24)
        b.setPadding(padH, 0, padH, 0)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(c, 16) // ≥16dp between adjacent hit targets
        b.layoutParams = lp
        if (onClick != null) {
            b.setOnClickListener { onClick.run() }
        }
        return b
    }

    // ---- Containers / fields ----
    /** A rounded surface card (vertical LinearLayout) for grouping content. */
    fun card(c: Context): LinearLayout {
        val ll = LinearLayout(c)
        ll.orientation = LinearLayout.VERTICAL
        ll.background = roundRect(SURFACE, dp(c, 20))
        val pad = dp(c, 28)
        ll.setPadding(pad, dp(c, 22), pad, dp(c, 22))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(c, 16)
        ll.layoutParams = lp
        return ll
    }

    fun field(c: Context, hint: String): EditText {
        val e = EditText(c)
        e.hint = hint
        e.setHintTextColor(TEXT_MUTED)
        e.setTextColor(TEXT)
        e.typeface = regular(c)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        e.background = roundRect(FIELD, dp(c, 14))
        val padH = dp(c, 18)
        val padV = dp(c, 16)
        e.setPadding(padH, padV, padH, padV)
        e.minHeight = dp(c, 64)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(c, 16)
        e.layoutParams = lp
        return e
    }

    /**
     * Build the standard screen scaffold on [root]: dark background, a vertical
     * scroll, a centred max-width column with a reserved top inset and page side
     * margins. Returns the column to add content to.
     */
    fun screen(a: Activity, root: FrameLayout): LinearLayout {
        return screen(a, root, MAX_W_DP)
    }

    fun screen(a: Activity, root: FrameLayout, maxWdp: Int): LinearLayout {
        root.setBackgroundColor(BG)
        val sv = ScrollView(a)
        sv.isFillViewport = true
        sv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )

        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        val side = dp(a, 40)
        col.setPadding(side, dp(a, TOP_INSET_DP.toFloat()), side, dp(a, 40))

        val clp = FrameLayout.LayoutParams(
            min(
                dp(a, maxWdp.toFloat()),
                a.resources.displayMetrics.widthPixels
            ),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        clp.gravity = Gravity.CENTER_HORIZONTAL
        col.layoutParams = clp

        sv.addView(col)
        root.addView(sv)
        return col
    }

    /**
     * Split [parent] into two equal-weight vertical columns with a gap, for
     * side-by-side panes. Returns {left, right}; add cards to either.
     */
    fun twoColumns(c: Context, parent: LinearLayout): Array<LinearLayout> {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        val rlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        rlp.topMargin = dp(c, 8)
        row.layoutParams = rlp

        val left = LinearLayout(c)
        left.orientation = LinearLayout.VERTICAL
        left.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )

        val right = LinearLayout(c)
        right.orientation = LinearLayout.VERTICAL
        val rp = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        rp.leftMargin = dp(c, 24)
        right.layoutParams = rp

        row.addView(left)
        row.addView(right)
        parent.addView(row)
        return arrayOf(left, right)
    }

    /**
     * A settings row inside a [card]: label (left) → value (blue) + chevron,
     * full-width, pressable. The value TextView is stored as the row's tag so it can
     * be updated in place via [setRowValue].
     */
    fun row(c: Context, label: String, value: String, onClick: Runnable?): View {
        val r = LinearLayout(c)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.minimumHeight = dp(c, 64)
        r.setPadding(dp(c, 4), dp(c, 8), dp(c, 4), dp(c, 8))
        r.isClickable = true
        r.background = pressable(
            roundRect(SURFACE_PRESSED, dp(c, 12)), roundRect(0x00000000, dp(c, 12))
        )
        r.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val lab = text(c, label, 18, medium(c), TEXT)
        lab.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )
        r.addView(lab)

        val `val` = text(c, value, 18, medium(c), BLUE)
        r.addView(`val`)

        val chev = text(c, "  ›", 18, medium(c), TEXT_MUTED)
        r.addView(chev)

        r.tag = `val`
        if (onClick != null) {
            r.setOnClickListener { onClick.run() }
        }
        return r
    }

    /** Update the value shown by a [row] in place. */
    fun setRowValue(row: View, value: String) {
        val tag = row.tag
        if (tag is TextView) {
            tag.text = value
        }
    }

    /** A thin divider between rows inside a card. */
    fun hairline(c: Context): View {
        val v = View(c)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, dp(c, 1))
        )
        lp.topMargin = dp(c, 4)
        lp.bottomMargin = dp(c, 4)
        v.layoutParams = lp
        v.setBackgroundColor(HAIRLINE)
        return v
    }

    /** A filled crescent-moon bitmap (for the night weather glyph), [color]-tinted. */
    fun crescent(sizePx: Int, color: Int): Bitmap {
        val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val cv = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = color
        val r = sizePx / 2f
        cv.drawCircle(r, r, r, p)
        // Carve an offset disc to leave a crescent.
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        cv.drawCircle(r + r * 0.52f, r - r * 0.16f, r * 0.95f, p)
        p.xfermode = null
        return b
    }

    // ---- drawable helpers ----
    fun roundRect(color: Int, radiusPx: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radiusPx.toFloat()
        return g
    }

    private fun pressable(pressed: GradientDrawable, normal: GradientDrawable): StateListDrawable {
        val s = StateListDrawable()
        s.addState(intArrayOf(android.R.attr.state_pressed), pressed)
        s.addState(intArrayOf(), normal)
        return s
    }
}
