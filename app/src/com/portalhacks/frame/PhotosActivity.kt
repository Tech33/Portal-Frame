package com.portalhacks.frame

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import java.net.NetworkInterface
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PhotosActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: FrameLayout

    private var server: AlbumServer? = null
    private var scanHint: TextView? = null
    private var showingStatus = false
    private var stopArmed = false
    private var cloudPollRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        root = FrameLayout(this)
        root.setBackgroundColor(Ui.BG)
        setContentView(root)

        val gotoExtra = intent?.getStringExtra("goto")
        when (gotoExtra) {
            "scan" -> startScan()
            "manual" -> startScan() // manual entry now lives inside the QR panel
            "announcement", "message" -> startAnnouncementScan()
            else -> showStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        if (showingStatus) {
            showStatus()
        }
    }

    override fun onPause() {
        super.onPause()
        stopServer()
        cloudPollRunnable?.let { main.removeCallbacks(it) }
        cloudPollRunnable = null
        restoreBrightness()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    // ------------------------------------------------ screensaver setup

    private fun isOurScreensaver(): Boolean {
        return try {
            val enabled = Settings.Secure.getInt(
                contentResolver, "screensaver_enabled", 0,
            ) == 1
            val comp = Settings.Secure.getString(contentResolver, "screensaver_components")
            enabled && comp != null && comp.contains(packageName)
        } catch (e: Exception) {
            false
        }
    }

    private fun openScreensaverSettings() {
        try {
            startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
        } catch (e: Exception) {
            toast("Open Settings → Display → Screen saver, then choose Frame")
        }
    }

    // ------------------------------------------------ prefs

    private fun prefs(): SharedPreferences =
        getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)

    private fun album(): String = Albums.list(prefs()).firstOrNull() ?: ""

    private fun getDelay(): Long =
        prefs().getLong(ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS)

    private fun getFade(): Long =
        prefs().getLong(ConfigReceiver.KEY_FADE_MS, ConfigReceiver.DEFAULT_FADE_MS)

    private fun getShuffle(): Boolean = prefs().getBoolean(ConfigReceiver.KEY_SHUFFLE, false)

    private fun getPairs(): Boolean =
        prefs().getBoolean(ConfigReceiver.KEY_PAIRS, ConfigReceiver.DEFAULT_PAIRS)

    // ------------------------------------------------------- Status / settings

    private fun showStatus() {
        stopServer()
        restoreBrightness()
        stopArmed = false
        showingStatus = true
        root.removeAllViews()
        val col = Ui.screen(this, root, Ui.MAX_W_WIDE_DP)

        val url = album()
        val hasAlbum = url.isNotEmpty()

        col.addView(Ui.title(this, if (hasAlbum) "Your photos" else "Show your photos"))

        val panes = Ui.twoColumns(this, col)
        val left = panes[0]
        val right = panes[1]

        val ssActive = isOurScreensaver()
        val ssCard = Ui.card(this)
        ssCard.addView(Ui.sectionLabel(this, "Screensaver"))
        val ssBody = Ui.body(
            this,
            if (ssActive) {
                "✓ Frame is your screensaver. Your photos appear when the Portal is idle."
            } else {
                "Almost there — tap below, then choose “Frame” so your photos show " +
                    "when the Portal is idle."
            },
        )
        topMargin(ssBody, 6)
        ssCard.addView(ssBody)
        ssCard.addView(
            if (ssActive) {
                Ui.outline(this, "Change screensaver") { openScreensaverSettings() }
            } else {
                Ui.primary(this, "Use as screensaver") { openScreensaverSettings() }
            },
        )
        left.addView(ssCard)

        val albumCard = Ui.card(this)
        albumCard.addView(Ui.sectionLabel(this, if (hasAlbum) "Album" else "No album yet"))
        if (hasAlbum) {
            val u = Ui.body(this, url)
            u.setTextColor(Ui.BLUE)
            u.setSingleLine(true)
            u.ellipsize = TextUtils.TruncateAt.MIDDLE
            topMargin(u, 6)
            albumCard.addView(u)
        } else {
            val none = Ui.body(
                this,
                "Add a Google Photos or iCloud shared album to show your own photos.",
            )
            topMargin(none, 6)
            albumCard.addView(none)
        }

        val rowAdd = Ui.outline(this, if (hasAlbum) "Change album" else "Add album", null)
        rowAdd.setOnClickListener {
            startScan()
        }
        albumCard.addView(rowAdd)

        if (hasAlbum) {
            val btnStop = Ui.outline(this, if (stopArmed) "Tap again to confirm" else "Stop showing photos", null)
            btnStop.setOnClickListener {
                if (stopArmed) {
                    Albums.clear(prefs())
                    showStatus()
                } else {
                    stopArmed = true
                    btnStop.text = "Tap again to confirm"
                    main.postDelayed({
                        if (stopArmed) {
                            stopArmed = false
                            showStatus()
                        }
                    }, 3000)
                }
            }
            albumCard.addView(btnStop)
        }
        left.addView(albumCard)

        val aiCard = Ui.card(this)
        aiCard.addView(Ui.sectionLabel(this, "Slideshow settings"))

        val rowDelay = Ui.row(this, "Time per photo", fmtDelay(getDelay()), null)
        rowDelay.setOnClickListener {
            val next = cycle(DELAY_CHOICES, getDelay(), 1)
            prefs().edit().putLong(ConfigReceiver.KEY_DELAY_MS, next).apply()
            Ui.setRowValue(rowDelay, fmtDelay(next))
        }
        aiCard.addView(rowDelay)
        aiCard.addView(Ui.hairline(this))

        val rowFade = Ui.row(this, "Transition", fadeLabel(getFade()), null)
        rowFade.setOnClickListener {
            val next = cycle(FADE_CHOICES, getFade(), 1)
            prefs().edit().putLong(ConfigReceiver.KEY_FADE_MS, next).apply()
            Ui.setRowValue(rowFade, fadeLabel(next))
        }
        aiCard.addView(rowFade)
        aiCard.addView(Ui.hairline(this))

        val rowShuffle = Ui.row(this, "Shuffle photos", if (getShuffle()) "On" else "Off", null)
        rowShuffle.setOnClickListener {
            val next = !getShuffle()
            prefs().edit().putBoolean(ConfigReceiver.KEY_SHUFFLE, next).apply()
            Ui.setRowValue(rowShuffle, if (next) "On" else "Off")
        }
        aiCard.addView(rowShuffle)
        aiCard.addView(Ui.hairline(this))

        val rowPairs = Ui.row(this, "Pair photos", if (getPairs()) "On" else "Off", null)
        rowPairs.setOnClickListener {
            val next = !getPairs()
            prefs().edit().putBoolean(ConfigReceiver.KEY_PAIRS, next).apply()
            Ui.setRowValue(rowPairs, if (next) "On" else "Off")
        }
        aiCard.addView(rowPairs)
        aiCard.addView(Ui.hairline(this))

        aiCard.addView(
            boolRow(
                "Ambient colors",
                ConfigReceiver.KEY_AMBIENT, ConfigReceiver.DEFAULT_AMBIENT,
            ),
        )
        aiCard.addView(Ui.hairline(this))
        aiCard.addView(
            boolRow(
                "Clock & weather",
                ConfigReceiver.KEY_CLOCK, ConfigReceiver.DEFAULT_CLOCK,
            ),
        )
        aiCard.addView(Ui.hairline(this))
        aiCard.addView(
            boolRow(
                "Night warmth",
                ConfigReceiver.KEY_NIGHT, ConfigReceiver.DEFAULT_NIGHT,
            ),
        )
        aiCard.addView(Ui.hairline(this))
        aiCard.addView(
            boolRow(
                "On This Day memories",
                ConfigReceiver.KEY_ON_THIS_DAY, ConfigReceiver.DEFAULT_ON_THIS_DAY,
            ),
        )
        right.addView(aiCard)

        val tipsCard = Ui.card(this)
        tipsCard.addView(Ui.sectionLabel(this, "Tips"))
        val howto = Ui.body(
            this,
            "On your phone: open Google Photos, open the album you want, tap Share, and " +
                "choose Create link. Then tap " +
                (if (hasAlbum) "Change album" else "Add album") +
                " here and scan the QR code with your phone to paste the link.\n\n" +
                "Tip: the album must be shared by link so the frame can see it.",
        )
        topMargin(howto, 6)
        tipsCard.addView(howto)
        right.addView(tipsCard)

        val done = Ui.secondary(this, "Done") { finish() }
        topMargin(done, 24)
        col.addView(done)
    }

    private fun topMargin(v: View, dp: Int) {
        var lp = v.layoutParams as? LinearLayout.LayoutParams
        if (lp == null) {
            lp = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        lp.topMargin = Ui.dp(this, dp.toFloat())
        v.layoutParams = lp
    }

    private fun sectionHeading(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(0xFFF0F0F0.toInt())
        t.typeface = Ui.bold(this)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        t.gravity = Gravity.CENTER_HORIZONTAL
        t.setShadowLayer(8f, 0f, 1f, Color.BLACK)
        return t
    }

    private fun boolRow(label: String, key: String, def: Boolean): View {
        val row = Ui.row(this, label, if (prefs().getBoolean(key, def)) "On" else "Off", null)
        row.setOnClickListener {
            val next = !prefs().getBoolean(key, def)
            prefs().edit().putBoolean(key, next).apply()
            Ui.setRowValue(row, if (next) "On" else "Off")
        }
        return row
    }

    // ------------------------------------------------ Manual entry / local server

    private fun addTypedAlbum(edit: EditText) {
        val url = edit.text.toString().trim()
        if (url.isEmpty()) {
            finish()
            return
        }
        if (!isPhotosLink(url)) {
            toast("That doesn't look like a Google Photos or iCloud link")
            return
        }
        Albums.add(prefs(), url)
        Log.i(TAG, "album added via manual entry: $url")
        hideKeyboard(edit)
        toast("Album added ✓")
        finish()
    }

    private fun pillButton(label: String, fill: Int, textColor: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.typeface = Ui.medium(this)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        b.setTextColor(textColor)
        val h = Ui.dp(this, 56f)
        b.minHeight = h
        b.minimumHeight = h
        b.stateListAnimator = null
        val padH = Ui.dp(this, 36f)
        b.setPadding(padH, 0, padH, 0)
        b.background = Ui.roundRect(fill, Ui.dp(this, 14f))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun hexToString(hex: String): String {
        return try {
            val bytes = ByteArray(hex.length / 2)
            for (i in bytes.indices) {
                bytes[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun decryptData(hexStr: String, keyStr: String): String {
        return try {
            val data = ByteArray(hexStr.length / 2)
            for (i in data.indices) {
                data[i] = hexStr.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            
            val iv = data.copyOfRange(0, 16)
            val ciphertext = data.copyOfRange(16, data.size)
            
            val keySpec = SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))
            
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            ""
        }
    }

    private fun checkCloudAlbumLink(code: String, aesKey: String) {
        val exe = ImageLoader(this).executor()
        exe.execute {
            try {
                val connection = java.net.URL("https://keyvalue.immanuel.co/api/KeyVal/GetValue/cs79vqdm/$code").openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                if (connection.responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    var response = reader.readLine()?.trim() ?: ""
                    reader.close()
                    
                    if (response.startsWith("\"") && response.endsWith("\"")) {
                        response = response.substring(1, response.length - 1)
                    }
                    
                    if (response.isNotEmpty() && response != "null") {
                        val decodedUrl = decryptData(response, aesKey).trim()
                        if (isPhotosLink(decodedUrl)) {
                            runOnUiThread {
                                Albums.add(prefs(), decodedUrl)
                                Log.i(TAG, "album added via cloud pairing code $code: $decodedUrl")
                                toast("Album added ✓")
                                cloudPollRunnable?.let { main.removeCallbacks(it) }
                                cloudPollRunnable = null
                                finish()
                            }
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "cloud polling check failed", e)
            }
        }
    }

    // ------------------------------------------------------ QR / Web Server Flow

    private fun startScan() {
        stopArmed = false
        showingStatus = false
        root.removeAllViews()

        val code = (100000..999999).random().toString()
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val aesKey = (1..16).map { chars.random() }.joinToString("")
        val cloudUrl = "https://raw.githack.com/Tech33/Portal-Frame/main/add.html?code=$code&key=$aesKey&v=1.6.3"
        val formattedCode = "${code.substring(0, 3)} ${code.substring(3, 6)}"

        val ip = getLocalIpAddress()
        if (ip != null) {
            val url = "http://$ip:8080"
            startServer(url)
        }
        overrideBrightness()

        // Start cloud polling
        cloudPollRunnable?.let { main.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                checkCloudAlbumLink(code, aesKey)
                main.postDelayed(this, 3000)
            }
        }
        cloudPollRunnable = poll
        main.post(poll)

        val fontScale = Ui.fontScale(this)
        val f = FrameLayout(this)
        f.setBackgroundColor(Color.BLACK)

        val boxSize = Ui.dp(this, 280f)
        val boxTop = Ui.dp(this, 56f)

        // QR Code display container with Apple squircle border
        val qrImage = ImageView(this)
        qrImage.scaleType = ImageView.ScaleType.FIT_CENTER
        val border = Ui.roundRect(0xFFFFFFFF.toInt(), Ui.dp(this, 24f))
        qrImage.background = border
        val pad = Ui.dp(this, 16f)
        qrImage.setPadding(pad, pad, pad, pad)

        val qrBitmap = generateQrCode(cloudUrl, 280)
        if (qrBitmap != null) {
            qrImage.setImageBitmap(qrBitmap)
        }

        val bp = FrameLayout.LayoutParams(boxSize, boxSize)
        bp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        bp.topMargin = boxTop
        f.addView(qrImage, bp)

        val colW = Math.min(Ui.dp(this, 640f), resources.displayMetrics.widthPixels - Ui.dp(this, 48f))

        val title = TextView(this)
        title.text = "Add an Album"
        title.setTextColor(Color.WHITE)
        title.typeface = Ui.bold(this)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f * fontScale)
        title.gravity = Gravity.CENTER_HORIZONTAL
        val titleLp = FrameLayout.LayoutParams(colW, WRAP)
        titleLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        titleLp.topMargin = Ui.dp(this, 16f)
        f.addView(title, titleLp)

        val belowBox = LinearLayout(this)
        belowBox.orientation = LinearLayout.VERTICAL
        belowBox.gravity = Gravity.CENTER_HORIZONTAL

        belowBox.addView(sectionHeading("Scan with your phone"), LinearLayout.LayoutParams(MATCH, WRAP))

        val subtitle = TextView(this)
        this.scanHint = subtitle
        var helperText = "Scan the QR code to open the setup helper on your phone, or visit:\nraw.githack.com/Tech33/Portal-Frame/main/add.html\nPairing Code: $formattedCode"
        if (ip != null) {
            helperText += "\n\nOffline backup: visit http://$ip:8080 on the same Wi-Fi"
        }
        subtitle.text = helperText
        subtitle.setTextColor(0xFFE5E5EA.toInt())
        subtitle.typeface = Ui.medium(this)
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
        subtitle.gravity = Gravity.CENTER_HORIZONTAL
        subtitle.setLineSpacing(Ui.dp(this, 4f).toFloat(), 1f)
        val subLp = LinearLayout.LayoutParams(MATCH, WRAP)
        subLp.topMargin = Ui.dp(this, 8f)
        belowBox.addView(subtitle, subLp)

        val manualHeading = sectionHeading("Or enter the link manually")
        val manualHeadingLp = LinearLayout.LayoutParams(MATCH, WRAP)
        manualHeadingLp.topMargin = Ui.dp(this, 32f)
        belowBox.addView(manualHeading, manualHeadingLp)

        val edit = Ui.field(this, "Paste a Google Photos or iCloud link")
        edit.setSingleLine(true)
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * fontScale)
        edit.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        belowBox.addView(edit)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = Ui.dp(this@PhotosActivity, 14f)
            }
            layoutParams = lp
        }

        val pasteBtn = pillButton("📋 Paste", 0xFF2C2C2E.toInt(), Color.WHITE) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()?.trim()
                    if (!text.isNullOrEmpty()) {
                        edit.setText(text)
                        edit.setSelection(text.length)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read clipboard", e)
            }
        }
        val pasteLp = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            rightMargin = Ui.dp(this@PhotosActivity, 8f)
        }
        buttonRow.addView(pasteBtn, pasteLp)

        val clearBtn = pillButton("✕ Clear", 0xFF2C2C2E.toInt(), Color.WHITE) {
            edit.setText("")
        }
        val clearLp = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            rightMargin = Ui.dp(this@PhotosActivity, 8f)
        }
        buttonRow.addView(clearBtn, clearLp)

        val doneBtn = pillButton("Done", Ui.BLUE, Color.WHITE) {
            addTypedAlbum(edit)
        }
        val doneLp = LinearLayout.LayoutParams(0, WRAP, 1.2f)
        buttonRow.addView(doneBtn, doneLp)

        belowBox.addView(buttonRow)

        val belowLp = FrameLayout.LayoutParams(colW, WRAP)
        belowLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        belowLp.topMargin = boxTop + boxSize + Ui.dp(this, 20f)
        f.addView(belowBox, belowLp)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(f, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private var initialAnnouncementPayload: String? = null

    private fun startAnnouncementScan() {
        stopArmed = false
        showingStatus = false
        root.removeAllViews()

        val p = prefs()
        val channel = p.getString(ConfigReceiver.KEY_ANNOUNCEMENT_CHANNEL, ConfigReceiver.DEFAULT_ANNOUNCEMENT_CHANNEL)?.trim()
            ?.ifEmpty { ConfigReceiver.DEFAULT_ANNOUNCEMENT_CHANNEL } ?: ConfigReceiver.DEFAULT_ANNOUNCEMENT_CHANNEL
        val aesKey = CryptoUtils.deriveAesKey(channel)
        val cloudUrl = "https://raw.githack.com/Tech33/Portal-Frame/main/message.html?channel=$channel&key=$aesKey&v=1.6.5"

        overrideBrightness()

        initialAnnouncementPayload = null
        cloudPollRunnable?.let { main.removeCallbacks(it) }
        val poll = object : Runnable {
            override fun run() {
                checkCloudAnnouncement(channel, aesKey)
                main.postDelayed(this, 3000)
            }
        }
        cloudPollRunnable = poll
        main.post(poll)

        val fontScale = Ui.fontScale(this)
        val f = FrameLayout(this)
        f.setBackgroundColor(Color.BLACK)

        val boxSize = Ui.dp(this, 280f)
        val boxTop = Ui.dp(this, 56f)

        // QR Code display container with Apple squircle border
        val qrImage = ImageView(this)
        qrImage.scaleType = ImageView.ScaleType.FIT_CENTER
        val border = Ui.roundRect(0xFFFFFFFF.toInt(), Ui.dp(this, 24f))
        qrImage.background = border
        val pad = Ui.dp(this, 16f)
        qrImage.setPadding(pad, pad, pad, pad)

        val qrBitmap = generateQrCode(cloudUrl, 280)
        if (qrBitmap != null) {
            qrImage.setImageBitmap(qrBitmap)
        }

        val bp = FrameLayout.LayoutParams(boxSize, boxSize)
        bp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        bp.topMargin = boxTop
        f.addView(qrImage, bp)

        val colW = Math.min(Ui.dp(this, 640f), resources.displayMetrics.widthPixels - Ui.dp(this, 48f))

        val title = TextView(this)
        title.text = "Post Announcement"
        title.setTextColor(Color.WHITE)
        title.typeface = Ui.bold(this)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f * fontScale)
        title.gravity = Gravity.CENTER_HORIZONTAL
        val titleLp = FrameLayout.LayoutParams(colW, WRAP)
        titleLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        titleLp.topMargin = Ui.dp(this, 16f)
        f.addView(title, titleLp)

        val belowBox = LinearLayout(this)
        belowBox.orientation = LinearLayout.VERTICAL
        belowBox.gravity = Gravity.CENTER_HORIZONTAL

        belowBox.addView(sectionHeading("Scan with your phone"), LinearLayout.LayoutParams(MATCH, WRAP))

        val subtitle = TextView(this)
        this.scanHint = subtitle
        val helperText = "Scan the QR code to post greetings, celebrations, or photo showcases from your phone, or visit:\nraw.githack.com/Tech33/Portal-Frame/main/message.html\nFamily Network: $channel"
        subtitle.text = helperText
        subtitle.setTextColor(0xFFE5E5EA.toInt())
        subtitle.typeface = Ui.medium(this)
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f * fontScale)
        subtitle.gravity = Gravity.CENTER_HORIZONTAL
        subtitle.setLineSpacing(Ui.dp(this, 4f).toFloat(), 1f)
        val subLp = LinearLayout.LayoutParams(MATCH, WRAP)
        subLp.topMargin = Ui.dp(this, 8f)
        belowBox.addView(subtitle, subLp)

        val manualHeading = sectionHeading("Or enter message directly on Portal")
        val manualHeadingLp = LinearLayout.LayoutParams(MATCH, WRAP)
        manualHeadingLp.topMargin = Ui.dp(this, 28f)
        belowBox.addView(manualHeading, manualHeadingLp)

        val edit = Ui.field(this, "e.g. Happy Birthday Sarah! 🎂")
        edit.setSingleLine(true)
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f * fontScale)
        belowBox.addView(edit)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = Ui.dp(this@PhotosActivity, 14f)
            }
            layoutParams = lp
        }

        val pasteBtn = pillButton("📋 Paste", 0xFF2C2C2E.toInt(), Color.WHITE) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = cm.primaryClip
                if (clip != null && clip.itemCount > 0) {
                    val text = clip.getItemAt(0).text?.toString()?.trim()
                    if (!text.isNullOrEmpty()) {
                        edit.setText(text)
                        edit.setSelection(text.length)
                    }
                }
            } catch (_: Exception) {}
        }
        val pasteLp = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginEnd = Ui.dp(this@PhotosActivity, 10f)
        }
        buttonRow.addView(pasteBtn, pasteLp)

        val curMsg = p.getString(ConfigReceiver.KEY_CUSTOM_MESSAGE, "")?.trim() ?: ""
        if (curMsg.isNotEmpty()) {
            val clearBtn = pillButton("✕ Clear", 0xFF3A3A3C.toInt(), 0xFFFF453A.toInt()) {
                p.edit().remove(ConfigReceiver.KEY_CUSTOM_MESSAGE).apply()
                sendBroadcast(Intent(ConfigReceiver.ACTION_CLEAR_MESSAGE).setPackage(packageName))
                MqttManager.getInstance(this).publishAllStates()
                toast("Announcement cleared ✓")
                finish()
            }
            val clearLp = LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginEnd = Ui.dp(this@PhotosActivity, 10f)
            }
            buttonRow.addView(clearBtn, clearLp)
        } else {
            val cancelBtn = pillButton("Cancel", 0xFF2C2C2E.toInt(), 0xFF8E8E93.toInt()) {
                finish()
            }
            val cancelLp = LinearLayout.LayoutParams(0, WRAP, 0.9f).apply {
                marginEnd = Ui.dp(this@PhotosActivity, 10f)
            }
            buttonRow.addView(cancelBtn, cancelLp)
        }

        val postBtn = pillButton("Post Banner", Ui.BLUE, Color.WHITE) {
            val text = edit.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                p.edit().putString(ConfigReceiver.KEY_CUSTOM_MESSAGE, text).apply()
                sendBroadcast(Intent(ConfigReceiver.ACTION_SET_MESSAGE).setPackage(packageName).putExtra("message", text))
                MqttManager.getInstance(this).publishAllStates()
                hideKeyboard(edit)
                toast("Announcement set ✓")
                finish()
            }
        }
        val postLp = LinearLayout.LayoutParams(0, WRAP, 1.2f)
        buttonRow.addView(postBtn, postLp)

        belowBox.addView(buttonRow)

        val belowLp = FrameLayout.LayoutParams(colW, WRAP)
        belowLp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        belowLp.topMargin = boxTop + boxSize + Ui.dp(this, 20f)
        f.addView(belowBox, belowLp)

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(f, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        root.addView(scroll, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun checkCloudAnnouncement(channel: String, aesKey: String) {
        val exe = ImageLoader(this).executor()
        exe.execute {
            try {
                val encodedChannel = java.net.URLEncoder.encode(channel, "UTF-8")
                val connection = java.net.URL("https://keyvalue.immanuel.co/api/KeyVal/GetValue/cs79vqdm/$encodedChannel").openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                if (connection.responseCode == 200) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(connection.inputStream))
                    var response = reader.readLine()?.trim() ?: ""
                    reader.close()
                    if (response.startsWith("\"") && response.endsWith("\"")) {
                        response = response.substring(1, response.length - 1)
                    }
                    if (initialAnnouncementPayload == null) {
                        initialAnnouncementPayload = response
                    } else if (response != initialAnnouncementPayload && response.isNotEmpty() && response != "null") {
                        val decrypted = CryptoUtils.decrypt(response, aesKey).trim()
                        if (decrypted == "__CLEAR__") {
                            runOnUiThread {
                                prefs().edit().remove(ConfigReceiver.KEY_CUSTOM_MESSAGE).apply()
                                sendBroadcast(Intent(ConfigReceiver.ACTION_CLEAR_MESSAGE).setPackage(packageName))
                                MqttManager.getInstance(this).publishAllStates()
                                toast("Announcement cleared ✓")
                                cloudPollRunnable?.let { main.removeCallbacks(it) }
                                cloudPollRunnable = null
                                finish()
                            }
                        } else if (decrypted.isNotEmpty()) {
                            runOnUiThread {
                                prefs().edit().putString(ConfigReceiver.KEY_CUSTOM_MESSAGE, decrypted).apply()
                                sendBroadcast(Intent(ConfigReceiver.ACTION_SET_MESSAGE).setPackage(packageName).putExtra("message", decrypted))
                                MqttManager.getInstance(this).publishAllStates()
                                toast("Broadcast received ✓")
                                cloudPollRunnable?.let { main.removeCallbacks(it) }
                                cloudPollRunnable = null
                                finish()
                            }
                        }
                    }
                }
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "announcement cloud polling failed", e)
            }
        }
    }

    private fun showNoWifiScreen() {
        val f = LinearLayout(this)
        f.orientation = LinearLayout.VERTICAL
        f.gravity = Gravity.CENTER
        f.setBackgroundColor(Ui.BG)
        val padding = Ui.dp(this, 24f)
        f.setPadding(padding, padding, padding, padding)

        val title = TextView(this)
        title.text = "No Wi-Fi Connection"
        title.setTextColor(Color.WHITE)
        title.typeface = Ui.bold(this)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        title.gravity = Gravity.CENTER
        f.addView(title)

        val desc = TextView(this)
        desc.text = "Your Portal must be connected to Wi-Fi to add albums using the QR helper or to stream photos. Please check your network and try again."
        desc.setTextColor(0xFFD2D2D2.toInt())
        desc.typeface = Ui.medium(this)
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        desc.gravity = Gravity.CENTER
        val descLp = LinearLayout.LayoutParams(Ui.dp(this, 400f), WRAP)
        descLp.topMargin = Ui.dp(this, 16f)
        desc.layoutParams = descLp
        f.addView(desc)

        val btnBack = pillButton("Back", Ui.BLUE, Color.WHITE) { showStatus() }
        val btnLp = LinearLayout.LayoutParams(WRAP, WRAP)
        btnLp.topMargin = Ui.dp(this, 32f)
        btnBack.layoutParams = btnLp
        f.addView(btnBack)

        root.addView(f, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress
                        if (sAddr != null && sAddr.indexOf(':') < 0) {
                            return sAddr
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to get local IP", ex)
        }
        return null
    }

    private fun generateQrCode(text: String, sizeDp: Int): Bitmap? {
        val size = Ui.dp(this, sizeDp.toFloat())
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate QR code", e)
            null
        }
    }

    private fun startServer(url: String) {
        val s = AlbumServer.startServer(this, 8080)
        s.setOnUrlReceived { inputUrl ->
            var success = false
            if (isPhotosLink(inputUrl)) {
                Albums.add(prefs(), inputUrl)
                Log.i(TAG, "Album added via local server: $inputUrl")
                main.post {
                    toast("Album added ✓")
                    cloudPollRunnable?.let { main.removeCallbacks(it) }
                    cloudPollRunnable = null
                    finish()
                }
                success = true
            }
            success
        }
        server = s
    }

    private fun stopServer() {
        server?.setOnUrlReceived(null)
        val prefs = prefs()
        if (!prefs.getBoolean(ConfigReceiver.KEY_HA_BRIDGE_MODE, ConfigReceiver.DEFAULT_HA_BRIDGE_MODE)) {
            // Keep running if HA bridge mode is active, otherwise can shut down
        }
        server = null
    }

    private fun overrideBrightness() {
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        window.attributes = lp
    }

    private fun restoreBrightness() {
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
    }

    private fun toast(m: String) {
        Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "PortalFrame"
        private val DELAY_CHOICES = longArrayOf(4000L, 6000L, 10000L, 30000L, 60000L)
        private val FADE_CHOICES = longArrayOf(2000L, 1200L, 500L)
        private val FADE_LABELS = arrayOf("Slow", "Normal", "Fast")

        private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private fun cycle(choices: LongArray, cur: Long, fallback: Int): Long {
            for (i in choices.indices) {
                if (choices[i] == cur) {
                    return choices[(i + 1) % choices.size]
                }
            }
            return choices[fallback]
        }

        private fun fadeLabel(ms: Long): String {
            for (i in FADE_CHOICES.indices) {
                if (FADE_CHOICES[i] == ms) {
                    return FADE_LABELS[i]
                }
            }
            return "Normal"
        }

        private fun fmtDelay(ms: Long): String {
            val s = ms / 1000
            return if (s >= 60) "${s / 60}m" else "${s}s"
        }

        private fun isPhotosLink(s: String): Boolean = PhotoSources.matches(s)
    }
}
